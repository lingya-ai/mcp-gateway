package cc.lingya.xiaolingtong.mcp.gateway

/**
 * 从构建资源读取发布版本，CLI 和主动握手使用同一版本标识。
 *
 * @author 思追(shaco)
 */
object GatewayVersion {

    val value: String = checkNotNull(GatewayVersion::class.java.getResourceAsStream("/gateway-version.txt")) {
        "构建产物缺少版本资源"
    }.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
}
