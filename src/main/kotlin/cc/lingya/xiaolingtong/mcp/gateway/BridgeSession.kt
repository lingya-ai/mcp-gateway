package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.node.ObjectNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个客户端的协议状态；HTTP 请求流与后台通知流分别拥有输出邮箱。
 *
 * @author 思追(shaco)
 */
class BridgeSession(val config: GatewayConfig, val router: MessageRouter) : AutoCloseable {

    val id: String = UUID.randomUUID().toString()
    val closed: AtomicBoolean = AtomicBoolean()
    val listening: AtomicBoolean = AtomicBoolean()

    @Volatile var initialized: Boolean = false

    @Volatile var protocolVersion: String = config.protocolVersion

    @Volatile var lastAccess: Long = System.nanoTime()
    private var background = MessageMailbox(config.queueCapacity)
    private val exchanges = ConcurrentHashMap<String, MessageMailbox>()

    /** 安装单次 HTTP 请求的响应流，重复 ID 在同一会话内拒绝。 */
    fun exchange(message: ObjectNode): MessageMailbox {
        val mailbox = MessageMailbox(config.queueCapacity)
        check(exchanges.putIfAbsent(message.path("id").toString(), mailbox) == null) { "会话内请求 ID 重复" }
        return mailbox
    }

    /** 完成或取消 HTTP 请求时移除邮箱；不会销毁有状态会话。 */
    fun finishExchange(key: String) {
        router.cancel(this, key)
        exchanges.remove(key)?.complete()
        touch()
    }

    /** 取得唯一后台流，拒绝同时打开第二条监听连接。 */
    @Synchronized
    fun listen(): MessageMailbox {
        check(listening.compareAndSet(false, true)) { "会话已有监听连接" }
        return background
    }

    /** GET 断开允许重新建立连接，但不重放已结束的流。 */
    @Synchronized
    fun stopListening() {
        background.complete()
        background = MessageMailbox(config.queueCapacity)
        listening.set(false)
        touch()
    }

    /** 收到请求或完成响应时更新空闲计时。 */
    fun touch() {
        lastAccess = System.nanoTime()
    }

    /** 是否可按空闲超时回收；活动请求或监听流不会被回收。 */
    fun idle(): Boolean = !listening.get() && exchanges.isEmpty() && !router.hasPending(this) &&
        System.nanoTime() - lastAccess >= config.idleTimeout.toNanos()

    /** 接收已恢复原 ID 的消息并选择唯一的客户端响应流。 */
    @Synchronized
    fun deliver(message: ObjectNode, requestKey: String? = null) {
        if (closed.get()) return
        touch()
        val response = !message.has("method")
        val mailbox = if (requestKey == null) null else exchanges[requestKey]
        val target = mailbox ?: if (listening.get() || exchanges.isEmpty()) background else exchanges.values.first()
        if (config.stateless && mailbox == null) return
        target.send(message)
        if (response && mailbox != null) mailbox.complete()
    }

    /** 终止所属请求和流；隔离进程由会话独占，共享进程继续存活。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        exchanges.values.forEach { it.complete() }
        exchanges.clear()
        background.complete()
        router.detach(this)
        if (!config.shared) router.close()
    }
}
