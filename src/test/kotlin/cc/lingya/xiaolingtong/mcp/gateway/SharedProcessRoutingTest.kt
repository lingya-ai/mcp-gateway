package cc.lingya.xiaolingtong.mcp.gateway

import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证多客户端共享同一上游时不会混淆请求、进度和取消目标。
 *
 * @author 思追(shaco)
 */
class SharedProcessRoutingTest {

    /** 超时后释放关联，迟到响应不能被广播到其他客户端。 */
    @Test
    fun `请求超时释放路由并丢弃迟到响应`() {
        val config = GatewayConfig(processScope = "shared", requestTimeout = Duration.ofMillis(100))
        val upstream = RecordingUpstream()
        MessageRouter(config, upstream).use { router ->
            router.start()
            val session = BridgeSession(config, router)
            router.attach(session)
            val received = LinkedBlockingQueue<ObjectNode>()
            session.listen().flux().subscribe { received.add(it) }
            router.forward(session, GatewayJson.parse("""{"jsonrpc":"2.0","id":7,"method":"slow"}"""))
            val sent = upstream.next()
            assertEquals(-32000, received.poll(5, TimeUnit.SECONDS).path("error").path("code").asInt())
            assertTrue(!router.hasPending(session))
            upstream.receiver.receive(GatewayJson.result(sent.path("id"), GatewayJson.mapper.createObjectNode()))
            assertTrue(received.isEmpty())
        }
    }

    /** 相同字符串 ID 和进度令牌可并发使用并逆序完成。 */
    @Test
    fun `共享请求及进度严格路由`() {
        val config = GatewayConfig(processScope = "shared")
        val upstream = RecordingUpstream()
        MessageRouter(config, upstream).use { router ->
            router.start()
            val a = BridgeSession(config, router)
            val b = BridgeSession(config, router)
            router.attach(a)
            router.attach(b)
            val first = LinkedBlockingQueue<ObjectNode>()
            val second = LinkedBlockingQueue<ObjectNode>()
            a.listen().flux().subscribe { first.add(it) }
            b.listen().flux().subscribe { second.add(it) }
            router.forward(a, GatewayJson.initialize(config.protocolVersion))
            router.forward(b, GatewayJson.initialize(config.protocolVersion))
            first.clear()
            second.clear()
            val request = GatewayJson.parse(
                """{"jsonrpc":"2.0","id":"same:0","method":"tools/call","params":{"_meta":{"progressToken":"same"}}}""",
            )
            router.forward(a, request)
            router.forward(b, request)
            val sentA = upstream.next()
            val sentB = upstream.next()
            assertNotEquals(sentA.path("id"), sentB.path("id"))
            val progress = GatewayJson.notification("notifications/progress")
            progress.putObject(
                "params",
            ).put("progress", 1).set("progressToken", sentB.path("params").path("_meta").path("progressToken"))
            upstream.receiver.receive(progress)
            assertEquals("same", second.poll(5, TimeUnit.SECONDS).path("params").path("progressToken").asString())
            assertTrue(first.isEmpty())
            upstream.receiver.receive(
                GatewayJson.result(sentB.path("id"), GatewayJson.mapper.createObjectNode().put("owner", "b")),
            )
            upstream.receiver.receive(
                GatewayJson.result(sentA.path("id"), GatewayJson.mapper.createObjectNode().put("owner", "a")),
            )
            assertEquals("a", first.poll(5, TimeUnit.SECONDS).path("result").path("owner").asString())
            assertEquals("same:0", second.poll(5, TimeUnit.SECONDS).path("id").asString())
            assertEquals(1, upstream.initializationCount)
        }
    }

    /** 取消只影响发起客户端，反向请求不能随意交给其他连接。 */
    @Test
    fun `共享取消映射及反向能力限制`() {
        val config = GatewayConfig(processScope = "shared")
        val upstream = RecordingUpstream()
        MessageRouter(config, upstream).use { router ->
            router.start()
            val session = BridgeSession(config, router)
            router.attach(session)
            router.forward(session, GatewayJson.parse("""{"jsonrpc":"2.0","id":0,"method":"tools/call"}"""))
            val sent = upstream.next()
            router.forward(
                session,
                GatewayJson.parse("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":0}}"""),
            )
            assertEquals(sent.path("id"), upstream.next().path("params").path("requestId"))
            upstream.receiver.receive(
                GatewayJson.parse("""{"jsonrpc":"2.0","id":"reverse","method":"sampling/createMessage"}"""),
            )
            assertEquals(-32601, upstream.next().path("error").path("code").asInt())
        }
    }

    /** 隔离模式完整透传服务端反向请求与客户端响应。 */
    @Test
    fun `隔离反向请求双向透传`() {
        val config = GatewayConfig()
        val upstream = RecordingUpstream()
        MessageRouter(config, upstream).use { router ->
            router.start()
            val session = BridgeSession(config, router)
            router.attach(session)
            val received = LinkedBlockingQueue<ObjectNode>()
            session.listen().flux().subscribe { received.add(it) }
            val request = GatewayJson.parse("""{"jsonrpc":"2.0","id":"reverse","method":"roots/list"}""")
            upstream.receiver.receive(request)
            assertEquals(request, received.poll(5, TimeUnit.SECONDS))
            val response = GatewayJson.result(request.path("id"), GatewayJson.mapper.createObjectNode())
            router.forward(session, response)
            assertEquals(response, upstream.next())
        }
    }
}
