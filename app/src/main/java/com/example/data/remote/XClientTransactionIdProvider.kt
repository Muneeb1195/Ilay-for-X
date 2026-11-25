package com.example.data.remote

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Full `twikit` / `twscrape` (`XClientTransaction`) `x-client-transaction-id` generator:
 * - Fetches `x.com/?mx=2` HTML + `ondemand.s.<hash>.js` chunk once per session (30-min TTL).
 * - Extracts the `twitter-site-verification` base64 key, `ondemand.s` byte-index offsets
 *   (`ROW_INDEX` and `KEY_BYTES_INDICES`), and the four `loading-x-anim-*` SVG `<path>` animations.
 * - Solves the exact cubic-Bézier animation curve + color/rotation interpolation to derive the
 *   real `animationKey` (replacing the previous placeholder salt).
 * - Persists the derived verification key and animation key in SharedPreferences so cold starts
 *   remain 0ms non-blocking, while exposing a fast bounded `forceRefresh()` for HTTP 404 recovery.
 */
class XClientTransactionIdProvider(
    context: Context,
    okHttpClient: OkHttpClient,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fastClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedKeyBytes: ByteArray = loadPersistedKey()
    @Volatile
    private var cachedAnimationKey: String = prefs.getString(KEY_ANIMATION_KEY, null).orEmpty()
    @Volatile
    private var lastKeyFetchEpochMs: Long = prefs.getLong(KEY_FETCHED_AT, 0L)
    @Volatile
    private var lastForceRefreshEpochMs: Long = 0L

    private fun loadPersistedKey(): ByteArray {
        val savedB64 = prefs.getString(KEY_VERIFICATION_B64, null)
        if (!savedB64.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(savedB64, Base64.DEFAULT) }.getOrNull()
            if (decoded != null && decoded.size >= 16) return decoded
        }
        return ByteArray(32).also { secureRandom.nextBytes(it) }
    }

    fun warmUp() {
        refreshInBackgroundIfStale()
    }

    /**
     * Generates an `x-client-transaction-id` immediately in memory (<1ms, zero network blocking).
     */
    fun generate(httpMethod: String, path: String): String {
        refreshInBackgroundIfStale()
        val keyBytes = cachedKeyBytes
        val animKey = cachedAnimationKey
        val timeNow = ((System.currentTimeMillis() / 1000L) - X_EPOCH_OFFSET).coerceAtLeast(1L).toInt()
        val randomByte = (secureRandom.nextInt(254) + 1).toByte()
        return XClientTransactionMath.buildTransactionId(
            httpMethod = httpMethod,
            path = path,
            keyBytes = keyBytes,
            animationKey = animKey,
            timeNowSecondsSinceXEpoch = timeNow,
            randomByte = randomByte,
        )
    }

    /**
     * Forces a single bounded regeneration of the `twitter-site-verification` key and SVG
     * animation key (called on HTTP 404 recovery, like `twscrape` issue #248). Includes a short
     * cooldown so concurrent 404s never trigger a scrape storm.
     */
    suspend fun forceRefresh(timeoutMs: Long = 2_500L): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastForceRefreshEpochMs < FORCE_REFRESH_COOLDOWN_MS && cachedAnimationKey.isNotBlank()) {
            return false
        }
        lastForceRefreshEpochMs = now
        return withTimeoutOrNull(timeoutMs) {
            refreshSessionMaterial(force = true)
        } ?: false
    }

    private fun refreshInBackgroundIfStale() {
        val now = System.currentTimeMillis()
        if (cachedAnimationKey.isNotBlank() && (now - lastKeyFetchEpochMs) < KEY_TTL_MS) return
        backgroundScope.launch {
            runCatching { refreshSessionMaterial(force = false) }
        }
    }

    private suspend fun refreshSessionMaterial(force: Boolean): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val now = System.currentTimeMillis()
            if (!force && cachedAnimationKey.isNotBlank() && (now - lastKeyFetchEpochMs) < KEY_TTL_MS) {
                return false
            }
            lastKeyFetchEpochMs = now

            // 1. Fetch x.com/?mx=2 HTML (cancellable) to extract verification key, SVG anims & ondemand.s URL
            val homeReq = Request.Builder()
                .url("https://x.com/?mx=2")
                .header("User-Agent", XEndpointRegistry.DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val html = runCatching {
                fastClient.newCall(homeReq).awaitUse { resp ->
                    if (!resp.isSuccessful) null else resp.body.string()
                }
            }.getOrNull() ?: return false

            val verificationB64 = XClientTransactionMath.extractVerificationKey(html) ?: return false
            val keyBytes = runCatching { Base64.decode(verificationB64, Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.size >= 16 } ?: return false

            // 2. Locate and fetch the ondemand.s.<hash>.js chunk to extract row/key-byte indices
            val ondemandUrl = XClientTransactionMath.extractOndemandScriptUrl(html)
            val indices = if (!ondemandUrl.isNullOrBlank()) {
                val js = runCatching {
                    val jsReq = Request.Builder()
                        .url(ondemandUrl)
                        .header("User-Agent", XEndpointRegistry.DESKTOP_USER_AGENT)
                        .build()
                    fastClient.newCall(jsReq).awaitUse { resp ->
                        if (!resp.isSuccessful) null else resp.body.string()
                    }
                }.getOrNull()
                if (!js.isNullOrBlank()) XClientTransactionMath.extractOndemandIndices(js) else null
            } else {
                null
            }

            // 3. Parse loading-x-anim-* SVG path frames and compute exact cubic-Bézier animation key
            val frames = XClientTransactionMath.extractSvgFrames(html)
            val derivedAnimKey = if (indices != null && frames.isNotEmpty()) {
                runCatching {
                    XClientTransactionMath.computeAnimationKey(
                        keyBytes = keyBytes,
                        rowIndex = indices.first,
                        keyByteIndices = indices.second,
                        svgFrames = frames,
                    )
                }.getOrNull()
            } else {
                null
            }

            cachedKeyBytes = keyBytes
            prefs.edit {
                putString(KEY_VERIFICATION_B64, verificationB64)
                putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                if (!derivedAnimKey.isNullOrBlank()) {
                    cachedAnimationKey = derivedAnimKey
                    putString(KEY_ANIMATION_KEY, derivedAnimKey)
                }
            }
            return true
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private const val PREFS_NAME = "ilay_tx_prefs"
        private const val KEY_VERIFICATION_B64 = "site_verification_b64"
        private const val KEY_ANIMATION_KEY = "site_animation_key"
        private const val KEY_FETCHED_AT = "site_verification_fetched_at"
        private const val X_EPOCH_OFFSET = 1682924400L
        private const val KEY_TTL_MS = 30 * 60 * 1000L
        private const val FORCE_REFRESH_COOLDOWN_MS = 10_000L
    }
}

