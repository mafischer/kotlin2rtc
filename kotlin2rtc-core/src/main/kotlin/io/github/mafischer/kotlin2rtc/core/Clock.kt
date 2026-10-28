package io.github.mafischer.kotlin2rtc.core

/** A clock in milliseconds; tests pass a fixed or controllable one */
public fun interface Clock {
    /** The current time in milliseconds */
    public fun millis(): Long

    public companion object {
        /** Wall-clock time (epoch milliseconds): WS-Security timestamps, RTCP sender reports */
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }

        /** A monotonic clock (milliseconds from an arbitrary origin): timeouts and windows a wall-clock step must not stretch */
        public val Monotonic: Clock = Clock { java.lang.System.nanoTime() / 1_000_000 }
    }
}
