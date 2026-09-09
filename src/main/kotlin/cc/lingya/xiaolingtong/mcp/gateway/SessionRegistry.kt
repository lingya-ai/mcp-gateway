package cc.lingya.xiaolingtong.mcp.gateway

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拥有 HTTP 会话和共享进程，用到期队列处理会话生命周期而非集群调度。
 *
 * @author 思追(shaco)
 */
class SessionRegistry(private val config: GatewayConfig) : AutoCloseable {

    private val sessions = ConcurrentHashMap<String, BridgeSession>()
    private val deadlines = DelayQueue<SessionDeadline>()
    private val closed = AtomicBoolean()
    private var shared: MessageRouter? = null
    private var reaper: Thread? = null
    val failure: CompletableFuture<Throwable> = CompletableFuture()

    /** 初始化共享进程，并启动阻塞等待会话到期的虚拟线程。 */
    fun start() {
        if (config.shared) {
            val router = MessageRouter(config, ProcessSupervisor(config))
            shared = router
            router.failure.thenAccept { failure.complete(it) }
            router.start()
        }
        reaper =
            Thread.ofVirtual().name("mcp-session-expiry").start {
                try {
                    while (!closed.get()) {
                        val deadline = deadlines.take()
                        val session = sessions[deadline.sessionId] ?: continue
                        if (session.closed.get() || session.idle()) {
                            remove(session.id)
                        } else {
                            deadlines.offer(SessionDeadline(session.id, config.idleTimeout.toNanos()))
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
    }

    /** 创建一个已连接上游的客户端会话，初始化失败时立即回收进程。 */
    @Synchronized
    fun create(): BridgeSession {
        check(!closed.get()) { "网关正在关闭" }
        sessions.values.filter { it.closed.get() || it.idle() }.forEach { remove(it.id) }
        check(sessions.size < config.maxSessions) { "客户端会话数量超过上限" }
        val router = shared ?: MessageRouter(config, ProcessSupervisor(config))
        val session = BridgeSession(config, router)
        try {
            router.attach(session)
            if (!config.shared) router.start()
            sessions[session.id] = session
            deadlines.offer(SessionDeadline(session.id, config.idleTimeout.toNanos()))
            return session
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    /** 查询仍然有效的会话。 */
    fun find(id: String): BridgeSession? = sessions[id]?.takeUnless { it.closed.get() || it.idle() }

    /** 结束指定客户端，未知标识不会影响其他会话。 */
    fun remove(id: String) {
        sessions.remove(id)?.close()
        deadlines.removeIf { it.sessionId == id }
    }

    /** 停止接收会话并回收所有拥有的子进程。 */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reaper?.interrupt()
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        deadlines.clear()
        shared?.close()
    }
}
