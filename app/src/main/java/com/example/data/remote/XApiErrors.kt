package com.example.data.remote

import okhttp3.Headers
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured X API error hierarchy modeled after twscrape/twikit error classification so
 * rate limits, load-shedding, auth locks, and missing feature flags are never confused with
 * stale GraphQL queryId hashes.
 */
sealed class XApiException(
    message: String,
    val httpCode: Int = 0,
    val xErrorCode: Int? = null,
) : IOException(message) {

    class RateLimited(
        val endpoint: String,
        val resetAtEpochMs: Long,
        httpCode: Int = 429,
        xErrorCode: Int? = 88,
    ) : XApiException(
        message = formatRateLimitMessage(resetAtEpochMs),
        httpCode = httpCode,
        xErrorCode = xErrorCode,
    )

    class TransientServerError(
        val operationName: String,
        httpCode: Int,
        xErrorCode: Int?,
        val detail: String,
    ) : XApiException(
        message = "X is temporarily overloaded ($detail). Please retry shortly.",
        httpCode = httpCode,
        xErrorCode = xErrorCode,
    )

    class MissingFeatureFlags(
        val operationName: String,
        val missingFlags: List<String>,
        httpCode: Int = 400,
    ) : XApiException(
        message = "X requires updated feature flags for $operationName (${missingFlags.size})",
        httpCode = httpCode,
        xErrorCode = 336,
    )

    class NotFoundOrInvalidTxId(
        val operationName: String,
        httpCode: Int = 404,
    ) : XApiException(
        message = "X endpoint $operationName returned 404 (transaction token or route refreshed)",
        httpCode = httpCode,
    )

    class AuthOrChallengeError(
        val operationName: String,
        httpCode: Int,
        xErrorCode: Int?,
        val detail: String,
    ) : XApiException(
        message = detail,
        httpCode = httpCode,
        xErrorCode = xErrorCode,
    )

    class GraphqlError(
        val operationName: String,
        httpCode: Int,
        xErrorCode: Int?,
        val detail: String,
    ) : XApiException(
        message = detail,
        httpCode = httpCode,
        xErrorCode = xErrorCode,
    )

    companion object {
        fun formatRateLimitMessage(resetAtEpochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            val remainingSec = ((resetAtEpochMs - nowMs) / 1000L).coerceAtLeast(1L)
            val mins = remainingSec / 60L
            val secs = remainingSec % 60L
            val window = when {
                mins >= 1L && secs > 0L -> "${mins}m ${secs}s"
                mins >= 1L -> "${mins}m"
                else -> "${secs}s"
            }
            return "X rate limit reached · Resets in $window"
        }
    }
}

/**
 * Classifies HTTP status codes, rate-limit headers, HTML/Cloudflare walls, and X GraphQL JSON
 * error arrays into exact [XApiException] categories.
 */
object XErrorClassifier {

