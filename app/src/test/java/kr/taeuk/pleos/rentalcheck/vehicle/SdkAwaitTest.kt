package kr.taeuk.pleos.rentalcheck.vehicle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SdkAwaitTest {
    @Test fun `duplicate success and failure callbacks cannot resume twice`() = runBlocking {
        val result = sdkAwait<Int> { ok, fail ->
            ok(80)
            ok(0)
            fail(SecurityException("late failure"))
        }
        assertEquals(80, result)
    }

    @Test fun `a callback arriving after cancellation is ignored`() = runBlocking {
        lateinit var lateSuccess: (Int) -> Unit
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            sdkAwait<Int> { ok, _ -> lateSuccess = ok }
        }
        job.cancelAndJoin()
        lateSuccess(80)
        assertTrue(job.isCancelled)
    }

    @Test fun `a silent service times out rather than returning invented data`() = runBlocking {
        try {
            sdkAwait<Int>(timeoutMillis = 30) { _, _ -> }
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            // Expected: no callback must never be converted to a valid reading.
        }
    }

    @Test fun `synchronous SDK exceptions are propagated`() = runBlocking {
        try {
            sdkAwait<Int> { _, _ -> throw SecurityException("permission denied") }
            fail("Expected permission error")
        } catch (error: SecurityException) {
            assertEquals("permission denied", error.message)
        }
    }
}