/**
 * Pure, deterministic implementation of the `XClientTransaction` (`twikit` / `twscrape`) algorithm:
 * - Parses `twitter-site-verification`, `ondemand.s.<hash>.js`, and `loading-x-anim-*` SVG paths.
 * - Evaluates Apple WebCore `UnitBezier` / Cubic-Bézier easing curves over keyframe rows.
 * - Produces the exact SHA-256 + XOR-obfuscated Base64 `x-client-transaction-id` header.
 */
internal object XClientTransactionMath {

    private const val DEFAULT_KEYWORD = "obfiowerehiring"
    private const val ADDITIONAL_RANDOM_NUMBER: Byte = 3
    private const val TOTAL_TIME_MS = 4096.0

    private val VERIFICATION_META_PATTERNS = listOf(
        Pattern.compile("""name=["']twitter-site-verification["'][^>]*?content=["']([^"']+)["']"""),
        Pattern.compile("""content=["']([^"']+)["'][^>]*?name=["']twitter-site-verification["']"""),
    )

    // Matches both direct script src and webpack chunk map entries: "ondemand.s":"<hash>"
    private val ONDEMAND_SCRIPT_URL_PATTERN = Pattern.compile(
        """(https://abs\.twimg\.com/responsive-web/client-web/ondemand\.s\.[a-fA-F0-9]+a\.js)"""
    )
    private val ONDEMAND_HASH_PATTERNS = listOf(
        Pattern.compile("""["']ondemand\.s["']\s*:\s*["']([a-fA-F0-9]+)["']"""),
        Pattern.compile("""ondemand\.s["']?\s*,\s*["']([a-fA-F0-9]+)["']"""),
    )

