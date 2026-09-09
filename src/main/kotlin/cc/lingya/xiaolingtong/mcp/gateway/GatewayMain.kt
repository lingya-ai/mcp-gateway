package cc.lingya.xiaolingtong.mcp.gateway

import picocli.CommandLine
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/**
 * 原生和 JVM 共用的命令行入口，stdout 保留给 MCP 协议。
 *
 * @author 思追(shaco)
 */
object GatewayMain {

    /** 执行网关并返回约定的进程退出码。 */
    @JvmStatic
    fun main(args: Array<String>) {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
        val cli = GatewayCli.commandLine()
        val config =
            try {
                val parsed = cli.parseArgs(*args)
                if (CommandLine.printHelpIfRequested(parsed)) return
                GatewayCli.config(parsed)
            } catch (error: Exception) {
                System.err.println("参数错误：${error.message}")
                exitProcess(2)
            }
        System.setProperty(
            "org.slf4j.simpleLogger.defaultLogLevel",
            if (config.logLevel ==
                "none"
            ) {
                "off"
            } else {
                config.logLevel
            },
        )
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
        System.setProperty("io.netty.transport.noNative", "true")
        System.setProperty("io.netty.handler.ssl.noOpenSsl", "true")
        try {
            if (config.from == "stdio") serve(config) else connect(config)
        } catch (error: Exception) {
            if (config.logLevel != "none") System.err.println("[gateway] 运行失败：${error.javaClass.simpleName}")
            exitProcess(1)
        }
    }

    private fun serve(config: GatewayConfig) {
        GatewayHttpServer(config).use { server ->
            val shutdown = Thread { server.close() }
            Runtime.getRuntime().addShutdownHook(shutdown)
            try {
                server.start()
                if (config.logLevel !=
                    "none"
                ) {
                    System.err.println("[gateway] ${config.to} 监听 ${config.host}:${server.port}")
                }
                throw server.failure.get()
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown)
                } catch (error: IllegalStateException) {
                    // JVM 已进入关闭阶段，已注册的钩子负责完成清理。
                }
            }
        }
    }

    private fun connect(config: GatewayConfig) {
        val router = MessageRouter(config, RemoteConnection(config))
        val session = BridgeSession(config, router)
        val ended = CompletableFuture<Void>()
        val shutdown = Thread { session.close() }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            router.attach(session)
            router.start()
            router.failure.thenAccept { ended.completeExceptionally(it) }
            session.listen().flux().subscribe({
                System.out.println(it.toString())
                System.out.flush()
            }, { ended.completeExceptionally(it) })
            Thread.ofVirtual().name("mcp-local-input").start {
                try {
                    BoundedLines.read(System.`in`, config.maxMessageBytes) {
                        router.forward(session, GatewayJson.parse(it))
                    }
                    ended.complete(null)
                } catch (error: Exception) {
                    ended.completeExceptionally(error)
                }
            }
            ended.get()
        } finally {
            session.close()
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown)
            } catch (error: IllegalStateException) {
                // JVM 已进入关闭阶段，已注册的钩子负责完成清理。
            }
        }
    }
}