    private val MISSING_FEATURES_REGEX = Regex(
        """The following features cannot be null:\s*([A-Za-z0-9_,\s-]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun parseResetAtEpochMs(headers: Headers?, nowMs: Long = System.currentTimeMillis()): Long {
        val raw = headers?.get("x-rate-limit-reset")?.trim()?.toLongOrNull()
        return if (raw != null && raw > 1_000_000_000L) {
            raw * 1000L
        } else {
            nowMs + DEFAULT_RATE_LIMIT_COOLDOWN_MS
        }
    }

    fun extractMissingFeatureFlags(errorMessage: String): List<String> {
        val match = MISSING_FEATURES_REGEX.find(errorMessage) ?: return emptyList()
        return match.groupValues[1]
            .split(',', ' ', '\n', '\r', '\t')
            .map { it.trim().trimEnd('.', ';', ')', ']') }
            .filter { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
            .distinct()
    }

    fun isHtmlChallenge(body: String): Boolean {
        val trimmed = body.trimStart()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.contains("Just a moment...", ignoreCase = true) ||
            trimmed.contains("cf-browser-verification", ignoreCase = true)
    }

    /**
     * Returns `null` if the response is healthy (2xx with usable data and no fatal error),
     * or a classified [XApiException] describing the exact failure mode.
     */
    fun classifyResponse(
        operationName: String,
        httpCode: Int,
        body: String,
        headers: Headers? = null,
        requireUsableData: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): XApiException? {
        if (httpCode == 429) {
            return XApiException.RateLimited(
                endpoint = operationName,
                resetAtEpochMs = parseResetAtEpochMs(headers, nowMs),
                httpCode = 429,
                xErrorCode = 88,
            )
        }

        if (isHtmlChallenge(body)) {
            return XApiException.AuthOrChallengeError(
                operationName = operationName,
                httpCode = httpCode,
                xErrorCode = null,
                detail = "X returned a security verification page (HTTP $httpCode). Try refreshing later or signing in again.",
            )
        }

        if (httpCode == 401 || httpCode == 403) {
            val parsedCode = parseFirstError(body)?.first
            val detail = when {
                parsedCode == 326 -> "X temporarily locked this session for verification (code 326). Re-authenticate in Account Switcher."
                httpCode == 401 -> "X session expired (HTTP 401). Please sign in again."
                else -> "X denied access to $operationName (HTTP 403)."
            }
            return XApiException.AuthOrChallengeError(
                operationName = operationName,
                httpCode = httpCode,
                xErrorCode = parsedCode,
                detail = detail,
            )
        }

        if (httpCode == 404) {
            return XApiException.NotFoundOrInvalidTxId(operationName = operationName, httpCode = 404)
        }

        if (httpCode in 500..599) {
            return XApiException.TransientServerError(
                operationName = operationName,
                httpCode = httpCode,
                xErrorCode = null,
                detail = "HTTP $httpCode",
            )
        }

        // Inspect JSON error array (applies to HTTP 400, 422, and HTTP 200 GraphQL error envelopes)
        val firstError = parseFirstError(body)
        if (firstError != null) {
            val (errCode, errMsg, errName) = firstError
            val missingFlags = extractMissingFeatureFlags(errMsg)
            if (errCode == 336 || missingFlags.isNotEmpty()) {
                return XApiException.MissingFeatureFlags(
                    operationName = operationName,
                    missingFlags = missingFlags,
                    httpCode = httpCode,
                )
            }
            if (errCode == 88 || errMsg.contains("Rate limit exceeded", ignoreCase = true)) {
                return XApiException.RateLimited(
                    endpoint = operationName,
                    resetAtEpochMs = parseResetAtEpochMs(headers, nowMs),
                    httpCode = httpCode,
                    xErrorCode = 88,
                )
            }
            if (errCode == 326 || errCode == 32 || errCode == 64 || errCode == 239) {
                val msg = if (errCode == 326) {
                    "X requires account verification (code 326). Open X to unlock your session."
                } else {
                    "X session authentication error (code $errCode). Please sign in again."
                }
                return XApiException.AuthOrChallengeError(
                    operationName = operationName,
                    httpCode = httpCode,
                    xErrorCode = errCode,
                    detail = msg,
                )
            }
            val isTransientName = errName.contains("LoadShed", ignoreCase = true) ||
                errName.contains("DeadlineExceeded", ignoreCase = true) ||
                errMsg.contains("LoadShed", ignoreCase = true) ||
                errMsg.contains("DeadlineExceeded", ignoreCase = true) ||
                errMsg.contains("Over capacity", ignoreCase = true) ||
                errCode == 130 || errCode == 131
            if (isTransientName) {
                return XApiException.TransientServerError(
                    operationName = operationName,
                    httpCode = httpCode,
                    xErrorCode = errCode,
                    detail = errName.ifBlank { errMsg.take(60) },
                )
            }

            // If HTTP was 2xx and payload still contains usable GraphQL data alongside partial errors, accept it!
            if (httpCode in 200..299 && (!requireUsableData || !XGraphqlParser.hasGraphqlErrorsWithoutData(body))) {
                return null
            }

            return XApiException.GraphqlError(
                operationName = operationName,
                httpCode = httpCode,
                xErrorCode = errCode,
                detail = errMsg.ifBlank { "X $operationName failed (HTTP $httpCode)" },
            )
        }

        if (httpCode !in 200..299) {
            return XApiException.GraphqlError(
                operationName = operationName,
                httpCode = httpCode,
                xErrorCode = null,
                detail = "X $operationName failed (HTTP $httpCode)",
            )
        }

        if (body.isBlank()) {
            return XApiException.GraphqlError(
                operationName = operationName,
                httpCode = httpCode,
                xErrorCode = null,
                detail = "X $operationName returned an empty response",
            )
        }

        return null
    }

    private fun parseFirstError(rawJson: String): Triple<Int?, String, String>? {
        val trimmed = rawJson.trimStart()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val errors = root.optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        val err = errors.optJSONObject(0) ?: return null
        val code = if (err.has("code")) err.optInt("code").takeIf { it != 0 } else null
        val message = err.optString("message").orEmpty()
        val name = err.optString("name").ifBlank {
            err.optJSONObject("extensions")?.optString("name").orEmpty()
        }
        return Triple(code, message, name)
    }

    private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60_000L
}

/**
 * In-memory per-(account, endpoint) rate-limit tracker modeled after twscrape/Nitter.
 * Reads `x-rate-limit-remaining` and `x-rate-limit-reset` headers on every response and
 * short-circuits exhausted buckets in 0ms until reset instead of burning quota on doomed retries.
 */
class XRateLimitTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class BucketState(
        val remaining: Int,
        val resetAtEpochMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, BucketState>()

    private fun key(accountId: String, endpointBucket: String): String =
        "${accountId.lowercase()}|$endpointBucket"

    /**
     * Returns the reset epoch timestamp if this `(accountId, endpointBucket)` is currently
     * rate-limited, or `null` if requests are permitted.
     */
    fun getBlockedUntilEpochMs(accountId: String, endpointBucket: String): Long? {
        val k = key(accountId, endpointBucket)
        val state = buckets[k] ?: return null
        val now = clock()
        if (state.resetAtEpochMs <= now) {
            buckets.remove(k)
            return null
        }
        return if (state.remaining <= 0) state.resetAtEpochMs else null
    }

    fun recordResponseHeaders(accountId: String, endpointBucket: String, headers: Headers) {
        val remaining = headers["x-rate-limit-remaining"]?.trim()?.toIntOrNull() ?: return
        val resetSec = headers["x-rate-limit-reset"]?.trim()?.toLongOrNull() ?: return
        val resetMs = resetSec * 1000L
        val now = clock()
        val k = key(accountId, endpointBucket)
        if (resetMs <= now) {
            buckets.remove(k)
        } else {
            buckets[k] = BucketState(remaining = remaining, resetAtEpochMs = resetMs)
        }
    }

    fun markExhausted(accountId: String, endpointBucket: String, resetAtEpochMs: Long) {
        val now = clock()
        val safeReset = resetAtEpochMs.coerceAtLeast(now + 15_000L)
        buckets[key(accountId, endpointBucket)] = BucketState(remaining = 0, resetAtEpochMs = safeReset)
    }
}
