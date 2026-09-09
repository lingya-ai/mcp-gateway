package cc.lingya.xiaolingtong.mcp.gateway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * 真进程验证 HTTP 会话与子进程的对应关系。
 *
 * @author 思追(shaco)
 */
class GatewaySessionTest {

    /** 有状态响应完成后保留进程，DELETE 后会话不再可用。 */
    @Test
    fun `有状态会话保持与删除`() {
        GatewayHttpServer(GatewayConfig(command = GatewayFixture.command(), port = 0)).use { server ->
            server.start()
            GatewayTestClient(server.port).use { client ->
                client.initialize()
                assertNotNull(client.sessionId)
                val request = """{"jsonrpc":"2.0","id":0,"method":"pid"}"""
                val first = client.result(client.send("POST", request)).path("result")
                assertEquals(first, client.result(client.send("POST", request)).path("result"))
                assertEquals(204, client.send("DELETE").statusCode())
                assertEquals(404, client.send("POST", request).statusCode())
            }
        }
    }

    /** 无状态隔离每次启动新进程，共享模式维持同一进程。 */
    @Test
    fun `无状态请求按进程策略复用`() {
        for (scope in listOf("isolated", "shared")) {
            val config =
                GatewayConfig(
                    command = GatewayFixture.command(),
                    port = 0,
                    httpMode = "stateless",
                    processScope = scope,
                )
            GatewayHttpServer(config).use { server ->
                server.start()
                GatewayTestClient(server.port).use { client ->
                    val request = """{"jsonrpc":"2.0","id":"a:1","method":"pid"}"""
                    val first = client.result(client.send("POST", request))
                    val second = client.result(client.send("POST", request))
                    if (scope ==
                        "shared"
                    ) {
                        assertEquals(first, second)
                    } else {
                        assertNotEquals(first.path("result"), second.path("result"))
                    }
                    assertEquals(405, client.send("GET").statusCode())
                    assertEquals(405, client.send("DELETE").statusCode())
                }
            }
        }
    }
}
