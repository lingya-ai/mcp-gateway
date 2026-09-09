package cc.lingya.xiaolingtong.mcp.gateway

import io.modelcontextprotocol.spec.McpSchema
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * 协议消息的结构校验与 SDK 适配，业务载荷保留为 JSON 树。
 *
 * @author 思追(shaco)
 */
object GatewayJson {

    val mapper: JsonMapper = JsonMapper.builder().build()
    val sdkMapper: GatewayMcpJsonMapper = GatewayMcpJsonMapper(mapper)
    val versions: Set<String> = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

    /** 解析单条 MCP 消息，拒绝批量、无效 ID 和不完整响应。 */
    fun parse(text: String): ObjectNode {
        val node = try {
            mapper.readTree(text)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("无效 JSON", error)
        }
        require(node is ObjectNode && node.path("jsonrpc").asString() == "2.0") { "无效 JSON-RPC 消息" }
        val id = node.get("id")
        require(id == null || id.isString || id.isIntegralNumber || (id.isNull && node.has("error"))) { "无效请求 ID" }
        if (node.has("method")) {
            require(node.path("method").isString && !node.has("result") && !node.has("error")) { "无效请求" }
            require(id == null || !id.isNull) { "请求 ID 不能为 null" }
            require(!node.has("params") || node.path("params").isObject) { "MCP 参数必须为对象" }
        } else {
            require(id != null && node.has("result") != node.has("error")) { "无效响应" }
            if (node.has("error")) {
                require(
                    node.path("error").path("code").isIntegralNumber && node.path("error").path("message").isString,
                ) {
                    "无效错误响应"
                }
            }
        }
        return node
    }

    /** 使用 SDK 的标准消息类型传入客户端 Transport。 */
    fun toSdk(node: ObjectNode): McpSchema.JSONRPCMessage =
        McpSchema.deserializeJsonRpcMessage(sdkMapper, node.toString())

    /** 恢复 SDK 消息为可保留任意业务载荷的树。 */
    fun fromSdk(message: McpSchema.JSONRPCMessage): ObjectNode {
        if (message is McpSchema.JSONRPCResponse) {
            val response = mapper.createObjectNode().put("jsonrpc", "2.0")
            response.set("id", mapper.valueToTree(message.id()))
            // SDK 默认的 NON_ABSENT 会省略合法的 result:null，桥接时必须保留结果分支。
            if (message.error() == null) {
                response.set("result", mapper.valueToTree(message.result()))
            } else {
                response.set("error", mapper.valueToTree(message.error()))
            }
            return response
        }
        return parse(mapper.writeValueAsString(message))
    }

    /** 创建协议通知。 */
    fun notification(method: String): ObjectNode = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method)

    /** 创建指定请求的错误响应。 */
    fun error(id: JsonNode?, code: Int, message: String): ObjectNode {
        val result = mapper.createObjectNode().put("jsonrpc", "2.0")
        result.set("id", id ?: mapper.nullNode())
        result.putObject("error").put("code", code).put("message", message)
        return result
    }

    /** 创建指定请求的成功响应。 */
    fun result(id: JsonNode, payload: JsonNode): ObjectNode = mapper.createObjectNode().put("jsonrpc", "2.0").also {
        it.set("id", id)
        it.set("result", payload)
    }

    /** 为无状态请求或共享进程创建独立的握手请求。 */
    fun initialize(version: String): ObjectNode = notification("initialize").put("id", "gateway-initialize").also {
        it.putObject("params").put("protocolVersion", version).also { params ->
            params.putObject("capabilities")
            params.putObject("clientInfo").put("name", "mcp-gateway").put("version", GatewayVersion.value)
        }
    }
}
