package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.MediaAttachment
import org.json.JSONArray

/**
 * Runtime network bandwidth tier detected via Android [ConnectivityManager].
 */
enum class NetworkTier(val label: String) {
    UNMETERED_FAST("Wi-Fi"),
    UNMETERED_CONSTRAINED("Slow Wi-Fi"),
    METERED_CELLULAR("Cellular"),
    DATA_SAVER_RESTRICTED("Data Saver"),
}

/**
 * Rendering slot where an image will be displayed.
 * Timeline slots ALWAYS select lower-resolution previews to minimize bandwidth;
 * only [EXPANDED_LIGHTBOX] upgrades on tap (capped at 1080p max in Data Saver).
 */
enum class ImageUsage {
    TIMELINE_CARD,
    COMPACT_THUMBNAIL,
    AVATAR,
    PROFILE_BANNER,
    EXPANDED_LIGHTBOX,
}

/**
 * Resolved CDN URL + Coil decode target for a specific image slot and network condition.
 */
data class SelectedImageVariant(
    val url: String,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val variantLabel: String,
    val isFullResolution: Boolean,
    val allowRgb565: Boolean,
)

/**
 * CompositionLocal propagating the user's [ImageQualityMode] cleanly across timeline cards,
 * embedded quote previews, link/article previews, profile banners, and the fullscreen lightbox.
 */
val LocalImageQualityMode = staticCompositionLocalOf { ImageQualityMode.AUTO }

/**
 * Adaptive media quality selector:
 * - Caches [ConnectivityManager] network-tier probes for 5s to avoid per-card main-thread Binder IPC.
 * - Uses verified X CDN profile banner suffixes (`600x200` and `1500x500` — never invalid `1080x360`).
 * - Caps expanded lightbox photos and video playback at **1080p max** when Data Saver is active,
 *   while allowing explicit on-demand upgrade via `forceOriginal = true`.
 */
object AdaptiveImageQualitySelector {

    private const val CONSTRAINED_WIFI_KBPS_THRESHOLD = 5_000
    private const val NETWORK_TIER_CACHE_TTL_MS = 5_000L
    private const val DATA_SAVER_VIDEO_MAX_LONG_EDGE = 1_920
    private const val DATA_SAVER_VIDEO_MAX_SHORT_EDGE = 1_080
    private const val DATA_SAVER_VIDEO_FALLBACK_MAX_BITRATE = 2_500_000

    private val MEDIA_NAME_PARAM_REGEX = Regex("([?&])name=[a-zA-Z0-9_]+")
    private val AVATAR_SUFFIX_REGEX = Regex("_(normal|bigger|mini|200x200|400x400)(\\.[a-zA-Z0-9]+)(\\?.*)?$")
    private val BANNER_SUFFIX_REGEX = Regex("/(300x100|600x200|1080x360|1500x500|web|web_retina|mobile|mobile_retina|ipad|ipad_retina)$")
    private val VIDEO_DIMENSIONS_REGEX = Regex("/(\\d{2,4})x(\\d{2,4})/")

    /**
     * Mirror of the user's persisted [ImageQualityMode] for non-Compose callers
     * (e.g. [IlayImageCache.prefetchTimelineMedia] and background prefetch workers).
     */
    @Volatile
    var currentMode: ImageQualityMode = ImageQualityMode.AUTO

    @Volatile
    private var cachedNetworkTier: NetworkTier? = null

    @Volatile
    private var cachedNetworkTierAtMs: Long = 0L

    /**
     * Detects the active device network tier using [ConnectivityManager] and Android's
     * system Data Saver setting (`restrictBackgroundStatus`), cached for 5 seconds to prevent
     * redundant main-thread Binder IPC calls while scrolling timelines.
     */
    fun detectNetworkTier(context: Context, forceRefresh: Boolean = false): NetworkTier {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cachedNetworkTier
            if (cached != null && (now - cachedNetworkTierAtMs) in 0L until NETWORK_TIER_CACHE_TTL_MS) {
                return cached
            }
        }
        val detected = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching NetworkTier.UNMETERED_FAST

