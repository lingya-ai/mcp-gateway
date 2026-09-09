package cc.lingya.xiaolingtong.mcp.gateway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * HTTP 协议校验、认证与 CORS 分别验证。
 *
 * @author 思追(shaco)
 */
class GatewayHttpContractTest {

    /** 认证失败不能启动业务子进程，预检只处理 CORS。 */
    @Test
    fun `认证和来源独立校验`() {
        val config =
            GatewayConfig(
                command = GatewayFixture.command(),
                port = 0,
                listenToken = "secret",
                origins = setOf("https://allowed.test"),
            )
        GatewayHttpServer(config).use { server ->
            server.start()
            GatewayTestClient(server.port).use { client ->
                assertEquals(401, client.send("POST", "{}").statusCode())
                assertEquals(
                    403,
                    client.send("POST", "{}", headers = mapOf("Origin" to "https://denied.test")).statusCode(),
                )
                assertEquals(
                    204,
                    client.send("OPTIONS", headers = mapOf("Origin" to "https://allowed.test")).statusCode(),
                )
            }
        }
    }

    /** 无效请求必须获得 HTTP 错误而不是挂起。 */
    @Test
    fun `请求格式和版本检查`() {
        GatewayHttpServer(GatewayConfig(command = GatewayFixture.command(), port = 0)).use { server ->
            server.start()
            GatewayTestClient(server.port).use { client ->
                assertEquals(400, client.send("POST", "{}").statusCode())
                assertEquals(400, client.send("POST", "[]").statusCode())
                assertEquals(400, client.send("POST", "{invalid}").statusCode())
                assertEquals(400, client.send("GET").statusCode())
                assertEquals(
                    415,
                    client.send("POST", "{}", headers = mapOf("Content-Type" to "text/plain")).statusCode(),
                )
                assertEquals(
                    406,
                    client.send("POST", "{}", headers = mapOf("Accept" to "application/json")).statusCode(),
                )
                assertEquals(
                    400,
                    client.send("POST", "{}", headers = mapOf("MCP-Protocol-Version" to "invalid")).statusCode(),
                )
            }
        }
    }
}
