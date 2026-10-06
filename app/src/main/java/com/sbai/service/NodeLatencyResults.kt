package com.sbai.service

/** Pure cache matching: equal delays may be fresh, changed delays alone are not proof of a test. */
internal object NodeLatencyResults {
    data class Sample(val delay: Int, val testTime: Long)

    fun freshDelay(before: Sample?, current: Sample?, requestedAtMillis: Long): Int? {
        current ?: return null
        // Missing/failed results are not a measured latency. Do not invent failure on timeout.
        if (current.delay <= 0 || current.testTime <= 0L) return null
        if (before != null && current.testTime <= before.testTime) return null
        // libbox versions expose Unix seconds or milliseconds. Compare at their native precision.
        val requestedAt = if (current.testTime < 100_000_000_000L) {
            requestedAtMillis / 1000L
        } else {
            requestedAtMillis
        }
        return current.delay.takeIf { current.testTime >= requestedAt }
    }
}