            if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                return@runCatching NetworkTier.DATA_SAVER_RESTRICTED
            }

            val activeNetwork = cm.activeNetwork ?: return@runCatching NetworkTier.UNMETERED_FAST
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching NetworkTier.UNMETERED_FAST

            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                cm.isActiveNetworkMetered ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            if (isMetered) {
                return@runCatching NetworkTier.METERED_CELLULAR
            }

            val downstreamKbps = caps.linkDownstreamBandwidthKbps
            if (downstreamKbps in 1 until CONSTRAINED_WIFI_KBPS_THRESHOLD) {
                NetworkTier.UNMETERED_CONSTRAINED
            } else {
                NetworkTier.UNMETERED_FAST
            }
        }.getOrDefault(NetworkTier.UNMETERED_FAST)

        cachedNetworkTier = detected
        cachedNetworkTierAtMs = now
        return detected
    }

    /**
     * Selects the optimal CDN URL variant and decode dimensions for [rawUrl] given [usage],
     * user [mode], and [networkTier].
     */
    fun selectVariant(
        rawUrl: String,
        usage: ImageUsage,
        mode: ImageQualityMode = currentMode,
        networkTier: NetworkTier = NetworkTier.UNMETERED_FAST,
        forceOriginal: Boolean = false,
    ): SelectedImageVariant {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return SelectedImageVariant(
                url = "",
                targetWidthPx = 0,
                targetHeightPx = 0,
                variantLabel = "",
                isFullResolution = false,
                allowRgb565 = true,
            )
        }

        val isDataSaver = mode == ImageQualityMode.DATA_SAVER ||
            networkTier == NetworkTier.DATA_SAVER_RESTRICTED
        val constrainedNetwork = networkTier == NetworkTier.METERED_CELLULAR ||
            networkTier == NetworkTier.DATA_SAVER_RESTRICTED ||
            networkTier == NetworkTier.UNMETERED_CONSTRAINED

        return when (usage) {
            ImageUsage.COMPACT_THUMBNAIL -> {
                val rewritten = rewriteTwimgMediaVariant(trimmed, "360x360")
                SelectedImageVariant(
                    url = rewritten,
                    targetWidthPx = 360,
                    targetHeightPx = 360,
                    variantLabel = "360px",
                    isFullResolution = false,
                    allowRgb565 = true,
                )
            }

            ImageUsage.TIMELINE_CARD -> {
                val (sizeName, widthPx, heightPx, label) = when (mode) {
                    ImageQualityMode.DATA_SAVER -> Quad("360x360", 360, 203, "360px")
                    ImageQualityMode.AUTO -> {
                        if (constrainedNetwork) {
                            Quad("360x360", 360, 203, "360px")
                        } else {
                            Quad("small", 680, 383, "680px")
                        }
                    }
                    ImageQualityMode.HIGH -> {
                        if (constrainedNetwork) {
                            Quad("small", 680, 383, "680px")
                        } else {
                            Quad("medium", 960, 540, "960px")
                        }
                    }
                }
                val rewritten = rewriteTwimgMediaVariant(trimmed, sizeName)
                SelectedImageVariant(
                    url = rewritten,
                    targetWidthPx = widthPx,
                    targetHeightPx = heightPx,
                    variantLabel = label,
                    isFullResolution = false,
                    allowRgb565 = true,
                )
            }

            ImageUsage.AVATAR -> {
                val useCompactAvatar = isDataSaver || networkTier == NetworkTier.METERED_CELLULAR
                val suffix = if (useCompactAvatar) "_bigger" else "_200x200"
                val px = if (useCompactAvatar) 96 else 160
                val rewritten = rewriteTwimgAvatarVariant(trimmed, suffix)
                SelectedImageVariant(
                    url = rewritten,
                    targetWidthPx = px,
                    targetHeightPx = px,
                    variantLabel = "${px}px",
                    isFullResolution = false,
                    allowRgb565 = false,
                )
            }

            ImageUsage.PROFILE_BANNER -> {
                // Only use X CDN verified banner sizes (`600x200` and `1500x500`) — `1080x360` 404s on pbs.twimg.com!
                val useCompactBanner = isDataSaver || constrainedNetwork
                val suffix = if (useCompactBanner) "600x200" else "1500x500"
                val (decodeW, decodeH) = when {
                    useCompactBanner -> 600 to 200
                    mode == ImageQualityMode.HIGH -> 1500 to 500
                    else -> 1080 to 360 // Valid 1500x500 CDN URL decoded efficiently for Adaptive Wi-Fi
                }
                val rewritten = rewriteTwimgBannerVariant(trimmed, suffix)
                SelectedImageVariant(
                    url = rewritten,
                    targetWidthPx = decodeW,
                    targetHeightPx = decodeH,
                    variantLabel = suffix,
                    isFullResolution = false,
                    allowRgb565 = true,
                )
            }

            ImageUsage.EXPANDED_LIGHTBOX -> {
                // Data Saver caps expanded photos at 1080p max (`medium` / <= 1200px, decode <= 1920x1080)
                // unless the user explicitly taps the quality pill (`forceOriginal = true`).
                if (!forceOriginal && isDataSaver) {
                    val rewritten = rewriteTwimgMediaVariant(trimmed, "medium")
                    return SelectedImageVariant(
                        url = rewritten,
                        targetWidthPx = DATA_SAVER_VIDEO_MAX_LONG_EDGE,
                        targetHeightPx = DATA_SAVER_VIDEO_MAX_SHORT_EDGE,
                        variantLabel = "1080p",
                        isFullResolution = false,
                        allowRgb565 = false,
                    )
                }

                val useOriginal = forceOriginal ||
                    mode == ImageQualityMode.HIGH ||
                    (mode == ImageQualityMode.AUTO && networkTier == NetworkTier.UNMETERED_FAST)
                val sizeName = if (useOriginal) "orig" else "large"
                val label = if (useOriginal) "ORIG" else "HD"
                val rewritten = rewriteTwimgMediaVariant(trimmed, sizeName)
                SelectedImageVariant(
                    url = rewritten,
                    targetWidthPx = 0, // Natural dimensions decoded in lightbox
                    targetHeightPx = 0,
                    variantLabel = label,
                    isFullResolution = true,
                    allowRgb565 = false,
                )
            }
        }
    }

    /**
     * Selects the playback video URL for [media], capping at the `<= 1080p` stream when Data Saver
     * is active unless the user explicitly opted into the max stream (`forceOriginal = true`).
     * Returns `(playbackUrl, isCappedAt1080p)`.
     */
    fun selectVideoUrl(
        media: MediaAttachment,
        mode: ImageQualityMode = currentMode,
        networkTier: NetworkTier = NetworkTier.UNMETERED_FAST,
        forceOriginal: Boolean = false,
    ): Pair<String, Boolean> {
        val fullUrl = media.videoUrl.orEmpty()
        val saverUrl = media.dataSaverVideoUrl?.takeIf { it.isNotBlank() }
        val isDataSaver = mode == ImageQualityMode.DATA_SAVER ||
            networkTier == NetworkTier.DATA_SAVER_RESTRICTED
        if (!forceOriginal && isDataSaver && saverUrl != null) {
            return saverUrl to (saverUrl != fullUrl && fullUrl.isNotBlank())
        }
        return fullUrl to false
    }

    /**
     * Extracts `(bestMp4Url, dataSaverAtMost1080pMp4Url)` from an X `video_info.variants` JSON array.
     * Shared by both GraphQL and public fallback parsers.
     */
    fun pickVideoVariants(variants: JSONArray?): Pair<String?, String?> {
        if (variants == null || variants.length() == 0) return null to null
        var bestMp4: String? = null
        var bestBitrate = -1
        var best1080pMp4: String? = null
        var best1080pBitrate = -1
        var lowestMp4: String? = null
        var lowestBitrate = Int.MAX_VALUE

        for (v in 0 until variants.length()) {
            val vObj = variants.optJSONObject(v) ?: continue
            if (vObj.optString("content_type") != "video/mp4") continue
            val url = vObj.optString("url").trim()
            if (url.isBlank()) continue
            val bitrate = vObj.optInt("bitrate", 0)

            if (bitrate >= bestBitrate) {
                bestBitrate = bitrate
                bestMp4 = url
            }
            if (bitrate < lowestBitrate) {
                lowestBitrate = bitrate
                lowestMp4 = url
            }

            if (isAtMost1080pVideoVariant(url, bitrate) && bitrate >= best1080pBitrate) {
                best1080pBitrate = bitrate
                best1080pMp4 = url
            }
        }
        return bestMp4 to (best1080pMp4 ?: lowestMp4 ?: bestMp4)
    }

    internal fun isAtMost1080pVideoVariant(url: String, bitrate: Int): Boolean {
        val match = VIDEO_DIMENSIONS_REGEX.find(url)
        if (match != null) {
            val w = match.groupValues[1].toIntOrNull() ?: 0
            val h = match.groupValues[2].toIntOrNull() ?: 0
            if (w > 0 && h > 0) {
                val longEdge = maxOf(w, h)
                val shortEdge = minOf(w, h)
                return longEdge <= DATA_SAVER_VIDEO_MAX_LONG_EDGE &&
                    shortEdge <= DATA_SAVER_VIDEO_MAX_SHORT_EDGE
            }
        }
        return bitrate <= 0 || bitrate <= DATA_SAVER_VIDEO_FALLBACK_MAX_BITRATE
    }

    /**
     * Convenience helper returning the exact preview URL that timeline cards cache for [rawUrl],
     * allowing the fullscreen lightbox to display the already-cached lower-res preview on frame 0
     * before the higher-resolution image finishes downloading.
     */
    fun previewUrlFor(
        rawUrl: String,
        compact: Boolean = false,
        mode: ImageQualityMode = currentMode,
        networkTier: NetworkTier = NetworkTier.UNMETERED_FAST,
    ): String {
        val usage = if (compact) ImageUsage.COMPACT_THUMBNAIL else ImageUsage.TIMELINE_CARD
        return selectVariant(rawUrl, usage = usage, mode = mode, networkTier = networkTier).url
    }

    /**
     * Human-readable live summary for Settings so users can see the exact preview & expand tiers
     * active on their current connection.
     */
    fun describeActivePolicy(mode: ImageQualityMode, networkTier: NetworkTier): String {
        val timeline = selectVariant(
            rawUrl = "https://pbs.twimg.com/media/sample.jpg",
            usage = ImageUsage.TIMELINE_CARD,
            mode = mode,
            networkTier = networkTier,
        )
        val expanded = selectVariant(
            rawUrl = "https://pbs.twimg.com/media/sample.jpg",
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = mode,
            networkTier = networkTier,
        )
        val expandedLabel = when (expanded.variantLabel) {
            "ORIG" -> "Original"
            "1080p" -> "1080p max"
            else -> "HD (2048px)"
        }
        return "${networkTier.label} · Timeline ${timeline.variantLabel} preview · Tap for $expandedLabel"
    }

    /**
     * Rewrites `pbs.twimg.com` photo & video-thumbnail URLs to request a specific CDN variant
     * (`360x360`, `small`, `medium`, `large`, `orig`) via the standard `name=` query parameter.
     */
    internal fun rewriteTwimgMediaVariant(url: String, sizeName: String): String {
        if (!isTwimgResizableMedia(url)) return url
        return if (MEDIA_NAME_PARAM_REGEX.containsMatchIn(url)) {
            url.replace(MEDIA_NAME_PARAM_REGEX, "$1name=$sizeName")
        } else if (url.contains('?')) {
            "$url&name=$sizeName"
        } else {
            "$url?name=$sizeName"
        }
    }

    /**
     * Rewrites `pbs.twimg.com/profile_images/...` URLs to a bandwidth-appropriate avatar size.
     */
    internal fun rewriteTwimgAvatarVariant(url: String, targetSuffix: String): String {
        if (!url.contains("pbs.twimg.com/profile_images/", ignoreCase = true)) return url
        return if (AVATAR_SUFFIX_REGEX.containsMatchIn(url)) {
            url.replace(AVATAR_SUFFIX_REGEX, "$targetSuffix$2$3")
        } else {
            url
        }
    }

    /**
     * Rewrites `pbs.twimg.com/profile_banners/...` URLs to a verified X CDN banner size
     * (`600x200` or `1500x500`).
     */
    internal fun rewriteTwimgBannerVariant(url: String, targetSuffix: String): String {
        if (!url.contains("pbs.twimg.com/profile_banners/", ignoreCase = true)) return url
        val baseWithoutQuery = url.substringBefore('?')
        val querySuffix = if (url.contains('?')) "?${url.substringAfter('?')}" else ""
        val trimmedBase = baseWithoutQuery.trimEnd('/')
        val updatedBase = if (BANNER_SUFFIX_REGEX.containsMatchIn(trimmedBase)) {
            trimmedBase.replace(BANNER_SUFFIX_REGEX, "/$targetSuffix")
        } else {
            "$trimmedBase/$targetSuffix"
        }
        return "$updatedBase$querySuffix"
    }

    private fun isTwimgResizableMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("pbs.twimg.com/media/") ||
            lower.contains("pbs.twimg.com/ext_tw_video_thumb/") ||
            lower.contains("pbs.twimg.com/amplify_video_thumb/") ||
            lower.contains("pbs.twimg.com/tweet_video_thumb/") ||
            lower.contains("pbs.twimg.com/card_img/")
    }

    private data class Quad(
        val sizeName: String,
        val widthPx: Int,
        val heightPx: Int,
        val label: String,
    )
}
