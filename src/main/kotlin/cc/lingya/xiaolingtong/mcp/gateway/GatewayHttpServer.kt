package cc.lingya.xiaolingtong.mcp.gateway

import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import reactor.netty.http.server.WebsocketServerSpec
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提供三种网络输出；所有可能等待进程或握手的操作均切换到虚拟线程。
 *
 * @author 思追(shaco)
 */
class GatewayHttpServer(private val config: GatewayConfig) : AutoCloseable {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val workers = Schedulers.fromExecutorService(executor)
    private val registry = SessionRegistry(config)
    private val closed = AtomicBoolean()
    private var server: DisposableServer? = null
    val failure get() = registry.failure
    val port: Int get() = checkNotNull(server).port()

    /** 启动共享进程和监听端口，端口 0 仅供程序化测试分配空闲端口。 */
    fun start() {
        registry.start()
        server =
            HttpServer
                .create()
                .host(config.host)
                .port(config.port)
                .httpRequestDecoder { it.maxHeaderSize(16384).maxInitialLineLength(8192) }
                .handle { request, response ->
                    Mono
                        .defer { handle(request, response) }
                        .subscribeOn(workers)
                        .onErrorResume { error ->
                            if (response.hasSentHeaders()) {
                                Mono.error(error)
                            } else {
                                respond(response, 500, "网关无法处理该请求")
                            }
                        }
                }.bindNow(config.connectTimeout)
    }

    private fun respond(response: HttpServerResponse, status: Int, message: String): Mono<Void> = response
        .status(status)
        .header("Content-Type", "application/json")
        .sendString(Mono.just(GatewayJson.error(null, -32000, message).toString()))
        .then()

    private fun handle(request: HttpServerRequest, response: HttpServerResponse): Mono<Void> {
        config.responseHeaders.forEach { (key, value) -> response.header(key, value) }
        if (request.uri().substringBefore('?') == "/healthz" && request.method().name() == "GET") {
            return response.header("Content-Type", "text/plain").sendString(Mono.just("ok")).then()
        }
        val origin = request.requestHeaders().get("Origin")
        if (origin != null && !allowedOrigin(origin)) return respond(response, 403, "Origin 不允许")
        if (origin != null && config.origins.isNotEmpty()) {
            response
                .header("Access-Control-Allow-Origin", if ("*" in config.origins) "*" else origin)
                .header("Vary", "Origin")
                .header("Access-Control-Expose-Headers", "Mcp-Session-Id,MCP-Protocol-Version")
        }
        if (request.method().name() == "OPTIONS") {
            return response
                .status(204)
                .header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
                .header(
                    "Access-Control-Allow-Headers",
                    "Content-Type,Accept,Authorization,Mcp-Session-Id,MCP-Protocol-Version",
                )
                .send()
                .then()
        }
        if (config.listenToken != null &&
            request.requestHeaders().get("Authorization") != "Bearer ${config.listenToken}"
        ) {
            response.header("WWW-Authenticate", "Bearer")
            return respond(response, 401, "需要有效的访问令牌")
        }
        val path = request.uri().substringBefore('?')
        return when {
            config.to == "streamable-http" && path == config.httpPath -> streamable(request, response)

            config.to == "sse" && path == config.ssePath && request.method().name() == "GET" -> sse(response)

            config.to == "sse" && path == config.messagePath && request.method().name() == "POST" -> legacyPost(
                request,
                response,
            )

            config.to == "ws" && path == config.wsPath && request.method().name() == "GET" -> websocket(response)

            else -> respond(response, 404, "端点不存在")
        }
    }

    private fun allowedOrigin(origin: String): Boolean {
        if ("*" in config.origins || origin in config.origins) return true
        if (config.origins.isNotEmpty()) return false
        return try {
            val uri = URI(origin)
            uri.scheme in setOf("http", "https") && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        } catch (error: IllegalArgumentException) {
            false
        }
    }

    private fun body(request: HttpServerRequest): Mono<ObjectNode> = request
        .receive()
        .asByteArray()
        .reduceWith({ ByteArrayOutputStream() }) { buffer, bytes ->
            require(buffer.size().toLong() + bytes.size <= config.maxMessageBytes) { "消息超过字节上限" }
            buffer.write(bytes)
            buffer
        }.publishOn(workers)
        .map { GatewayJson.parse(it.toString(Charsets.UTF_8)) }

    private fun jsonPost(
        request: HttpServerRequest,
        response: HttpServerResponse,
        handler: (ObjectNode) -> Mono<Void>,
    ): Mono<Void> {
        if (request
                .requestHeaders()
                .get("Content-Type")
                ?.substringBefore(';')
                ?.trim() != "application/json"
        ) {
            return respond(response, 415, "需要 application/json")
        }
        val length = request.requestHeaders().get("Content-Length")?.toLongOrNull()
        if (length != null && length > config.maxMessageBytes) return respond(response, 413, "消息超过字节上限")
        return body(request).flatMap(handler).onErrorResume(IllegalArgumentException::class.java) {
            if (response.hasSentHeaders()) Mono.error(it) else respond(response, 400, "无效 JSON-RPC 消息")
        }
    }

