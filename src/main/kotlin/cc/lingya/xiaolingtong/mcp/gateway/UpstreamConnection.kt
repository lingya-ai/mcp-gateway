package cc.lingya.xiaolingtong.mcp.gateway

import tools.jackson.databind.node.ObjectNode

/**
 * 桥接器对上游传输的最小依赖。
 *
 * @author 思追(shaco)
 */
interface UpstreamConnection : AutoCloseable {

    /** 启动读取并安装唯一接收器。 */
    fun start(receiver: MessageReceiver)

    /** 有界发送；失败时抛出异常，由所属会话结束连接。 */
    fun send(message: ObjectNode)

    /** 终止连接并释放拥有的资源。 */
    override fun close()
}
