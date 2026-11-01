package kr.taeuk.pleos.rentalcheck.vehicle

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Drop duplicate and late binder callbacks, and bound calls that never finish. */
internal suspend fun <T> sdkAwait(
    timeoutMillis: Long = 4_000,
    request: ((T) -> Unit, (Exception) -> Unit) -> Unit,
): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        continuation.invokeOnCancellation { finished.set(true) }
        val complete: (T) -> Unit = { value ->
            if (finished.compareAndSet(false, true)) continuation.resume(value)
        }
        val fail: (Exception) -> Unit = { error ->
            if (finished.compareAndSet(false, true)) continuation.resumeWithException(error)
        }
        try {
            request(complete, fail)
        } catch (error: Exception) {
            fail(error)
        }
    }
}