    private fun streamable(request: HttpServerRequest, response: HttpServerResponse): Mono<Void> {
        val method = request.method().name()
        if (method !in setOf("GET", "POST", "DELETE")) return respond(response, 405, "不支持的 HTTP 方法")
        if (config.stateless && method != "POST") return respond(response, 405, "无状态传输只支持 POST")
        val headerVersion = request.requestHeaders().get("MCP-Protocol-Version")
        if (headerVersion != null && headerVersion !in GatewayJson.versions) return respond(response, 400, "不支持的协议版本")
        val sessionId = request.requestHeaders().get("Mcp-Session-Id")
        val session = sessionId?.let { registry.find(it) }
        if (!config.stateless && sessionId != null && session == null) return respond(response, 404, "会话不存在或已过期")
        if (session != null && headerVersion != null && headerVersion != session.protocolVersion) {
            return respond(response, 400, "协议版本与会话协商结果不一致")
        }
        if (method == "DELETE") {
            if (session == null) return respond(response, 400, "缺少会话 ID")
            registry.remove(session.id)
            return response.status(204).send().then()
        }
        if (method == "GET") {
            if (session == null) return respond(response, 400, "缺少会话 ID")
            if (!accepts(request, "text/event-stream")) return respond(response, 406, "需要接受 text/event-stream")
            if (session.listening.get()) return respond(response, 409, "会话已有监听连接")
            return eventStream(response, session.listen().flux().map { "event: message\ndata: $it\n\n" })
                .doFinally { session.stopListening() }
        }
        if (!accepts(request, "text/event-stream") || !accepts(request, "application/json")) {
            return respond(response, 406, "POST 需要同时接受 application/json 和 text/event-stream")
        }
        return jsonPost(request, response) { message ->
            val initialize = message.path("method").asString("") == "initialize" && message.has("id")
            if (!config.stateless && session == null && !initialize) {
                return@jsonPost respond(response, 400, "首次请求必须 initialize")
            }
            if (session != null && initialize) return@jsonPost respond(response, 400, "会话已初始化")
            if (initialize && message.path("params").path("protocolVersion").asString() !in GatewayJson.versions) {
                return@jsonPost respond(response, 400, "不支持的握手版本")
            }
            if (config.stateless && message.path("method").asString("") == "notifications/initialized") {
                return@jsonPost response.status(202).send().then()
            }
            val current = session ?: registry.create()
            if (!config.stateless) response.header("Mcp-Session-Id", current.id)
            if (!message.has("method") || !message.has("id")) {
                try {
                    current.router.forward(current, message)
                    response.status(202).send().then()
                } finally {
                    if (config.stateless) registry.remove(current.id)
                }
            } else {
                val key = message.path("id").toString()
                val mailbox = current.exchange(message)
                try {
                    current.router.forward(current, message)
                } catch (error: Exception) {
                    current.deliver(GatewayJson.error(message.path("id"), -32000, "上游请求被拒绝"), key)
                }
                eventStream(response, mailbox.flux().map { "event: message\ndata: $it\n\n" }).doFinally {
                    current.finishExchange(key)
                    if (config.stateless) workers.schedule { registry.remove(current.id) }
                }
            }
        }
    }

    private fun accepts(request: HttpServerRequest, type: String): Boolean = request
        .requestHeaders()
        .get("Accept")
        ?.split(',')
        ?.any { it.substringBefore(';').trim() == type } == true

    private fun eventStream(response: HttpServerResponse, messages: Publisher<String>): Mono<Void> = response
        .header("Content-Type", "text/event-stream")
        .header("Cache-Control", "no-cache")
        .header("X-Accel-Buffering", "no")
        .sendString(messages)
        .then()

    private fun sse(response: HttpServerResponse): Mono<Void> {
        val session = registry.create()
        val endpoint = "${config.publicBaseUrl}${config.messagePath}?sessionId=${session.id}"
        val stream =
            session
                .listen()
                .flux()
                .map { "event: message\ndata: $it\n\n" }
                .startWith("event: endpoint\ndata: $endpoint\n\n")
        return eventStream(response, stream).doFinally { workers.schedule { registry.remove(session.id) } }
    }

    private fun legacyPost(request: HttpServerRequest, response: HttpServerResponse): Mono<Void> {
        val query =
            request
                .uri()
                .substringAfter('?', "")
                .split('&')
                .map { it.split('=', limit = 2) }
        val id = query.firstOrNull {
            it.first() == "sessionId"
        }?.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
        val session = id?.let { registry.find(it) } ?: return respond(response, 404, "SSE 会话不存在")
        return jsonPost(request, response) { message ->
            session.router.forward(session, message)
            response.status(202).send().then()
        }
    }

    private fun websocket(response: HttpServerResponse): Mono<Void> {
        val session = registry.create()
        val mailbox = session.listen()
        return response
            .sendWebsocket({ inbound, outbound ->
                val input =
                    inbound
                        .aggregateFrames(config.maxMessageBytes)
                        .receive()
                        .asString(Charsets.UTF_8)
                        .publishOn(workers)
                        .doOnNext {
                            session.router.forward(session, GatewayJson.parse(it))
                        }.then()
                        .doFinally { mailbox.complete() }
                val output = outbound.sendObject(
                    mailbox.flux().map {
                        TextWebSocketFrame(it.toString())
                    },
                ) { true }.then()
                // 升级 HTTP 请求完成不代表 WebSocket 结束，进程跟随消息流回收。
                Mono.`when`(input, output).doFinally { workers.schedule { registry.remove(session.id) } }
            }, WebsocketServerSpec.builder().maxFramePayloadLength(config.maxMessageBytes).build())
            .doOnError { workers.schedule { registry.remove(session.id) } }
    }

    /** 停止监听后回收会话、子进程与执行器。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server?.disposeNow(config.shutdownTimeout)
        registry.close()
        workers.dispose()
        executor.close()
    }
}
