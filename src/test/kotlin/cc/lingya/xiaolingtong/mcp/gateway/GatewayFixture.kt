package cc.lingya.xiaolingtong.mcp.gateway

import java.nio.file.Path

/**
 * 用独立 JVM 模拟真实子进程，测试不需要下载 Node 或 Python 服务。
 *
 * @author 思追(shaco)
 */
object GatewayFixture {

    /** 返回测试进程启动参数，完整保留含空格的 classpath。 */
    fun command(vararg arguments: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-cp",
        System.getProperty("gateway.test.classpath"),
        GatewayFixture::class.java.name,
    ) + arguments

    /** 提供握手、进度和工具操作，stdout 只输出 JSON-RPC。 */
    @JvmStatic
    fun main(args: Array<String>) {
        if ("stderr" in args) System.err.println("测试子进程日志")
        if ("exit" in args) return
        System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val request = GatewayJson.mapper.readTree(line)
            val method = request.path("method").asString("")
            if (!request.has("id")) return@forEachLine
            val result =
                when (method) {
                    "initialize" -> {
                        GatewayJson.mapper.createObjectNode().also {
                            it.put("protocolVersion", request.path("params").path("protocolVersion").asString())
                            it.putObject("capabilities").putObject("tools")
                            it.putObject("serverInfo").put("name", "fixture").put("version", "1")
                        }
                    }

                    "tools/list" -> {
                        GatewayJson.mapper.readTree(
                            """{"tools":[{"name":"echo","description":"echo","inputSchema":{"type":"object"}}]}""",
                        )
                    }

                    "tools/call" -> {
                        GatewayJson.mapper.createObjectNode().also {
                            val token = request.path("params").path("_meta").get("progressToken")
                            if (token != null) {
                                val progress = GatewayJson.notification("notifications/progress")
                                progress.putObject("params").put("progress", 1).set("progressToken", token)
                                println(progress)
                            }
                            it
                                .putArray("content")
                                .addObject()
                                .put("type", "text")
                                .put("text", request.path("params").path("arguments").toString())
                        }
                    }

                    "resources/list" -> {
                        GatewayJson.mapper.readTree("""{"resources":[]}""")
                    }

                    "prompts/list" -> {
                        GatewayJson.mapper.readTree("""{"prompts":[]}""")
                    }

                    "pid" -> {
                        GatewayJson.mapper.createObjectNode().put("pid", ProcessHandle.current().pid())
                    }

                    else -> {
                        request.path("params")
                    }
                }
            println(GatewayJson.result(request.path("id"), result))
            System.out.flush()
        }
    }
}
