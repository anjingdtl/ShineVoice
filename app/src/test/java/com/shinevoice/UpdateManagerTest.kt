package com.shinevoice

import com.shinevoice.update.UpdateCheckThrottle
import com.shinevoice.update.UpdateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun successRecordsTimestamp() = runBlocking {
        var stored = 0L
        val throttle = UpdateCheckThrottle(
            readLastCheckAt = { stored },
            writeLastCheckAt = { stored = it },
            nowMs = { 123_456L },
        )

        assertFalse(throttle.shouldSkip(force = false))
        throttle.recordSuccessfulCheck()

        assertEquals(123_456L, stored)
        assertTrue(throttle.shouldSkip(force = false))
    }

    @Test
    fun failureDoesNotRecordAndNextAutomaticCallIsAllowed() = runBlocking {
        var stored = 77L
        var now = stored + UpdateManager.AUTO_CHECK_INTERVAL_MS + 1L
        val throttle = UpdateCheckThrottle(
            readLastCheckAt = { stored },
            writeLastCheckAt = { stored = it },
            nowMs = { now },
        )

        // A failed fetch does not call recordSuccessfulCheck().
        assertFalse(throttle.shouldSkip(force = false))
        assertEquals(77L, stored)

        now += 1L
        assertFalse(throttle.shouldSkip(force = false))
        assertEquals(77L, stored)
    }

    @Test
    fun successfulCheckWithin24HoursSkipsAutomaticFetch() = runBlocking {
        var stored = 1_000L
        val throttle = UpdateCheckThrottle(
            readLastCheckAt = { stored },
            writeLastCheckAt = { stored = it },
            nowMs = { stored + UpdateManager.AUTO_CHECK_INTERVAL_MS - 1L },
        )

        assertTrue(throttle.shouldSkip(force = false))
    }

    @Test
    fun forceAlwaysFetchesRegardlessOfTimestamp() = runBlocking {
        val throttle = UpdateCheckThrottle(
            readLastCheckAt = { Long.MAX_VALUE },
            writeLastCheckAt = {},
            nowMs = { Long.MAX_VALUE },
        )

        assertFalse(throttle.shouldSkip(force = true))
    }
}