    // Matches X's ondemand.s index expressions like `(c[12], 16)`
    private val INDICES_REGEX = Pattern.compile("""\(\w\[(\d{1,2})\],\s*16\)""")

    // Extracts <svg id="loading-x-anim-0"> ... </svg> blocks and their <path d="..."> attributes
    private val LOADING_ANIM_SVG_PATTERN = Pattern.compile(
        """<svg[^>]*id=["']loading-x-anim-(\d+)["'][^>]*>(.*?)</svg>""",
        Pattern.DOTALL,
    )
    private val SVG_PATH_D_PATTERN = Pattern.compile("""<path[^>]*\bd=["']([^"']+)["']""")
    private val NUMBER_PATTERN = Pattern.compile("""-?\d+""")

    fun extractVerificationKey(html: String): String? {
        for (pattern in VERIFICATION_META_PATTERNS) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val found = matcher.group(1)?.trim()
                if (!found.isNullOrBlank()) return found
            }
        }
        return null
    }

    fun extractOndemandScriptUrl(html: String): String? {
        val direct = ONDEMAND_SCRIPT_URL_PATTERN.matcher(html)
        if (direct.find()) {
            return direct.group(1)
        }
        for (pattern in ONDEMAND_HASH_PATTERNS) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val hash = matcher.group(1)?.trim()
                if (!hash.isNullOrBlank()) {
                    return "https://abs.twimg.com/responsive-web/client-web/ondemand.s.${hash}a.js"
                }
            }
        }
        return null
    }

    /**
     * Extracts `(rowIndex, keyByteIndices)` from `ondemand.s.<hash>.js`.
     */
    fun extractOndemandIndices(ondemandJs: String): Pair<Int, List<Int>>? {
        val all = mutableListOf<Int>()
        val matcher = INDICES_REGEX.matcher(ondemandJs)
        while (matcher.find()) {
            matcher.group(1)?.toIntOrNull()?.let { all.add(it) }
        }
        if (all.size < 2) return null
        return all.first() to all.drop(1)
    }

    /**
     * Parses `loading-x-anim-0..3` SVG `<path d="...">` data in numeric ID order.
     * In X's HTML, each `loading-x-anim-*` SVG contains 2 `<path>` elements; the second `<path>`
     * carries the curve keyframe rows (`M...C...` segments split on `C`).
     */
    fun extractSvgFrames(html: String): List<List<List<Int>>> {
        val ordered = sortedMapOf<Int, List<List<Int>>>()
        val svgMatcher = LOADING_ANIM_SVG_PATTERN.matcher(html)
        while (svgMatcher.find()) {
            val animIdx = svgMatcher.group(1)?.toIntOrNull() ?: continue
            val innerSvg = svgMatcher.group(2).orEmpty()
            val paths = mutableListOf<String>()
            val pathMatcher = SVG_PATH_D_PATTERN.matcher(innerSvg)
            while (pathMatcher.find()) {
                pathMatcher.group(1)?.let { paths.add(it) }
            }
            val targetPath = paths.getOrNull(1) ?: paths.firstOrNull() ?: continue
            val rows = parseSvgPathTo2dArray(targetPath)
            if (rows.isNotEmpty()) {
                ordered[animIdx] = rows
            }
        }
        return ordered.values.toList()
    }

    /**
     * Mirrors `get_2d_array`: drops leading 9 chars of the SVG `d` string, splits on `'C'`,
     * and extracts integer sequences for each keyframe row.
     */
    fun parseSvgPathTo2dArray(pathD: String): List<List<Int>> {
        if (pathD.length <= 9) return emptyList()
        return pathD.substring(9)
            .split('C')
            .mapNotNull { segment ->
                val nums = mutableListOf<Int>()
                val m = NUMBER_PATTERN.matcher(segment)
                while (m.find()) {
                    m.group().toIntOrNull()?.let { nums.add(it) }
                }
                nums.takeIf { it.isNotEmpty() }
            }
    }

    /**
     * Computes the exact `animationKey` string from `keyBytes`, `rowIndex`, `keyByteIndices`,
     * and the extracted `loading-x-anim-*` SVG frames.
     */
    fun computeAnimationKey(
        keyBytes: ByteArray,
        rowIndex: Int,
        keyByteIndices: List<Int>,
        svgFrames: List<List<List<Int>>>,
    ): String {
        if (keyBytes.size <= 5 || svgFrames.isEmpty()) return ""
        val frameIndex = (keyBytes[5].toInt() and 0xFF) % 4
        val frameRows = svgFrames.getOrNull(frameIndex) ?: svgFrames.first()
        if (frameRows.isEmpty()) return ""

        val safeRowIndex = rowIndex.coerceIn(0, keyBytes.lastIndex)
        val rowSelector = (keyBytes[safeRowIndex].toInt() and 0xFF) % 16
        val selectedRow = frameRows.getOrNull(rowSelector) ?: frameRows[rowSelector % frameRows.size]
        if (selectedRow.size < 11) return ""

        var frameTime = 1L
        for (idx in keyByteIndices) {
            if (idx in keyBytes.indices) {
                frameTime *= ((keyBytes[idx].toInt() and 0xFF) % 16).toLong()
            }
        }
        // Round frameTime to nearest multiple of 10 (matching JS Math.round(frameTime / 10) * 10)
        val roundedFrameTime = ((frameTime / 10.0).roundToLong() * 10L).toDouble()
        val targetTime = roundedFrameTime / TOTAL_TIME_MS

        return animateRow(selectedRow, targetTime)
    }

    internal fun animateRow(frames: List<Int>, targetTime: Double): String {
        val fromColor = doubleArrayOf(frames[0].toDouble(), frames[1].toDouble(), frames[2].toDouble(), 1.0)
        val toColor = doubleArrayOf(frames[3].toDouble(), frames[4].toDouble(), frames[5].toDouble(), 1.0)
        val fromRotation = doubleArrayOf(0.0)
        val toRotation = doubleArrayOf(solve(frames[6].toDouble(), 60.0, 360.0, true))

        val curves = DoubleArray(4) { i ->
            solve(frames[7 + i].toDouble(), isOdd(i), 1.0, false)
        }
        val cubic = Cubic(curves)
        val easedVal = cubic.getValue(targetTime)

        val color = interpolate(fromColor, toColor, easedVal).map { it.coerceAtLeast(0.0) }
        val rotation = interpolate(fromRotation, toRotation, easedVal)
        val matrix = convertRotationToMatrix(rotation[0])

        val parts = mutableListOf<String>()
        // RGB color channels formatted as lowercase hex (matching X's DOM style serialization)
        for (c in 0 until 3) {
            parts.add(color[c].roundToInt().coerceIn(0, 255).toString(16))
        }
        // Matrix entries converted via floatToHex
        for (v in matrix) {
            val rounded = (v * 100.0).roundToLong() / 100.0
            val hex = floatToHex(abs(rounded))
            parts.add(
                when {
                    hex.startsWith(".") -> "0$hex"
                    hex.isEmpty() -> "0"
                    else -> hex
                }
            )
        }
        parts.add("0")
        parts.add("0")

        return parts.joinToString("")
            .replace(".", "")
            .replace("-", "")
            .lowercase()
    }

    fun buildTransactionId(
        httpMethod: String,
        path: String,
        keyBytes: ByteArray,
        animationKey: String,
        timeNowSecondsSinceXEpoch: Int,
        randomByte: Byte,
    ): String {
        val timeBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(timeNowSecondsSinceXEpoch)
            .array()

        val digestInput = "${httpMethod.uppercase()}!$path!$timeNowSecondsSinceXEpoch$DEFAULT_KEYWORD$animationKey"
        val hashBytes = MessageDigest.getInstance("SHA-256")
            .digest(digestInput.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(keyBytes.size + 4 + 16 + 1)
        System.arraycopy(keyBytes, 0, payload, 0, keyBytes.size)
        System.arraycopy(timeBytes, 0, payload, keyBytes.size, 4)
        System.arraycopy(hashBytes, 0, payload, keyBytes.size + 4, 16)
        payload[payload.lastIndex] = ADDITIONAL_RANDOM_NUMBER

        val obfuscated = ByteArray(payload.size + 1)
        obfuscated[0] = randomByte
        val mask = randomByte.toInt() and 0xFF
        for (i in payload.indices) {
            obfuscated[i + 1] = ((payload[i].toInt() and 0xFF) xor mask).toByte()
        }

        return encodeBase64NoPad(obfuscated)
    }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Pure-Kotlin RFC 4648 standard Base64 encoder without padding (`=`).
     * Runs identically on Android API 24+ and plain JVM unit tests without `java.util.Base64` (API 26).
     */
    internal fun encodeBase64NoPad(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0

            out.append(BASE64_ALPHABET[b0 ushr 2])
            out.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (hasB1) {
                out.append(BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            }
            if (hasB2) {
                out.append(BASE64_ALPHABET[b2 and 0x3F])
            }
            i += 3
        }
        return out.toString()
    }

    internal fun isOdd(num: Int): Double = if (num % 2 != 0) -1.0 else 0.0

    internal fun solve(value: Double, minVal: Double, maxVal: Double, rounding: Boolean): Double {
        val result = value * (maxVal - minVal) / 255.0 + minVal
        return if (rounding) kotlin.math.floor(result) else (result * 100.0).roundToLong() / 100.0
    }

    internal fun interpolate(fromList: DoubleArray, toList: DoubleArray, f: Double): DoubleArray =
        DoubleArray(fromList.size) { i -> fromList[i] * (1.0 - f) + toList[i] * f }

    internal fun convertRotationToMatrix(degrees: Double): DoubleArray {
        val rad = degrees * PI / 180.0
        return doubleArrayOf(cos(rad), -sin(rad), sin(rad), cos(rad))
    }

    internal fun floatToHex(xInput: Double): String {
        val result = StringBuilder()
        var quotient = kotlin.math.floor(xInput).toLong()
        var fraction = xInput - quotient

        while (quotient > 0L) {
            val newQuotient = quotient / 16L
            val remainder = (quotient - newQuotient * 16L).toInt()
            result.insert(0, hexDigit(remainder))
            quotient = newQuotient
        }

        if (fraction == 0.0) return result.toString()
        result.append('.')
        var guard = 0
        while (fraction > 0.0 && guard < 16) {
            fraction *= 16.0
            val integer = kotlin.math.floor(fraction).toInt()
            fraction -= integer
            result.append(hexDigit(integer))
            guard++
        }
        return result.toString()
    }

    private fun hexDigit(n: Int): Char =
        if (n > 9) (n + 55).toChar() else ('0'.code + n).toChar()

    /**
     * Exact port of `XClientTransaction`'s `Cubic` curve solver (mirroring Apple WebCore UnitBezier).
     */
    internal class Cubic(private val curves: DoubleArray) {
        fun getValue(time: Double): Double {
            var startGradient = 0.0
            var endGradient = 0.0
            if (time <= 0.0) {
                if (curves[0] > 0.0) startGradient = curves[1] / curves[0]
                else if (curves[1] == 0.0 && curves[2] > 0.0) startGradient = curves[3] / curves[2]
                return startGradient * time
            }
            if (time >= 1.0) {
                if (curves[2] < 1.0) endGradient = (curves[3] - 1.0) / (curves[2] - 1.0)
                else if (curves[2] == 1.0 && curves[0] < 1.0) endGradient = (curves[1] - 1.0) / (curves[0] - 1.0)
                return 1.0 + endGradient * (time - 1.0)
            }
            var start = 0.0
            var end = 1.0
            var mid = 0.0
            while (start < end) {
                mid = (start + end) / 2.0
                val xEst = calculate(curves[0], curves[2], mid)
                if (abs(time - xEst) < 0.00001) {
                    return calculate(curves[1], curves[3], mid)
                }
                if (xEst < time) start = mid else end = mid
            }
            return calculate(curves[1], curves[3], mid)
        }

        private fun calculate(a: Double, b: Double, m: Double): Double =
            3.0 * a * (1.0 - m) * (1.0 - m) * m + 3.0 * b * (1.0 - m) * m * m + m * m * m
    }
}
