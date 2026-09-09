package cc.lingya.xiaolingtong.mcp.gateway

import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit

/**
 * 注册表持有的会话到期事件。
 *
 * @author 思追(shaco)
 */
class SessionDeadline(val sessionId: String, delayNanos: Long) : Delayed {

    private val deadline = System.nanoTime() + delayNanos

    /** 距离会话重新检查还需等待的时间。 */
    override fun getDelay(unit: TimeUnit): Long = unit.convert(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)

    /** 按到期时间排列等待队列。 */
    override fun compareTo(other: Delayed): Int =
        getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
}
