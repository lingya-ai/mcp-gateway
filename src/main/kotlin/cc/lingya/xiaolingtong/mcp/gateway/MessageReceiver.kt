package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.node.ObjectNode

/**
 * 将上游消息和终止事件交给桥接器。
 *
 * @author 思追(shaco)
 */
interface MessageReceiver {

    /** 接收一条完整的 JSON-RPC 消息。 */
    fun receive(message: ObjectNode)

    /** 报告不可恢复的传输失败。 */
    fun failed(error: Throwable)
}
