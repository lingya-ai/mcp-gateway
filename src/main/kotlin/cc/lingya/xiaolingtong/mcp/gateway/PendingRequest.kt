package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CompletableFuture

/**
 * 上游唯一请求 ID 对应的原客户端信息，进度令牌与请求同生命周期。
 *
 * @author 思追(shaco)
 */
data class PendingRequest(
    val session: BridgeSession?,
    val originalId: JsonNode,
    val originalProgress: JsonNode?,
    val upstreamProgress: String?,
    val method: String,
    val completion: CompletableFuture<ObjectNode>,
)
