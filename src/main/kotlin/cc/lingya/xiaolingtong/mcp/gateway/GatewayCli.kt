package cc.lingya.xiaolingtong.mcp.gateway

import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec
import picocli.CommandLine.Model.PositionalParamSpec
import java.net.URI
import java.time.Duration

/**
 * 使用编程式定义解析命令，避免 Native 镜像依赖字段反射。
 *
 * @author 思追(shaco)
 */
object GatewayCli {

    /** 创建支持帮助和版本输出的命令解析器。 */
    fun commandLine(): CommandLine {
        val spec =
            CommandSpec
                .create()
                .name("mcp-gateway")
                .mixinStandardHelpOptions(true)
                .version("mcp-gateway ${GatewayVersion.value}")
        val defaults =
            mapOf(
                "from" to null,
                "to" to null,
                "process-scope" to "isolated",
                "http-mode" to "stateful",
                "url" to null,
                "shell-command" to null,
                "host" to (System.getenv("MCP_GATEWAY_HOST") ?: "127.0.0.1"),
                "port" to "8000",
                "http-path" to "/mcp",
                "sse-path" to "/sse",
                "message-path" to "/message",
                "ws-path" to "/message",
                "public-base-url" to "",
                "upstream-bearer-env" to null,
                "listen-bearer-env" to null,
                "connect-timeout" to "30",
                "initialize-timeout" to "30",
                "request-timeout" to "180",
                "session-timeout" to "1800",
                "shutdown-timeout" to "5",
                "protocol-version" to "2025-11-25",
                "log-level" to "info",
                "max-message-bytes" to "4194304",
                "max-pending" to "256",
                "max-sessions" to "64",
                "queue-capacity" to "256",
            )
        defaults.forEach { (name, default) ->
            spec.addOption(
                OptionSpec
                    .builder("--$name")
                    .type(String::class.java)
                    .defaultValue(default)
                    .build(),
            )
        }
        listOf("upstream-header", "response-header", "allow-origin").forEach { name ->
            spec.addOption(
                OptionSpec
                    .builder("--$name")
                    .type(Array<String>::class.java)
                    .arity("1")
                    .build(),
            )
        }
        spec.addPositional(
            PositionalParamSpec
                .builder()
                .arity("0..*")
                .type(Array<String>::class.java)
                .build(),
        )
        spec.parser().unmatchedOptionsArePositionalParams(false)
        return CommandLine(spec)
    }

