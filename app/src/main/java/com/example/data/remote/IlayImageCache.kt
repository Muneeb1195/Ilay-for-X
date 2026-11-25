package com.example.data.remote

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.Tweet
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File

/**
 * Advanced Coil 3 disk + memory caching configuration for Ilay for X:
 * - Uses a cache-free OkHttpClient derived from AppContainer's HTTP/2 pool so Coil's own
 *   DiskCache is the single authoritative disk store (avoiding double disk writes & eviction thrash).
 * - Persists up to 250 MB (adaptive 5% free-space quota, minimum 64 MB) on disk across restarts.
 * - Normalizes media URLs into deterministic cache keys so scrolling up/down or revisiting posts
 *   serves straight from memory/disk with zero redundant network downloads.
 * - Prefetches upcoming timeline cards & avatars ahead of scroll position to keep perceived load < 1s.
 */
object IlayImageCache {

    private const val DISK_CACHE_DIR_NAME = "ilay_image_disk_cache"
    private const val DISK_CACHE_MIN_BYTES = 64L * 1024L * 1024L
    private const val DISK_CACHE_MAX_BYTES = 250L * 1024L * 1024L
    private const val DISK_CACHE_PERCENT = 0.05
    private const val MEMORY_CACHE_PERCENT = 0.25

