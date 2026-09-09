package cc.lingya.xiaolingtong.mcp.gateway

import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpClientTransport
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 复用 SDK HTTP 客户端传输，显式携带协商版本并禁止业务请求自动重试。
 *
 * @author 思追(shaco)
 */
class RemoteConnection(private val config: GatewayConfig) : UpstreamConnection {

    private val closed = AtomicBoolean()
    private val sends = Semaphore(config.queueCapacity)
    private var transport: McpClientTransport? = null
    private var receiver: MessageReceiver? = null

    @Volatile private var version = config.protocolVersion

    /** 安装底层消息回调；不创建接管握手的高层 MCP 客户端。 */
    override fun start(receiver: MessageReceiver) {
        this.receiver = receiver
        val uri = URI(requireNotNull(config.url))
        val base = "${uri.scheme}://${uri.rawAuthority}"
        val endpoint = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val request = HttpRequest.newBuilder()
        config.upstreamHeaders.forEach { (name, value) -> request.header(name, value) }
        val connection =
            if (config.from == "sse") {
                HttpClientSseClientTransport
                    .builder(base)
                    .sseEndpoint(endpoint)
                    .jsonMapper(GatewayJson.sdkMapper)
                    .clientBuilder(HttpClient.newBuilder().connectTimeout(config.connectTimeout))
                    .connectTimeout(config.connectTimeout)
                    .requestBuilder(request)
                    .build()
            } else {
                HttpClientStreamableHttpTransport
                    .builder(base)
                    .endpoint(endpoint)
                    .jsonMapper(GatewayJson.sdkMapper)
                    .clientBuilder(HttpClient.newBuilder().connectTimeout(config.connectTimeout))
                    .connectTimeout(config.connectTimeout)
                    .requestBuilder(request)
                    .resumableStreams(false)
                    .openConnectionOnStartup(false)
                    .build()
            }
        transport = connection
        connection.setExceptionHandler { if (!closed.get()) receiver.failed(it) }
        connection
            .connect { incoming ->
                incoming.doOnNext { message ->
                    val node = GatewayJson.fromSdk(message)
                    check(node.toString().toByteArray(Charsets.UTF_8).size <= config.maxMessageBytes) { "远端消息超过上限" }
                    val negotiated = node.path("result").path("protocolVersion").asString("")
                    if (negotiated.isNotEmpty()) version = negotiated
                    receiver.receive(node)
                }
            }.contextWrite { it.put(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION, version) }
            .block(config.connectTimeout)
    }

    /** 异步发送以便服务端等待反向响应时仍可继续读取本地输入。 */
    override fun send(message: ObjectNode) {
        check(!closed.get()) { "远端连接已关闭" }
        if (message.path("method").asString("") ==
            "initialize"
        ) {
            version = message.path("params").path("protocolVersion").asString()
        }
        val outgoing = GatewayJson.toSdk(message)
        check(sends.tryAcquire()) { "远端发送队列已满" }
        checkNotNull(transport)
            .sendMessage(outgoing)
            .contextWrite { it.put(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION, version) }
            .timeout(config.requestTimeout)
            .doFinally { sends.release() }
            .subscribe({}, { if (!closed.get()) receiver?.failed(it) })
    }

    /** 向远端终止会话并释放 SDK 传输资源。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            transport
                ?.closeGracefully()
                ?.contextWrite { it.put(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION, version) }
                ?.block(config.shutdownTimeout)
        } catch (error: Exception) {
            if (config.logLevel != "none") System.err.println("[gateway] 远端会话关闭失败")
        }
    }
}