    /** 将已解析参数转换为配置；无效组合抛出参数错误。 */
    fun config(parsed: CommandLine.ParseResult): GatewayConfig {
        fun value(name: String): String? = parsed.matchedOptionValue(name, null)
            ?: parsed.commandSpec().findOption(name)?.getValue<String>()

        fun text(name: String): String = requireNotNull(value(name)) { "缺少 --$name" }

        fun choices(name: String, vararg allowed: String): String = text(name).also {
            require(it in allowed) { "--$name 必须为 ${allowed.joinToString()}" }
        }

        fun positive(name: String): Int = text(name).toInt().also { require(it > 0) { "--$name 必须为正数" } }

        fun duration(name: String): Duration = Duration.ofSeconds(positive(name).toLong())

        fun values(name: String): List<String> = parsed.matchedOptionValue<Array<String>>(name, emptyArray()).toList()

        fun headers(name: String): Map<String, String> = values(name).associate { header ->
            val parts = header.split(':', limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "无效的 --$name" }
            require(!parts[1].contains('\r') && !parts[1].contains('\n')) { "HTTP 头禁止换行" }
            parts[0].lowercase() to parts[1].trim()
        }

        fun token(name: String): String? = value(name)?.let {
            requireNotNull(System.getenv(it)?.takeIf(String::isNotBlank)) { "--$name 指定的环境变量为空" }
        }

        fun httpUrl(url: String): String = url.also {
            val uri = URI(it)
            require(
                uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null &&
                    uri.fragment == null,
            ) {
                "URL 必须为无用户信息和片段的 HTTP(S) 地址"
            }
        }
        val from = choices(name = "from", allowed = arrayOf("stdio", "sse", "streamable-http"))
        val to = value("to") ?: if (from == "stdio") "streamable-http" else "stdio"
        require(if (from == "stdio") to in listOf("sse", "ws", "streamable-http") else to == "stdio") { "不支持此转换组合" }
        val scope = choices("process-scope", "isolated", "shared")
        val mode = choices("http-mode", "stateful", "stateless")
        val positional = parsed.matchedPositionalValue<Array<String>>(0, emptyArray()).toList()
        val shell = value("shell-command")
        require(shell == null || positional.isEmpty()) { "--shell-command 与命令参数列表互斥" }
        val command =
            if (shell == null) {
                positional
            } else if (System.getProperty("os.name").startsWith("Windows")) {
                listOf("cmd.exe", "/d", "/s", "/c", shell)
            } else {
                listOf("/bin/sh", "-c", shell)
            }
        require(from != "stdio" || command.isNotEmpty()) { "stdio 输入需要 -- 后的命令或 --shell-command" }
        require(from == "stdio" || (command.isEmpty() && value("url") != null)) { "远端输入需要 --url，不能设置子进程命令" }
        require(from != "stdio" || value("url") == null) { "stdio 输入不能设置 --url" }
        require(from == "stdio" || !parsed.hasMatchedOption("process-scope")) { "远端输入不能设置 --process-scope" }
        require(to == "streamable-http" || !parsed.hasMatchedOption("http-mode")) { "--http-mode 只适用于 HTTP 输出" }
        val port = text("port").toInt().also { require(it in 1..65535) { "端口超出范围" } }
        val paths =
            listOf("http-path", "sse-path", "message-path", "ws-path").associateWith { name ->
                text(name).also {
                    require(it.startsWith('/') && !it.contains('?') && !it.contains('#')) { "端点必须为绝对路径" }
                }
            }
        require(paths["sse-path"] != paths["message-path"]) { "SSE 端点不能与消息端点重合" }
        require(paths.values.none { it == "/healthz" }) { "协议端点不能占用 /healthz" }
        val reserved =
            setOf(
                "content-type",
                "content-length",
                "transfer-encoding",
                "connection",
                "mcp-session-id",
                "mcp-protocol-version",
            )
        val responseHeaders = headers("response-header")
        require(responseHeaders.keys.none { it in reserved || it.startsWith("access-control-") }) { "禁止覆盖协议响应头" }
        val upstreamHeaders = headers("upstream-header").toMutableMap()
        require(upstreamHeaders.keys.none { it in reserved || it == "host" || it == "accept" }) { "禁止覆盖协议请求头" }
        token("upstream-bearer-env")?.let { upstreamHeaders["authorization"] = "Bearer $it" }
        val protocol = text("protocol-version")
        require(protocol in GatewayJson.versions) { "不支持的 MCP 协议版本" }
        val baseUrl = text("public-base-url").let { if (it.isEmpty()) it else httpUrl(it).trimEnd('/') }
        return GatewayConfig(
            from = from,
            to = to,
            processScope = scope,
            httpMode = mode,
            command = command,
            url = value("url")?.let(::httpUrl),
            host = text("host"),
            port = port,
            httpPath = paths.getValue("http-path"),
            ssePath = paths.getValue("sse-path"),
            messagePath = paths.getValue("message-path"),
            wsPath = paths.getValue("ws-path"),
            publicBaseUrl = baseUrl,
            upstreamHeaders = upstreamHeaders,
            responseHeaders = responseHeaders,
            listenToken = token("listen-bearer-env"),
            origins = values("allow-origin").toSet(),
            connectTimeout = duration("connect-timeout"),
            initializeTimeout = duration("initialize-timeout"),
            requestTimeout = duration("request-timeout"),
            idleTimeout = duration("session-timeout"),
            shutdownTimeout = duration("shutdown-timeout"),
            protocolVersion = protocol,
            logLevel = choices(name = "log-level", allowed = arrayOf("debug", "info", "none")),
            maxMessageBytes = positive("max-message-bytes"),
            maxPending = positive("max-pending"),
            maxSessions = positive("max-sessions"),
            queueCapacity = positive("queue-capacity"),
        )
    }
}
