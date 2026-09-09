package cc.lingya.xiaolingtong.mcp.gateway

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 限制单行字节数后解码，避免 UTF-8 跨读取分片损坏或无换行输入耗尽内存。
 *
 * @author 思追(shaco)
 */
object BoundedLines {

    /** 遍历 LF 或 CRLF 分隔的完整行；EOF 时处理最后一行，不关闭输入流。 */
    fun read(input: InputStream, maximum: Int, consumer: (String) -> Unit) {
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(8192)

        fun flush() {
            val bytes = line.toByteArray()
            val size = if (bytes.lastOrNull() == 13.toByte()) bytes.size - 1 else bytes.size
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes, 0, size)).toString()
            line.reset()
            if (text.isNotBlank()) consumer(text)
        }
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                if (buffer[index] == 10.toByte()) {
                    flush()
                } else {
                    check(line.size() < maximum) { "消息超过字节上限" }
                    line.write(buffer[index].toInt())
                }
            }
        }
        if (line.size() > 0) flush()
    }
}
