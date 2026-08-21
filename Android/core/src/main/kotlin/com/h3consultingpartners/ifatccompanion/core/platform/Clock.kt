package com.h3consultingpartners.ifatccompanion.core.platform

/**
 * Time source. Every engine takes one rather than calling `System.currentTimeMillis()`
 * directly, so timing-sensitive behaviour (readback gates, cache TTLs, chatter
 * cadence, reconnect backoff) is deterministic under test.
 */
fun interface Clock {
    /** Wall-clock time in epoch milliseconds. */
    fun nowMillis(): Long

    companion object {
        val system = Clock { System.currentTimeMillis() }
    }
}

/** A clock the tests drive by hand. */
class MutableClock(private var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }

    fun set(millis: Long) {
        now = millis
    }
}
