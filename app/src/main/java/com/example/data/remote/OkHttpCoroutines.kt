package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Coroutine-cancellable OkHttp execution helper.
 * Unlike blocking `call.execute()`, cancelling the parent coroutine (e.g. navigating away from a
 * screen or hitting `withTimeout`) immediately invokes `Call.cancel()` to abort the underlying
 * socket and release the connection pool slot.
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response) { _, res, _ ->
                    runCatching { res.close() }
                }
            } else {
                runCatching { response.close() }
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    })
}

/**
 * Awaits the OkHttp response with full coroutine cancellation support, then reads/consumes the
 * response body on [Dispatchers.IO] and guarantees the response is closed.
 */
suspend inline fun <T> Call.awaitUse(crossinline block: (Response) -> T): T {
    val response = awaitResponse()
    return withContext(Dispatchers.IO) {
        response.use { block(it) }
    }
}

/**
 * Like [runCatching], but rethrows [kotlinx.coroutines.CancellationException] so coroutine
 * cancellation and `withTimeout`/`withTimeoutOrNull` propagate immediately instead of being
 * swallowed as a regular failure.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce
} catch (t: Throwable) {
    Result.failure(t)
}

