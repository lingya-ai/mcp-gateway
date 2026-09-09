package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关联双向 JSON-RPC 消息，共享时将请求及进度标识限定到发起客户端。
 *
 * @author 思追(shaco)
 */
class MessageRouter(private val config: GatewayConfig, private val upstream: UpstreamConnection) :
    MessageReceiver,
    AutoCloseable {

    private val requests = ConcurrentHashMap<String, PendingRequest>()
    private val progress = ConcurrentHashMap<String, PendingRequest>()
    private val sessions = ConcurrentHashMap<String, BridgeSession>()
    private val closed = AtomicBoolean()
    val failure: CompletableFuture<Throwable> = CompletableFuture()

    @Volatile private var cachedInitialize: JsonNode? = null

    /** 启动底层传输；共享或无状态桥接提前以空客户端能力完成握手。 */
    fun start() {
        upstream.start(this)
        if (config.shared || config.stateless) {
            val response =
                request(null, GatewayJson.initialize(config.protocolVersion))
                    .get(config.initializeTimeout.toMillis(), TimeUnit.MILLISECONDS)
            check(!response.has("error")) { "上游初始化失败" }
            val result = response.path("result")
            check(result.path("protocolVersion").asString() in GatewayJson.versions) { "上游协商版本不受支持" }
            cachedInitialize = result.deepCopy()
            if (config.stateless) {
                (cachedInitialize!!.path("capabilities").path("resources") as? ObjectNode)?.put("subscribe", false)
            }
            upstream.send(GatewayJson.notification("notifications/initialized"))
        }
    }

    /** 注册可接收上游消息的客户端。 */
    fun attach(session: BridgeSession) {
        check(!closed.get()) { "上游已关闭" }
        sessions[session.id] = session
    }

    /** 移除客户端及其待处理请求，不终止其他客户端的请求。 */
    fun detach(session: BridgeSession) {
        sessions.remove(session.id)
        requests.values.filter { it.session === session }.forEach { it.completion.cancel(false) }
    }

    /** HTTP 请求流断开时撤销路由，不把随后到达的响应送到其他流。 */
    fun cancel(session: BridgeSession, requestKey: String) {
        requests.values.filter { it.session === session && it.originalId.toString() == requestKey }
            .forEach { it.completion.cancel(false) }
    }

    /** 查询是否还有该会话的活动请求。 */
    fun hasPending(session: BridgeSession): Boolean = requests.values.any { it.session === session }

    /** 转发客户端消息；通知和响应不创建新的请求关联。 */
    fun forward(session: BridgeSession, message: ObjectNode) {
        check(!closed.get()) { "上游已关闭" }
        session.touch()
        val method = message.path("method").asString("")
        if (!message.has("method")) {
            check(!config.shared && !config.stateless) { "当前模式不支持客户端反向响应" }
            upstream.send(message)
            return
        }
        if (method == "initialize" && message.has("id") && cachedInitialize != null) {
            session.protocolVersion = cachedInitialize!!.path("protocolVersion").asString()
            session.initialized = true
            session.deliver(GatewayJson.result(message.path("id"), cachedInitialize!!), message.path("id").toString())
            return
        }
        if (config.stateless && method in setOf("resources/subscribe", "resources/unsubscribe")) {
            if (message.has("id")) {
                session.deliver(
                    GatewayJson.error(message.get("id"), -32601, "无状态传输不支持持续订阅"),
                    message.path("id").toString(),
                )
            }
            return
        }
        if ((config.shared || config.stateless) && method == "notifications/initialized") return
        if (message.has("id")) {
            request(session, message)
        } else if (method == "notifications/cancelled") {
            val original = message.path("params").path("requestId")
            val entry = requests.entries.firstOrNull { it.value.session === session && it.value.originalId == original }
            if (entry != null) {
                val copy = message.deepCopy()
                (copy.path("params") as ObjectNode).put("requestId", entry.key)
                upstream.send(copy)
                entry.value.completion.cancel(false)
            }
        } else {
            upstream.send(message)
        }
    }

    @Synchronized
    private fun request(session: BridgeSession?, message: ObjectNode): CompletableFuture<ObjectNode> {
        check(requests.size < config.maxPending) { "上游并发请求超过上限" }
        check(requests.values.none { it.session === session && it.originalId == message.path("id") }) { "请求 ID 重复" }
        val upstreamId = UUID.randomUUID().toString()
        val originalProgress = message.path("params").path("_meta").get("progressToken")
        val upstreamProgress = originalProgress?.let { UUID.randomUUID().toString() }
        val completion = CompletableFuture<ObjectNode>()
        val pending =
            PendingRequest(
                session = session,
                originalId = message.path("id"),
                originalProgress = originalProgress,
                upstreamProgress = upstreamProgress,
                method = message.path("method").asString(),
                completion = completion,
            )
        requests[upstreamId] = pending
        if (upstreamProgress != null) progress[upstreamProgress] = pending
        val copy = message.deepCopy().put("id", upstreamId)
        if (upstreamProgress !=
            null
        ) {
            (copy.path("params").path("_meta") as ObjectNode).put("progressToken", upstreamProgress)
        }
        val timeout = if (pending.method == "initialize") config.initializeTimeout else config.requestTimeout
        completion.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete { response, error ->
            requests.remove(upstreamId)
            if (upstreamProgress != null) progress.remove(upstreamProgress)
            if (session != null && !session.closed.get()) {
                val outgoing =
                    if (error == null) {
                        response.deepCopy().also { it.set("id", pending.originalId) }
                    } else {
                        GatewayJson.error(pending.originalId, -32000, "上游请求失败或超时")
                    }
                if (pending.method == "initialize" && !outgoing.has("error")) {
                    session.protocolVersion = outgoing.path("result").path("protocolVersion").asString()
                    session.initialized = true
                }
                try {
                    session.deliver(outgoing, pending.originalId.toString())
                } catch (deliveryError: Exception) {
                    session.close()
                }
            }
        }
        try {
            upstream.send(copy)
        } catch (error: Exception) {
            completion.completeExceptionally(error)
        }
        return completion
    }

    /** 按原请求路由响应和进度；共享状态通知只广播给已初始化客户端。 */
    override fun receive(message: ObjectNode) {
        if (closed.get()) return
        if (!message.has("method")) {
            requests[message.path("id").asString()]?.completion?.complete(message)
            return
        }
        if (message.path("method").asString() == "notifications/progress") {
            val pending = progress[message.path("params").path("progressToken").asString()] ?: return
            val copy = message.deepCopy()
            (copy.path("params") as ObjectNode).set("progressToken", pending.originalProgress)
            try {
                pending.session?.deliver(copy, pending.originalId.toString())
            } catch (error: Exception) {
                pending.session?.close()
            }
            return
        }
        if (message.has("id") && (config.shared || config.stateless)) {
            val response =
                if (message.path("method").asString() == "ping") {
                    GatewayJson.result(message.path("id"), GatewayJson.mapper.createObjectNode())
                } else {
                    GatewayJson.error(message.path("id"), -32601, "当前模式不提供客户端反向调用能力")
                }
            upstream.send(response)
            return
        }
        sessions.values.filter { it.initialized || !config.shared }.forEach { session ->
            try {
                session.deliver(message)
            } catch (error: Exception) {
                session.close()
            }
        }
    }

    /** 终止依赖该上游的全部客户端并报告给网关生命周期。 */
    override fun failed(error: Throwable) {
        if (config.logLevel != "none") System.err.println("[gateway] 上游连接终止：${error.javaClass.simpleName}")
        failure.complete(error)
        close()
    }

    /** 幂等结束所有待处理请求与底层传输。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.values.forEach { it.completion.completeExceptionally(IllegalStateException("上游已关闭")) }
        sessions.values.toList().forEach { it.close() }
        upstream.close()
    }
}
