package com.example.data.remote

import com.example.domain.model.LinkPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Lightweight, cached OpenGraph / Twitter-Card metadata resolver for external links in posts.
 * Features:
 * - Shared bounded LRU TTL cache (max 200 URLs)
 * - Cancellation-safe in-flight request coalescing (duplicate URLs share one network call)
 * - Concurrency batching via Semaphore(3) so fast feed scrolling never floods sockets
 * - Pre-compiled Regex patterns and capped 32KB Range reads on Dispatchers.IO
 */
class LinkPreviewResolver(
    baseClient: OkHttpClient,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = BoundedTtlCache<String, LinkPreview>(
        maxEntries = MAX_CACHE_ENTRIES,
        defaultTtlMs = CACHE_TTL_MS,
    )
    private val coalescer = RequestCoalescer<String, LinkPreview>()
    private val concurrencyLimiter = Semaphore(permits = MAX_CONCURRENT_FETCHES)

    suspend fun enrich(initial: LinkPreview): LinkPreview {
        if (initial.url.isBlank()) return initial
        if (initial.title.isNotBlank() && initial.imageUrl.isNotBlank()) {
            return initial
        }
        cache.get(initial.url)?.let { return it }

        return coalescer.coalesce(initial.url) {
            val resolved = withContext(Dispatchers.IO) {
                concurrencyLimiter.withPermit {
                    fetchPreview(initial)
                }
            }
            cache.put(initial.url, resolved)
            resolved
        }
    }

    private fun fetchPreview(initial: LinkPreview): LinkPreview = runCatching {
        val req = Request.Builder()
            .url(initial.url)
            .header("User-Agent", XEndpointRegistry.WEB_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Range", "bytes=0-32768")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) return@runCatching initial
            val contentType = resp.header("Content-Type").orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
                return@runCatching initial
            }
            val finalUrl = resp.request.url.toString()
            val rawBytes = resp.body.byteStream().use { stream ->
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
            if (rawBytes.isBlank()) return@runCatching initial

            val metaMap = parseMetaTags(rawBytes)
            val ogTitle = metaMap["og:title"]
                ?: metaMap["twitter:title"]
                ?: extractHtmlTitle(rawBytes)
            val ogDesc = metaMap["og:description"]
                ?: metaMap["twitter:description"]
                ?: metaMap["description"]
                ?: ""
            val ogImageRaw = metaMap["og:image"]
                ?: metaMap["twitter:image"]
                ?: metaMap["twitter:image:src"]
                ?: ""
            val ogImage = resolveRelativeUrl(finalUrl, ogImageRaw)
            val host = runCatching { URI(finalUrl).host?.removePrefix("www.") }.getOrNull().orEmpty()

            initial.copy(
                url = finalUrl.ifBlank { initial.url },
                domain = initial.domain.ifBlank { host },
                title = initial.title.ifBlank { decodeHtml(ogTitle) },
                description = initial.description.ifBlank { decodeHtml(ogDesc) },
                imageUrl = initial.imageUrl.ifBlank { ogImage },
            )
        }
    }.getOrDefault(initial)

    /**
     * Single-pass `<meta>` scanner using pre-compiled regexes instead of re-compiling 12+
     * regexes per URL.
     */
    private fun parseMetaTags(html: String): Map<String, String> {
        val result = HashMap<String, String>(8)
        for (match in META_TAG_REGEX.findAll(html)) {
            val tag = match.value
            val key = META_KEY_REGEX.find(tag)?.groupValues?.getOrNull(1)?.lowercase() ?: continue
            if (key !in TARGET_META_KEYS || result.containsKey(key)) continue
            val content = META_CONTENT_REGEX.find(tag)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (content.isNotBlank()) {
                result[key] = content
            }
        }
        return result
    }

    private fun extractHtmlTitle(html: String): String =
        HTML_TITLE_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            .orEmpty()

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        if (candidate.isBlank()) return ""
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        if (candidate.startsWith("//")) return "https:$candidate"
        return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrDefault("")
    }

    private fun decodeHtml(input: String): String = input
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#x27;", "'")
        .trim()

    companion object {
        private const val MAX_CACHE_ENTRIES = 200
        private const val CACHE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_CONCURRENT_FETCHES = 3

        private val TARGET_META_KEYS = setOf(
            "og:title",
            "twitter:title",
            "og:description",
            "twitter:description",
            "description",
            "og:image",
            "twitter:image",
            "twitter:image:src",
        )

        private val META_TAG_REGEX = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val META_KEY_REGEX = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val META_CONTENT_REGEX = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val HTML_TITLE_REGEX = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