    /**
     * Canonicalizes X/Twitter and external media URLs into a stable cache key
     * (normalizes scheme, trims whitespace, strips ephemeral tracking parameters).
     */
    fun canonicalKey(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        val httpsUrl = if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://${trimmed.substring(7)}"
        } else {
            trimmed
        }
        val qIndex = httpsUrl.indexOf('?')
        if (qIndex < 0) return httpsUrl.trimEnd('/')
        val base = httpsUrl.substring(0, qIndex).trimEnd('/')
        val query = httpsUrl.substring(qIndex + 1)
        val keptParams = query.split('&')
            .filter { part ->
                val key = part.substringBefore('=').lowercase()
                key.isNotBlank() &&
                    !key.startsWith("utm_") &&
                    key != "s" &&
                    key != "t" &&
                    key != "fbclid" &&
                    key != "ref_src"
            }
            .sorted()
        return if (keptParams.isEmpty()) base else "$base?${keptParams.joinToString("&")}"
    }

    /**
     * Custom Coil 3 Keyer so even raw URL strings passed directly to AsyncImage share
     * the exact canonical cache key across memory and disk caches.
     */
    private class CanonicalUrlKeyer : Keyer<Uri> {
        override fun key(data: Uri, options: Options): String? {
            val raw = data.toString()
            if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
                return null
            }
            return canonicalKey(raw)
        }
    }

    fun buildImageLoader(
        context: PlatformContext,
        appCacheDir: File,
        sharedOkHttpClient: OkHttpClient,
    ): ImageLoader {
        // Share HTTP/2 connection pool & dispatcher while disabling OkHttp's own disk cache
        // so Coil's DiskCache is the single source of truth (no double disk I/O).
        val imageOkHttpClient = sharedOkHttpClient.newBuilder()
            .cache(null)
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageOkHttpClient }))
                add(CanonicalUrlKeyer())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appCacheDir, DISK_CACHE_DIR_NAME))
                    .maxSizePercent(DISK_CACHE_PERCENT)
                    .minimumMaxSizeBytes(DISK_CACHE_MIN_BYTES)
                    .maximumMaxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .fetcherCoroutineContext(Dispatchers.IO)
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(4))
            .crossfade(120)
            .build()
    }

    /**
     * Adaptive ImageRequest for timeline card images, embedded quotes, link cards, & video thumbnails.
     * Automatically rewrites X CDN URLs to bandwidth-saving lower-resolution preview variants
     * (`360x360`, `small`, or `medium`) and uses RGB_565 (50% less RAM).
     */
    fun cardMediaRequest(
        context: Context,
        rawUrl: String,
        compact: Boolean = false,
        mode: ImageQualityMode = AdaptiveImageQualitySelector.currentMode,
    ): ImageRequest {
        val networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
        val variant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawUrl,
            usage = if (compact) ImageUsage.COMPACT_THUMBNAIL else ImageUsage.TIMELINE_CARD,
            mode = mode,
            networkTier = networkTier,
        )
        return buildCardMediaRequest(context, variant)
    }

    private fun buildCardMediaRequest(
        context: Context,
        variant: SelectedImageVariant,
    ): ImageRequest {
        val key = canonicalKey(variant.url)
        return ImageRequest.Builder(context)
            .data(variant.url)
            .diskCacheKey(key)
            .memoryCacheKey(key)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (variant.targetWidthPx > 0 && variant.targetHeightPx > 0) {
                    size(variant.targetWidthPx, variant.targetHeightPx)
                }
            }
            .precision(Precision.INEXACT)
            .allowRgb565(variant.allowRgb565)
            .crossfade(100)
            .build()
    }

    /**
     * Adaptive ImageRequest for circular user avatars (`_200x200` on Wi-Fi, `_bigger` on cellular/Data Saver).
     */
    fun avatarRequest(
        context: Context,
        rawUrl: String,
        mode: ImageQualityMode = AdaptiveImageQualitySelector.currentMode,
    ): ImageRequest {
        val networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
        val variant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawUrl,
            usage = ImageUsage.AVATAR,
            mode = mode,
            networkTier = networkTier,
        )
        return buildAvatarRequest(context, variant)
    }

    private fun buildAvatarRequest(
        context: Context,
        variant: SelectedImageVariant,
    ): ImageRequest {
        val key = canonicalKey(variant.url)
        return ImageRequest.Builder(context)
            .data(variant.url)
            .diskCacheKey(key)
            .memoryCacheKey(key)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .size(variant.targetWidthPx, variant.targetHeightPx)
            .precision(Precision.INEXACT)
            .crossfade(80)
            .build()
    }

    /**
     * Adaptive ImageRequest for profile header banners (`600x200` on cellular/Data Saver,
     * `1500x500` on Wi-Fi — with optional raw-URL fallback if a legacy banner lacks size variants).
     */
    fun bannerRequest(
        context: Context,
        rawUrl: String,
        mode: ImageQualityMode = AdaptiveImageQualitySelector.currentMode,
        useRawUrl: Boolean = false,
    ): ImageRequest {
        val networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
        val variant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawUrl,
            usage = ImageUsage.PROFILE_BANNER,
            mode = mode,
            networkTier = networkTier,
        )
        val targetUrl = if (useRawUrl) rawUrl.trim() else variant.url
        val key = canonicalKey(targetUrl)
        return ImageRequest.Builder(context)
            .data(targetUrl)
            .diskCacheKey(key)
            .memoryCacheKey(key)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (variant.targetWidthPx > 0 && variant.targetHeightPx > 0) {
                    size(variant.targetWidthPx, variant.targetHeightPx)
                }
            }
            .precision(Precision.INEXACT)
            .allowRgb565(true)
            .crossfade(100)
            .build()
    }

    /**
     * Full-resolution ImageRequest used ONLY when the user taps to expand a photo in the lightbox.
     * Reuses the already-cached lower-resolution timeline preview on frame 0 while fetching and
     * caching the full natural-aspect-ratio (`orig` / `large`) image.
     */
    fun lightboxImageRequest(
        context: Context,
        highResUrl: String,
        thumbnailUrl: String,
        mode: ImageQualityMode = AdaptiveImageQualitySelector.currentMode,
        forceOriginal: Boolean = false,
    ): ImageRequest {
        val networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
        val fullVariant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = highResUrl,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = mode,
            networkTier = networkTier,
            forceOriginal = forceOriginal,
        )
        val highKey = canonicalKey(fullVariant.url)

        // Locate whichever lower-res timeline preview is already warm in memory for instant frame-0 display
        val memCache = SingletonImageLoader.get(context).memoryCache
        val previewBase = thumbnailUrl.ifBlank { highResUrl }
        val cardPreviewKey = canonicalKey(
            AdaptiveImageQualitySelector.previewUrlFor(
                rawUrl = previewBase,
                compact = false,
                mode = mode,
                networkTier = networkTier,
            )
        )
        val compactPreviewKey = canonicalKey(
            AdaptiveImageQualitySelector.previewUrlFor(
                rawUrl = previewBase,
                compact = true,
                mode = mode,
                networkTier = networkTier,
            )
        )
        val rawThumbKey = canonicalKey(previewBase)
        val placeholderKey = listOf(cardPreviewKey, compactPreviewKey, rawThumbKey)
            .firstOrNull { candidate ->
                candidate.isNotBlank() && memCache?.get(MemoryCache.Key(candidate)) != null
            } ?: cardPreviewKey.ifBlank { rawThumbKey }

        return ImageRequest.Builder(context)
            .data(fullVariant.url)
            .diskCacheKey(highKey)
            .memoryCacheKey(highKey)
            .apply {
                if (placeholderKey.isNotBlank()) {
                    placeholderMemoryCacheKey(MemoryCache.Key(placeholderKey))
                }
                if (fullVariant.targetWidthPx > 0 && fullVariant.targetHeightPx > 0) {
                    size(fullVariant.targetWidthPx, fullVariant.targetHeightPx)
                }
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowRgb565(false)
            .crossfade(120)
            .build()
    }

    /**
     * Proactively warms Coil's disk & memory caches for upcoming timeline items using ONLY
     * bandwidth-saving lower-resolution preview variants (never full-resolution photos).
     */
    fun prefetchTimelineMedia(
        context: Context,
        tweets: List<Tweet>,
        startIndex: Int,
        windowSize: Int = 10,
        mode: ImageQualityMode = AdaptiveImageQualitySelector.currentMode,
    ) {
        if (tweets.isEmpty()) return
        val imageLoader = SingletonImageLoader.get(context)
        val memCache = imageLoader.memoryCache
        val networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
        val effectiveWindow = if (mode == ImageQualityMode.DATA_SAVER ||
            networkTier == NetworkTier.DATA_SAVER_RESTRICTED
        ) {
            windowSize.coerceAtMost(4)
        } else {
            windowSize
        }
        val from = startIndex.coerceIn(0, tweets.lastIndex)
        val to = (from + effectiveWindow).coerceAtMost(tweets.size)

        fun enqueueAvatarIfCold(rawUrl: String) {
            if (rawUrl.isBlank()) return
            val variant = AdaptiveImageQualitySelector.selectVariant(
                rawUrl = rawUrl,
                usage = ImageUsage.AVATAR,
                mode = mode,
                networkTier = networkTier,
            )
            if (memCache?.get(MemoryCache.Key(canonicalKey(variant.url))) == null) {
                imageLoader.enqueue(buildAvatarRequest(context, variant))
            }
        }

        fun enqueueCardMediaIfCold(rawUrl: String) {
            if (rawUrl.isBlank()) return
            val variant = AdaptiveImageQualitySelector.selectVariant(
                rawUrl = rawUrl,
                usage = ImageUsage.TIMELINE_CARD,
                mode = mode,
                networkTier = networkTier,
            )
            if (memCache?.get(MemoryCache.Key(canonicalKey(variant.url))) == null) {
                imageLoader.enqueue(buildCardMediaRequest(context, variant))
            }
        }

        for (i in from until to) {
            val tweet = tweets[i]
            enqueueAvatarIfCold(tweet.author.avatarUrl)
            tweet.media.take(2).forEach { media ->
                enqueueCardMediaIfCold(media.thumbnailUrl.ifBlank { media.url })
            }
            tweet.quotedTweet?.let { quoted ->
                enqueueAvatarIfCold(quoted.author.avatarUrl)
                quoted.media.firstOrNull()?.let { qMedia ->
                    enqueueCardMediaIfCold(qMedia.thumbnailUrl.ifBlank { qMedia.url })
                }
            }
            tweet.article?.coverImageUrl?.let(::enqueueCardMediaIfCold)
        }
    }
}
