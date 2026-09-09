package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 黑盒 HTTP 请求辅助，不使用网关服务端路由实现。
 *
 * @author 思追(shaco)
 */
class GatewayTestClient(private val port: Int) : AutoCloseable {

    private val client = HttpClient.newHttpClient()
    var sessionId: String? = null

    /** 请求协议端点，允许覆盖协议头验证异常契约。 */
    fun send(
        method: String,
        body: String? = null,
        path: String = "/mcp",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        headers.forEach { (key, value) -> builder.setHeader(key, value) }
        val response =
            client.send(
                builder
                    .method(
                        method,
                        if (body ==
                            null
                        ) {
                            HttpRequest.BodyPublishers.noBody()
                        } else {
                            HttpRequest.BodyPublishers.ofString(body)
                        },
                    ).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
        return response
    }

    /** 完成握手并记录会话标识。 */
    fun initialize(): ObjectNode = result(send("POST", GatewayJson.initialize("2025-11-25").toString()))

    /** 读取 SSE 响应中的最终 JSON-RPC 响应。 */
    fun result(response: HttpResponse<String>): ObjectNode {
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: ${response.body()}" }
        return response
            .body()
            .lineSequence()
            .filter { it.startsWith("data: ") }
            .map { GatewayJson.parse(it.removePrefix("data: ")) }
            .last { !it.has("method") }
    }

    /** 释放 HTTP 客户端线程。 */
    override fun close() {
        client.shutdownNow()
    }
}
