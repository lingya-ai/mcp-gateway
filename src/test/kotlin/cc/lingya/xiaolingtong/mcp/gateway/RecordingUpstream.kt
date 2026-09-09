package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 可控上游用于验证跨客户端关联和反向请求，不依赖网络时序。
 *
 * @author 思追(shaco)
 */
class RecordingUpstream : UpstreamConnection {

    val sent = LinkedBlockingQueue<ObjectNode>()
    lateinit var receiver: MessageReceiver
    var initializationCount: Int = 0

    /** 保存路由器回调。 */
    override fun start(receiver: MessageReceiver) {
        this.receiver = receiver
    }

    /** 自动响应握手，其他消息交由测试决定完成顺序。 */
    override fun send(message: ObjectNode) {
        if (message.path("method").asString("") == "initialize") {
            initializationCount++
            val result = GatewayJson.mapper.createObjectNode().put("protocolVersion", "2025-11-25")
            result.putObject("capabilities").putObject("tools")
            result.putObject("serverInfo").put("name", "recording").put("version", "1")
            receiver.receive(GatewayJson.result(message.path("id"), result))
        } else if (message.path("method").asString("") != "notifications/initialized") {
            sent.add(message.deepCopy())
        }
    }

    /** 在有限时间内取得下一条上游消息。 */
    fun next(): ObjectNode = checkNotNull(sent.poll(5, TimeUnit.SECONDS)) { "未收到上游消息" }

    /** 测试上游没有需要释放的外部资源。 */
    override fun close() = Unit
}
