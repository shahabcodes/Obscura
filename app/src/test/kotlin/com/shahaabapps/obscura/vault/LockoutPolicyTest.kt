package com.shahaabapps.obscura.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class LockoutPolicyTest {

    private class MemoryStorage : LockoutStorage {
        override var failures = 0
        override var lockedUntilElapsed = 0L
        override var bootCount = -1
    }

    private class FakeClock(var now: Long = 1_000_000, var boot: Int = 7) : DeviceClock {
        override fun elapsedRealtime() = now
        override fun bootCount() = boot
    }

    private val storage = MemoryStorage()
    private val clock = FakeClock()
    private val policy = LockoutPolicy(storage, clock)

    @Test
    fun fourFreeAttempts() {
        repeat(4) { policy.recordFailure() }
        assertEquals(0, policy.remainingMs())
        assertEquals(0, policy.freeAttemptsLeft())
    }

    @Test
    fun delaysEscalate() {
        repeat(5) { policy.recordFailure() }
        assertEquals(60_000, policy.remainingMs())
        clock.now += 60_000
        assertEquals(0, policy.remainingMs())
        policy.recordFailure()
        assertEquals(5 * 60_000L, policy.remainingMs())
        policy.recordFailure()
        assertEquals(15 * 60_000L, policy.remainingMs())
        policy.recordFailure()
        policy.recordFailure()
        assertEquals(60 * 60_000L, policy.remainingMs())
    }

    @Test
    fun timerCountsDown() {
        repeat(5) { policy.recordFailure() }
        clock.now += 20_000
        assertEquals(40_000, policy.remainingMs())
    }

    @Test
    fun rebootRestartsTheCurrentDelayInFull() {
        repeat(6) { policy.recordFailure() }
        clock.now += 4 * 60_000
        clock.boot += 1
        clock.now = 5_000 // elapsed time restarts after boot
        assertEquals(5 * 60_000L, policy.remainingMs())
    }

    @Test
    fun successResetsEverything() {
        repeat(6) { policy.recordFailure() }
        policy.recordSuccess()
        assertEquals(0, policy.failures)
        assertEquals(0, policy.remainingMs())
        assertEquals(4, policy.freeAttemptsLeft())
    }
}
