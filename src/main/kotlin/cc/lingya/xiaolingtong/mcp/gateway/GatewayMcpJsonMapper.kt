package cc.lingya.xiaolingtong.mcp.gateway

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import tools.jackson.databind.json.JsonMapper

/**
 * 使用 SDK Jackson 适配器解析，保留 JSON-RPC 空结果。
 *
 * @author 思追(shaco)
 */
class GatewayMcpJsonMapper(private val mapper: JsonMapper) : McpJsonMapper by JacksonMcpJsonMapper(mapper) {

    /** SDK 2 要求 result 非 Java null；用 JSON null 节点保留显式空结果。 */
    override fun <T : Any?> convertValue(fromValue: Any?, type: Class<T>): T {
        if (type == McpSchema.JSONRPCResponse::class.java && fromValue is Map<*, *> &&
            fromValue.containsKey("result") && fromValue["result"] == null && !fromValue.containsKey("error")
        ) {
            return type.cast(
                McpSchema.JSONRPCResponse(
                    fromValue["jsonrpc"] as String?, fromValue["id"], mapper.nullNode(), null,
                ),
            )
        }
        return mapper.convertValue(fromValue, type)
    }

    /** 保留 JSON-RPC 响应的 result:null，其他对象沿用 Jackson 配置。 */
    override fun writeValueAsString(value: Any?): String = if (value is McpSchema.JSONRPCMessage) {
        GatewayJson.fromSdk(value).toString()
    } else {
        mapper.writeValueAsString(value)
    }

    /** 字节形式与字符串形式使用一致的消息结构。 */
    override fun writeValueAsBytes(value: Any?): ByteArray = writeValueAsString(value).toByteArray(Charsets.UTF_8)
}
