package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.IlayDatabase
import com.example.data.remote.XApiException
import com.example.data.remote.XClientTransactionMath
import com.example.data.remote.XEndpointRegistry
import com.example.data.remote.XErrorClassifier
import com.example.data.remote.XRateLimitTracker
import com.example.data.remote.awaitResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XNetworkingResilienceTest {

    private lateinit var db: IlayDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IlayDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun transactionMath_parsesHtmlOndemandAndSvgCurvesIntoValidXorBase64Id() {
        val sampleHtml = """
            <html>
            <head>
              <meta name="twitter-site-verification" content="AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=" />
              <script>window.__chunks = {"ondemand.s":"7a8b9c0d"};</script>
            </head>
            <body>
              <svg id="loading-x-anim-0">
                <path d="M0 0L10 10" />
                <path d="M 10 20 C 120 80 40 200 150 90 128 64 192 32 224 C 10 20 30 40 50 60 70 80 90 100 110" />
              </svg>
              <svg id="loading-x-anim-1">
                <path d="M0 0L10 10" />
                <path d="M 10 20 C 200 100 50 180 90 210 140 80 120 160 200 C 15 25 35 45 55 65 75 85 95 105 115" />
              </svg>
              <svg id="loading-x-anim-2">
                <path d="M0 0L10 10" />
                <path d="M 10 20 C 50 150 250 10 110 210 90 30 130 230 170" />
              </svg>
              <svg id="loading-x-anim-3">
                <path d="M0 0L10 10" />
                <path d="M 10 20 C 90 40 160 220 70 130 110 50 150 190 210" />
              </svg>
            </body>
            </html>
        """.trimIndent()

        val verificationB64 = XClientTransactionMath.extractVerificationKey(sampleHtml)
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=", verificationB64)

        val ondemandUrl = XClientTransactionMath.extractOndemandScriptUrl(sampleHtml)
        assertEquals(
            "https://abs.twimg.com/responsive-web/client-web/ondemand.s.7a8b9c0da.js",
            ondemandUrl,
        )

        val ondemandJs = "function x(c){return parseInt(c[2], 16) + parseInt(c[7], 16) + parseInt(c[11], 16);}"
        val indices = XClientTransactionMath.extractOndemandIndices(ondemandJs)
        assertNotNull(indices)
        assertEquals(2, indices!!.first)
        assertEquals(listOf(7, 11), indices.second)

        val frames = XClientTransactionMath.extractSvgFrames(sampleHtml)
        assertEquals(4, frames.size)
        assertEquals(2, frames[0].size)

        val keyBytes = Base64.getDecoder().decode(verificationB64)
        val animKey = XClientTransactionMath.computeAnimationKey(
            keyBytes = keyBytes,
            rowIndex = indices.first,
            keyByteIndices = indices.second,
            svgFrames = frames,
        )
        assertTrue("Animation key should be non-empty hex-derived string", animKey.isNotBlank())
        assertFalse("Animation key must not contain dots or dashes", animKey.contains('.') || animKey.contains('-'))

        val method = "GET"
        val path = "/i/api/graphql/c-CzHF1LboFilMpsx4ZCrQ/HomeTimeline"
        val timeNow = 42_000_123
        val randomByte: Byte = 0x5A
        val txId = XClientTransactionMath.buildTransactionId(
            httpMethod = method,
            path = path,
            keyBytes = keyBytes,
            animationKey = animKey,
            timeNowSecondsSinceXEpoch = timeNow,
            randomByte = randomByte,
        )

        // Decode and reverse XOR to verify exact byte layout & SHA-256 digest
        val raw = Base64.getDecoder().decode(txId)
        assertEquals(randomByte, raw[0])
        val mask = randomByte.toInt() and 0xFF
        val decoded = ByteArray(raw.size - 1) { i -> ((raw[i + 1].toInt() and 0xFF) xor mask).toByte() }
        assertEquals(keyBytes.size + 4 + 16 + 1, decoded.size)

        val decodedTime = ByteBuffer.wrap(decoded, keyBytes.size, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(timeNow, decodedTime)
        assertEquals(3.toByte(), decoded.last())

        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest("$method!$path!${timeNow}obfiowerehiring$animKey".toByteArray(Charsets.UTF_8))
        val actualHashPrefix = decoded.copyOfRange(keyBytes.size + 4, keyBytes.size + 20)
        assertTrue(expectedHash.copyOfRange(0, 16).contentEquals(actualHashPrefix))
    }

    @Test
    fun errorClassifier_distinguishesRateLimitsTransientMissingFeaturesAuthAnd404() {
        val nowMs = 1_700_000_000_000L
        val resetSec = (nowMs / 1000L) + 180L
        val headers = Headers.Builder()
            .add("x-rate-limit-remaining", "0")
            .add("x-rate-limit-reset", resetSec.toString())
            .build()

        // 1. HTTP 429 -> RateLimited with exact reset epoch
        val err429 = XErrorClassifier.classifyResponse("HomeTimeline", 429, "{}", headers, nowMs = nowMs)
        assertTrue(err429 is XApiException.RateLimited)
        assertEquals(resetSec * 1000L, (err429 as XApiException.RateLimited).resetAtEpochMs)

        // 2. HTTP 200 GraphQL code 88 -> RateLimited (NOT stale hash)
        val body88 = """{"errors":[{"code":88,"message":"Rate limit exceeded"}],"data":{}}"""
        val err88 = XErrorClassifier.classifyResponse("HomeTimeline", 200, body88, headers, nowMs = nowMs)
        assertTrue(err88 is XApiException.RateLimited)

        // 3. LoadShed / DeadlineExceeded -> TransientServerError
        val bodyLoadShed = """{"errors":[{"name":"LoadShed","message":"Service Over capacity"}],"data":{}}"""
        val errLoadShed = XErrorClassifier.classifyResponse("HomeTimeline", 200, bodyLoadShed, nowMs = nowMs)
        assertTrue(errLoadShed is XApiException.TransientServerError)

        // 4. Code 336 missing feature flags -> MissingFeatureFlags with extracted names
        val body336 = """{"errors":[{"code":336,"message":"The following features cannot be null: responsive_web_foo_enabled, rweb_bar_disabled"}]}"""
        val err336 = XErrorClassifier.classifyResponse("SearchTimeline", 400, body336, nowMs = nowMs)
        assertTrue(err336 is XApiException.MissingFeatureFlags)
        assertEquals(
            listOf("responsive_web_foo_enabled", "rweb_bar_disabled"),
            (err336 as XApiException.MissingFeatureFlags).missingFlags,
        )

        // 5. HTTP 404 -> NotFoundOrInvalidTxId
        val err404 = XErrorClassifier.classifyResponse("HomeTimeline", 404, "", nowMs = nowMs)
        assertTrue(err404 is XApiException.NotFoundOrInvalidTxId)

        // 6. Cloudflare HTML challenge -> AuthOrChallengeError
        val cfHtml = "<!DOCTYPE html><html><head><title>Just a moment...</title></head></html>"
        val errCf = XErrorClassifier.classifyResponse("HomeTimeline", 403, cfHtml, nowMs = nowMs)
        assertTrue(errCf is XApiException.AuthOrChallengeError)

        // 7. Code 326 account lock -> AuthOrChallengeError
        val body326 = """{"errors":[{"code":326,"message":"To protect our users from spam..."}]}"""
        val err326 = XErrorClassifier.classifyResponse("HomeTimeline", 403, body326, nowMs = nowMs)
        assertTrue(err326 is XApiException.AuthOrChallengeError)
        assertEquals(326, err326?.xErrorCode)

        // 8. HTTP 200 with partial errors AND usable timeline instructions -> accepted (null)
        val partialOk = """{"data":{"home":{"home_timeline_urt":{"instructions":[{"type":"TimelineAddEntries","entries":[{"entryId":"tweet-1"}]}]}}},"errors":[{"message":"minor warning"}]}"""
        assertNull(XErrorClassifier.classifyResponse("HomeTimeline", 200, partialOk, nowMs = nowMs))
    }

    @Test
    fun rateLimitTracker_blocksExhaustedBucketAndAutoUnblocksAfterReset() {
        var fakeNow = 1_700_000_000_000L
        val tracker = XRateLimitTracker(clock = { fakeNow })
        val resetSec = (fakeNow / 1000L) + 120L

        tracker.recordResponseHeaders(
            accountId = "acct_1",
            endpointBucket = "HomeTimeline",
            headers = Headers.Builder()
                .add("x-rate-limit-remaining", "0")
                .add("x-rate-limit-reset", resetSec.toString())
                .build(),
        )

        // Exhausted bucket is blocked immediately for acct_1, while acct_2 remains unblocked
        assertEquals(resetSec * 1000L, tracker.getBlockedUntilEpochMs("acct_1", "HomeTimeline"))
        assertNull(tracker.getBlockedUntilEpochMs("acct_2", "HomeTimeline"))

        // Advance clock past reset -> auto-unblocked
        fakeNow = (resetSec * 1000L) + 1_000L
        assertNull(tracker.getBlockedUntilEpochMs("acct_1", "HomeTimeline"))
    }

    @Test
    fun endpointRegistry_patchMissingFeaturesUpdatesMemoryAndRoomWithoutNetwork() = runBlocking {
        val registry = XEndpointRegistry(
            okHttpClient = OkHttpClient(),
            endpointHashDao = db.endpointHashDao(),
        )
        registry.ensureInitialized()

        val patched = registry.patchMissingFeatures(
            operationName = "HomeTimeline",
            missingFlags = listOf("rweb_new_experimental_flag", "feature_disable_legacy_cards"),
        )
        assertTrue(patched)

        val resolved = registry.resolve("HomeTimeline")
        val features = JSONObject(resolved.featuresJson)
        assertTrue(features.has("rweb_new_experimental_flag"))
        assertTrue(features.getBoolean("rweb_new_experimental_flag"))
        assertTrue(features.has("feature_disable_legacy_cards"))
        assertFalse(features.getBoolean("feature_disable_legacy_cards"))

        // Persisted to Room as well
        val persisted = db.endpointHashDao().getAllOnce().first { it.operationName == "HomeTimeline" }
        val persistedFeatures = JSONObject(persisted.featuresJson)
        assertTrue(persistedFeatures.has("rweb_new_experimental_flag"))

        // Re-patching identical flags is a no-op (returns false)
        assertFalse(
            registry.patchMissingFeatures(
                operationName = "HomeTimeline",
                missingFlags = listOf("rweb_new_experimental_flag"),
            )
        )
    }

    @Test
    fun awaitResponse_cancelsUnderlyingOkHttpCallWhenCoroutineIsCancelled() = runBlocking {
        val cancelledFlag = AtomicBoolean(false)
        val fakeCall = object : Call {
            override fun request(): Request = Request.Builder().url("https://x.com/test").build()
            override fun execute(): Response = error("Should not be called synchronously")
            override fun enqueue(responseCallback: Callback) {
                // Intentionally never completes to simulate a slow socket
            }
            override fun cancel() {
                cancelledFlag.set(true)
            }
            override fun isExecuted(): Boolean = true
            override fun isCanceled(): Boolean = cancelledFlag.get()
            override fun timeout(): Timeout = Timeout.NONE
            override fun clone(): Call = this
            override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
            override fun <T> tag(type: Class<out T>): T? = null
            override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
            override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        }

        val job = async { fakeCall.awaitResponse() }
        delay(50L)
        job.cancel()
        delay(50L)
        assertTrue("Cancelling coroutine must cancel underlying OkHttp Call", cancelledFlag.get())
    }
}
