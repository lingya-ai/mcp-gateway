package cc.lingya.xiaolingtong.mcp.gateway

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 使用官方 SDK 和 JDK WebSocket 客户端验证五条转换路径。
 *
 * @author 思追(shaco)
 */
class GatewayBridgeTest {

    /** SDK 边界必须保留空结果和错误数据，不把业务错误转换为网关错误。 */
    @Test
    fun `SDK消息载荷保持原样`() {
        val messages = listOf(
            """{"jsonrpc":"2.0","id":0,"result":null}""",
            """{"jsonrpc":"2.0","id":"e:0","error":{"code":-123,"message":"错误","data":{"_meta":{"x":1}}}}""",
            """{"jsonrpc":"2.0","id":1,"method":"custom","params":{"_meta":{"x":null},"items":[1,"中文"]}}""",
        )
        messages.forEach { text ->
            val message = GatewayJson.parse(text)
            assertEquals(message, GatewayJson.fromSdk(GatewayJson.toSdk(message)))
        }
    }

    /** SDK 高层客户端独立检验服务端 framing 和握手。 */
    @Test
    fun `SDK客户端通过SSE及HTTP调用工具`() {
        for (transport in listOf("sse", "streamable-http")) {
            for (scope in listOf("isolated", "shared")) {
                val version = if (transport == "sse") "2024-11-05" else "2025-11-25"
                GatewayHttpServer(
                    GatewayConfig(
                        command = GatewayFixture.command(),
                        port = 0,
                        to = transport,
                        processScope = scope,
                        protocolVersion = version,
                    ),
                ).use { server ->
                    server.start()
                    val base = "http://127.0.0.1:${server.port}"
                    val connection = if (transport == "sse") {
                        HttpClientSseClientTransport.builder(base).jsonMapper(GatewayJson.sdkMapper).build()
                    } else {
                        HttpClientStreamableHttpTransport.builder(base).jsonMapper(GatewayJson.sdkMapper).build()
                    }
                    McpClient.sync(connection).requestTimeout(Duration.ofSeconds(15)).build().use { client ->
                        client.initialize()
                        assertEquals("echo", client.listTools().tools().single().name())
                        val response = client.callTool(McpSchema.CallToolRequest("echo", mapOf("text" to "中文")))
                        assertNotNull(response.content().first())
                    }
                }
            }
        }
    }

    /** 远端输入经 SDK 底层传输转回本地消息，版本跟随协商结果。 */
    @Test
    fun `远端SSE及HTTP转本地消息`() {
        for (transport in listOf("sse", "streamable-http")) {
            GatewayHttpServer(
                GatewayConfig(command = GatewayFixture.command(), port = 0, to = transport),
            ).use { server ->
                server.start()
                val endpoint = if (transport == "sse") "/sse" else "/mcp"
                val config =
                    GatewayConfig(from = transport, to = "stdio", url = "http://127.0.0.1:${server.port}$endpoint")
                MessageRouter(config, RemoteConnection(config)).use { router ->
                    val session = BridgeSession(config, router)
                    router.attach(session)
                    router.start()
                    val received = LinkedBlockingQueue<ObjectNode>()
                    session.listen().flux().subscribe { received.add(it) }
                    router.forward(session, GatewayJson.initialize("2025-11-25"))
                    assertEquals(
                        "fixture",
                        assertNotNull(
                            received.poll(15, TimeUnit.SECONDS),
                        ).path("result").path("serverInfo").path("name").asString(),
                    )
                    router.forward(session, GatewayJson.notification("notifications/initialized"))
                    router.forward(
                        session,
                        GatewayJson.parse("""{"jsonrpc":"2.0","id":"list:0","method":"tools/list"}"""),
                    )
                    assertEquals("list:0", assertNotNull(received.poll(15, TimeUnit.SECONDS)).path("id").asString())
                }
            }
        }
    }

    /** WS 的文本帧内使用标准 JSON-RPC，支持字符串 ID。 */
    @Test
    fun `WebSocket文本帧双向桥接`() {
        GatewayHttpServer(GatewayConfig(command = GatewayFixture.command(), port = 0, to = "ws")).use { server ->
            server.start()
            HttpClient.newHttpClient().use { client ->
                val received = LinkedBlockingQueue<String>()
                val listener = object : WebSocket.Listener {

                    private val text = StringBuilder()
                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        text.append(data)
                        if (last) {
                            received.add(text.toString())
                            text.setLength(0)
                        }
                        webSocket.request(1)
                        return null
                    }
                }
                val ws = client.newWebSocketBuilder().buildAsync(
                    URI("ws://127.0.0.1:${server.port}/message"),
                    listener,
                ).get(15, TimeUnit.SECONDS)
                try {
                    ws.sendText(GatewayJson.initialize("2025-11-25").toString(), true).join()
                    val message = GatewayJson.parse(assertNotNull(received.poll(15, TimeUnit.SECONDS)))
                    assertEquals("gateway-initialize", message.path("id").asString())
                } finally {
                    ws.abort()
                }
            }
        }
    }
}
