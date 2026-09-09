package cc.lingya.xiaolingtong.mcp.gateway

import java.time.Duration

/**
 * 网关经过校验的启动配置，HTTP 头的键名属于动态协议数据。
 *
 * @author 思追(shaco)
 */
data class GatewayConfig(
    val from: String = "stdio",
    val to: String = "streamable-http",
    val processScope: String = "isolated",
    val httpMode: String = "stateful",
    val command: List<String> = emptyList(),
    val url: String? = null,
    val host: String = "127.0.0.1",
    val port: Int = 8000,
    val httpPath: String = "/mcp",
    val ssePath: String = "/sse",
    val messagePath: String = "/message",
    val wsPath: String = "/message",
    val publicBaseUrl: String = "",
    val upstreamHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val listenToken: String? = null,
    val origins: Set<String> = emptySet(),
    val connectTimeout: Duration = Duration.ofSeconds(30),
    val initializeTimeout: Duration = Duration.ofSeconds(30),
    val requestTimeout: Duration = Duration.ofSeconds(180),
    val idleTimeout: Duration = Duration.ofMinutes(30),
    val shutdownTimeout: Duration = Duration.ofSeconds(5),
    val protocolVersion: String = "2025-11-25",
    val logLevel: String = "info",
    val maxMessageBytes: Int = 4 * 1024 * 1024,
    val maxPending: Int = 256,
    val maxSessions: Int = 64,
    val queueCapacity: Int = 256,
) {

    val shared: Boolean get() = processScope == "shared"
    val stateless: Boolean get() = to == "streamable-http" && httpMode == "stateless"
}
