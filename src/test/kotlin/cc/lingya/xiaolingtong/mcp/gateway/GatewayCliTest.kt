package cc.lingya.xiaolingtong.mcp.gateway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 启动参数和跨平台命令边界验证。
 *
 * @author 思追(shaco)
 */
class GatewayCliTest {

    /** 默认发布为隔离的有状态 HTTP。 */
    @Test
    fun `默认值及含空格参数保持完整`() {
        val cli = GatewayCli.commandLine()
        val config = GatewayCli.config(cli.parseArgs("--from", "stdio", "--", "my program", "中文 参数", "--flag"))
        assertEquals(listOf("my program", "中文 参数", "--flag"), config.command)
        assertEquals("streamable-http", config.to)
        assertEquals("isolated", config.processScope)
        assertEquals("stateful", config.httpMode)
    }

    /** 不允许模糊的传输组合或协议头覆盖。 */
    @Test
    fun `拒绝无效组合和保留头`() {
        listOf(
            arrayOf("--from", "sse", "--url", "https://example.com", "--to", "ws"),
            arrayOf("--from", "stdio", "--response-header", "Mcp-Session-Id: x", "--", "server"),
            arrayOf("--from", "stdio", "--shell-command", "echo x", "--", "server"),
        ).forEach { args ->
            assertFailsWith<IllegalArgumentException> { GatewayCli.config(GatewayCli.commandLine().parseArgs(*args)) }
        }
    }

    /** 重复头和来源列表不能静默丢失。 */
    @Test
    fun `重复参数全部保留`() {
        val config =
            GatewayCli.config(
                GatewayCli.commandLine().parseArgs(
                    "--from",
                    "sse",
                    "--url",
                    "https://example.com/mcp?q=1",
                    "--upstream-header",
                    "X-One: 1",
                    "--upstream-header",
                    "X-Two: 2",
                    "--allow-origin",
                    "https://a.test",
                    "--allow-origin",
                    "https://b.test",
                ),
            )
        assertEquals(mapOf("x-one" to "1", "x-two" to "2"), config.upstreamHeaders)
        assertEquals(2, config.origins.size)
    }
}
