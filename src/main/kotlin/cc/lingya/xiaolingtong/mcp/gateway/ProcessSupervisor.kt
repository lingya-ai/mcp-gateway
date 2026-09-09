package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拥有一个 MCP 子进程及其输入输出线程，关闭时同时回收已跟踪的后代进程。
 *
 * @author 思追(shaco)
 */
class ProcessSupervisor(private val config: GatewayConfig) : UpstreamConnection {

    private val closed = AtomicBoolean()
    private val outbound = ArrayBlockingQueue<String>(config.queueCapacity)
    private var process: Process? = null
    private val threads = mutableListOf<Thread>()

    /** 启动进程，以虚拟线程处理有界输入输出。 */
    override fun start(receiver: MessageReceiver) {
        check(process == null && !closed.get()) { "进程已经启动或关闭" }
        val child = ProcessBuilder(config.command).start()
        process = child
        threads +=
            Thread.ofVirtual().name("mcp-child-output").start {
                try {
                    BoundedLines.read(child.inputStream, config.maxMessageBytes) {
                        receiver.receive(GatewayJson.parse(it))
                    }
                    if (!closed.get()) receiver.failed(IOException("MCP 子进程输出已关闭"))
                } catch (error: Exception) {
                    if (!closed.get()) receiver.failed(error)
                }
            }
        threads +=
            Thread.ofVirtual().name("mcp-child-input").start {
                try {
                    child.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        while (!closed.get()) {
                            writer.write(outbound.take())
                            writer.newLine()
                            writer.flush()
                        }
                    }
                } catch (error: Exception) {
                    if (!closed.get()) receiver.failed(error)
                }
            }
        threads +=
            Thread.ofVirtual().name("mcp-child-error").start {
                try {
                    BoundedLines.read(child.errorStream, config.maxMessageBytes) {
                        if (config.logLevel != "none") System.err.println("[child] $it")
                    }
                } catch (error: Exception) {
                    if (!closed.get()) receiver.failed(error)
                }
            }
        child.onExit().thenAccept {
            if (!closed.get()) receiver.failed(IOException("MCP 子进程退出，退出码 ${it.exitValue()}"))
        }
    }

    /** 将完整消息加入发送队列，满队列立即失败而不是阻塞网络线程。 */
    override fun send(message: ObjectNode) {
        check(!closed.get()) { "子进程已关闭" }
        val text = message.toString()
        check(text.toByteArray(Charsets.UTF_8).size <= config.maxMessageBytes) { "消息超过字节上限" }
        check(outbound.offer(text)) { "子进程发送队列已满" }
    }

    /** 幂等回收进程；关闭宽限期后强制终止仍存活的后代和主进程。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val child = process ?: return
        val descendants = child.descendants().use { it.toList().reversed() }
        descendants.forEach { it.destroy() }
        child.destroy()
        try {
            child.waitFor(config.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (child.isAlive) child.destroyForcibly()
            threads.forEach { it.interrupt() }
            outbound.clear()
        }
    }
}
