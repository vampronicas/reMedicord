package com.remedicord.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Single source of "now", replaceable in tests (fake clock). */
open class TimeProvider(private val clock: Clock = Clock.systemDefaultZone()) {
    open fun now(): Instant = clock.instant()
    open fun nowMillis(): Long = now().toEpochMilli()
    open fun zone(): ZoneId = clock.zone
}
