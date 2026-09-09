package cc.lingya.xiaolingtong.mcp.gateway

import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ArrayBlockingQueue

/**
 * 单个客户端流的有界邮箱，发送者串行提交避免并发发射丢消息。
 *
 * @author 思追(shaco)
 */
class MessageMailbox(capacity: Int) {

    private val sink = Sinks.many().unicast().onBackpressureBuffer<ObjectNode>(ArrayBlockingQueue(capacity))

    /** 返回仅允许一个订阅者的输出流。 */
    fun flux(): Flux<ObjectNode> = sink.asFlux()

    /** 提交消息；慢客户端耗尽缓冲后终止该流。 */
    @Synchronized
    fun send(message: ObjectNode) {
        val result = sink.tryEmitNext(message)
        if (result.isFailure) {
            sink.tryEmitError(IllegalStateException("客户端输出队列不可用：$result"))
            error("客户端输出队列不可用")
        }
    }

    /** 正常结束，先发送已经排队的消息。 */
    @Synchronized
    fun complete() {
        sink.tryEmitComplete()
    }

    /** 因传输错误结束输出。 */
    @Synchronized
    fun fail(error: Throwable) {
        sink.tryEmitError(error)
    }
}
