package cc.lingya.xiaolingtong.mcp.gateway

import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * 验证真实子进程的字符流、异常和回收，不依赖 shell 命令转义。
 *
 * @author 思追(shaco)
 */
class GatewayProcessTest {

    /** UTF-8 的每个字节分开读取仍保留原始字符。 */
    @Test
    fun `分片中文和换行边界`() {
        val input = object : FilterInputStream(ByteArrayInputStream("中文\r\n尾行".toByteArray())) {

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 1))
        }
        val lines = mutableListOf<String>()
        BoundedLines.read(input, 64) { lines.add(it) }
        assertEquals(listOf("中文", "尾行"), lines)
        assertFailsWith<IllegalStateException> { BoundedLines.read(ByteArrayInputStream(ByteArray(100) { 65 }), 8) {} }
    }

    /** stderr 不混入协议流，关闭后进程确实退出。 */
    @Test
    fun `真实子进程消息与回收`() {
        val received = LinkedBlockingQueue<ObjectNode>()
        val errors = LinkedBlockingQueue<Throwable>()
        val receiver = object : MessageReceiver {

            override fun receive(message: ObjectNode) {
                received.add(message)
            }
            override fun failed(error: Throwable) {
                errors.add(error)
            }
        }
        val process = ProcessSupervisor(GatewayConfig(command = GatewayFixture.command("stderr")))
        process.start(receiver)
        process.send(GatewayJson.parse("""{"jsonrpc":"2.0","id":1,"method":"pid"}"""))
        val pid = assertNotNull(received.poll(10, TimeUnit.SECONDS)).path("result").path("pid").asLong()
        process.close()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(0, errors.size)
    }

    /** 异常退出应通知会话管理器。 */
    @Test
    fun `退出事件传播`() {
        val errors = LinkedBlockingQueue<Throwable>()
        ProcessSupervisor(GatewayConfig(command = GatewayFixture.command("exit"))).use { process ->
            process.start(object : MessageReceiver {

                override fun receive(message: ObjectNode) = Unit
                override fun failed(error: Throwable) {
                    errors.add(error)
                }
            })
            assertNotNull(errors.poll(10, TimeUnit.SECONDS))
        }
    }
}
