package com.example.data.remote

import com.example.data.local.AccountEntity
import com.example.domain.model.NotificationItem
import com.example.domain.model.SearchTab
import com.example.domain.model.ThreadConversation
import com.example.domain.model.TimelinePage
import com.example.domain.model.TrendItem
import com.example.domain.model.Tweet
import com.example.domain.model.UniversalSearchSuggestions
import com.example.domain.model.XUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TranslationResult(
    val translatedText: String,
    val sourceLanguage: String,
    val provider: String,
)

/**
 * Direct reverse-engineered X internal GraphQL + v2/v1.1 internal API client.
 * Includes resilient multi-step viewer profile resolution, adaptive keyword search,
 * fuzzy typeahead people search, native X translation, and self-healing queryId retries.
 */
class XGraphqlClient(
    private val okHttpClient: OkHttpClient,
    private val registry: XEndpointRegistry,
    private val txIdProvider: XClientTransactionIdProvider,
    val rateLimitTracker: XRateLimitTracker = XRateLimitTracker(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Dedicated tighter-timeout client for interactive reads so stalled calls fail fast
    private val fastClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves the currently authenticated X account's full profile within a strict 6.5s budget:
     * 1. Known handle -> race GraphQL UserByScreenName + REST users/show.json concurrently.
     * 2. Known numeric twid -> race REST users/show.json + GraphQL UserByRestId concurrently.
     * 3. Unknown handle -> settings.json / verify_credentials.json (with HTML fallback only if still unresolved).
     */
    suspend fun fetchViewerProfile(
        account: AccountEntity,
        twidUserId: String? = null,
    ): Result<XUser> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(6_500L) {
            fetchViewerProfileInternal(account, twidUserId)
        } ?: Result.failure(IllegalStateException("X profile lookup timed out"))
    }

    private suspend fun fetchViewerProfileInternal(
        account: AccountEntity,
        twidUserId: String?,
    ): Result<XUser> = coroutineScope {
        val numericId = twidUserId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: account.userId.takeIf { it.isNotBlank() && it.all(Char::isDigit) }

        val savedHandle = account.handle.takeIf {
            it.isNotBlank() && it != "x_user" && it != "x_account" && it != "unknown"
        }

        // 1. Fast parallel path (~150-300ms): race GraphQL + REST by known screen_name
        if (!savedHandle.isNullOrBlank()) {
            val gqlDeferred = async { fetchUserByScreenName(account, savedHandle) }
            val restDeferred = async { fetchUserRestV1(account, screenName = savedHandle, userId = null) }
            val gqlRes = gqlDeferred.await()
            if (gqlRes.isSuccess) {
                restDeferred.cancel()
                return@coroutineScope gqlRes
            }
            val restRes = restDeferred.await()
            if (restRes.isSuccess) return@coroutineScope restRes
            val authErr = gqlRes.exceptionOrNull() as? XApiException.AuthOrChallengeError
                ?: restRes.exceptionOrNull() as? XApiException.AuthOrChallengeError
            if (authErr != null) return@coroutineScope Result.failure(authErr)
        }

        // 2. Fast parallel path (~150-300ms): race REST + GraphQL by numeric twid
        if (!numericId.isNullOrBlank()) {
            val restDeferred = async { fetchUserRestV1(account, screenName = null, userId = numericId) }
            val gqlDeferred = async { fetchUserByRestId(account, numericId) }
            val restRes = restDeferred.await()
            if (restRes.isSuccess) {
                gqlDeferred.cancel()
                return@coroutineScope restRes
            }
            val gqlRes = gqlDeferred.await()
            if (gqlRes.isSuccess) return@coroutineScope gqlRes
            val authErr = restRes.exceptionOrNull() as? XApiException.AuthOrChallengeError
                ?: gqlRes.exceptionOrNull() as? XApiException.AuthOrChallengeError
            if (authErr != null) return@coroutineScope Result.failure(authErr)
        }

        // 3. Discover handle via settings.json and verify_credentials.json concurrently
        val settingsDeferred = async {
            runSuspendCatching {
                val path = "/i/api/1.1/account/settings.json"
                val url = "https://x.com$path?include_mention_filter=true&include_nsfw_user_flag=true&include_ranked_timeline=true"
                val txId = txIdProvider.generate("GET", path)
                val req = buildBaseRequest(url, account, txId).get().build()
                fastClient.newCall(req).awaitUse { resp ->
                    rateLimitTracker.recordResponseHeaders(account.userId, "account_settings", resp.headers)
                    if (!resp.isSuccessful) error("settings HTTP ${resp.code}")
                    JSONObject(resp.body.string()).optString("screen_name").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
        val verifyDeferred = async {
            runSuspendCatching {
                val path = "/i/api/1.1/account/verify_credentials.json"
                val url = "https://x.com$path?include_email=false&skip_status=true"
                val txId = txIdProvider.generate("GET", path)
                val req = buildBaseRequest(url, account, txId).get().build()
                fastClient.newCall(req).awaitUse { resp ->
                    rateLimitTracker.recordResponseHeaders(account.userId, "verify_credentials", resp.headers)
                    if (!resp.isSuccessful) error("verify HTTP ${resp.code}")
                    val obj = JSONObject(resp.body.string())
                    val handle = obj.optString("screen_name")
                    if (handle.isBlank()) error("Missing handle")
                    XGraphqlParser.parseLegacyUser(obj)
                }
            }
        }

        val verifyResult = verifyDeferred.await()
        if (verifyResult.isSuccess) {
            settingsDeferred.cancel()
            return@coroutineScope verifyResult
        }

        val settingsHandle = settingsDeferred.await()
        val discoveredHandle = settingsHandle?.takeIf { it.isNotBlank() } ?: savedHandle
        if (!discoveredHandle.isNullOrBlank() && discoveredHandle != savedHandle) {
            val gqlProfile = fetchUserByScreenName(account, discoveredHandle)
            if (gqlProfile.isSuccess) return@coroutineScope gqlProfile
            return@coroutineScope Result.success(
                XUser(
                    id = numericId ?: discoveredHandle,
                    handle = discoveredHandle,
                    displayName = account.displayName.ifBlank { discoveredHandle },
                    avatarUrl = account.avatarUrl,
                    bannerUrl = account.bannerUrl,
                )
            )
        }

        // 4. Last resort (only when handle & numericId were both missing): authenticated x.com/home HTML
        val htmlViewer = runSuspendCatching {
            val req = Request.Builder()
                .url("https://x.com/home")
                .header("Cookie", "auth_token=${account.authToken}; ct0=${account.ct0}")
                .header("User-Agent", XEndpointRegistry.DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            fastClient.newCall(req).awaitUse { resp ->
                if (!resp.isSuccessful) null
                else XGraphqlParser.parseInitialStateViewerUser(resp.body.string(), numericId)
            }
        }.getOrNull()
        if (htmlViewer != null && htmlViewer.handle.isNotBlank()) {
            return@coroutineScope Result.success(htmlViewer)
        }

        if (!discoveredHandle.isNullOrBlank()) {
            return@coroutineScope Result.success(
                XUser(
                    id = numericId ?: discoveredHandle,
                    handle = discoveredHandle,
                    displayName = account.displayName.ifBlank { discoveredHandle },
                    avatarUrl = account.avatarUrl,
                    bannerUrl = account.bannerUrl,
                )
            )
        }

        Result.failure(IllegalStateException("Unable to resolve authenticated X viewer profile"))
    }

    suspend fun fetchUserRestV1(
        account: AccountEntity,
        screenName: String? = null,
        userId: String? = null,
    ): Result<XUser> = withContext(Dispatchers.IO) {
        rateLimitTracker.getBlockedUntilEpochMs(account.userId, "users_show")?.let { resetAt ->
            return@withContext Result.failure(XApiException.RateLimited("users/show", resetAt))
        }
        runSuspendCatching {
            val path = "/i/api/1.1/users/show.json"
            val builder = "https://x.com$path".toHttpUrl().newBuilder()
                .addQueryParameter("include_entities", "true")
            if (!screenName.isNullOrBlank()) builder.addQueryParameter("screen_name", screenName.removePrefix("@"))
            if (!userId.isNullOrBlank()) builder.addQueryParameter("user_id", userId)
            val txId = txIdProvider.generate("GET", path)
            val req = buildBaseRequest(builder.build().toString(), account, txId).get().build()
            fastClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, "users_show", resp.headers)
                val body = resp.body.string()
                XErrorClassifier.classifyResponse("users/show", resp.code, body, resp.headers, requireUsableData = false)
                    ?.let { err ->
                        if (err is XApiException.RateLimited) {
                            rateLimitTracker.markExhausted(account.userId, "users_show", err.resetAtEpochMs)
                        }
                        throw err
                    }
                val obj = JSONObject(body)
                if (obj.optString("screen_name").isBlank()) error("Missing screen_name")
                XGraphqlParser.parseLegacyUser(obj)
            }
        }
    }

    private suspend fun fetchUserByRestId(account: AccountEntity, userId: String): Result<XUser> {
        val vars = JSONObject()
            .put("userId", userId)
            .put("withSafetyModeUserFields", true)
        return executeGraphqlGet("UserByRestId", vars, account, useFastClient = true).mapCatching { raw ->
            XGraphqlParser.parseUserResult(raw) ?: error("User $userId not found")
        }
    }

    suspend fun fetchHomeTimeline(
        account: AccountEntity,
        latestOnly: Boolean,
        cursor: String? = null,
        count: Int = 25,
    ): Result<TimelinePage> {
        val op = if (latestOnly) "HomeLatestTimeline" else "HomeTimeline"
        val vars = JSONObject()
            .put("count", count)
            .put("includePromotedContent", false)
            .put("latestControlAvailable", true)
            .put("requestContext", "launch")
        if (!cursor.isNullOrBlank()) vars.put("cursor", cursor)

        return executeGraphqlGet(op, vars, account, useFastClient = true).map { raw ->
            XGraphqlParser.parseTimeline(raw, "X GraphQL ($op)")
        }
    }

    suspend fun fetchUserByScreenName(account: AccountEntity, handle: String): Result<XUser> {
        val vars = JSONObject()
            .put("screen_name", handle.removePrefix("@"))
            .put("withSafetyModeUserFields", true)
        return executeGraphqlGet("UserByScreenName", vars, account, useFastClient = true).mapCatching { raw ->
            XGraphqlParser.parseUserResult(raw) ?: error("User @$handle not found")
        }
    }

    suspend fun fetchUserTimeline(
        account: AccountEntity,
        userId: String,
        operationName: String = "UserTweets",
        cursor: String? = null,
        count: Int = 25,
    ): Result<TimelinePage> {
        val vars = JSONObject()
            .put("userId", userId)
            .put("count", count)
            .put("includePromotedContent", false)
            .put("withQuickPromoteEligibilityTweetFields", true)
            .put("withVoice", true)
            .put("withV2Timeline", true)
        if (operationName == "UserTweetsAndReplies") {
            vars.put("withCommunity", true)
        }
        if (!cursor.isNullOrBlank()) vars.put("cursor", cursor)

        val gql = executeGraphqlGet(operationName, vars, account, useFastClient = true).map { raw ->
            XGraphqlParser.parseTimeline(raw, "X GraphQL ($operationName)")
        }
        if (gql.isSuccess) return gql
        val gqlErr = gql.exceptionOrNull()
        if (gqlErr is XApiException.RateLimited || gqlErr is XApiException.AuthOrChallengeError) {
            return gql
        }

        // Authenticated REST v1.1 fallback for Posts & Replies when GraphQL hashes/features rotate
        if (cursor == null && (operationName == "UserTweets" || operationName == "UserTweetsAndReplies")) {
            val restFallback = fetchUserTimelineRestV1(
                account = account,
                userId = userId,
                includeReplies = operationName == "UserTweetsAndReplies",
                count = count,
            )
            if (restFallback.isSuccess) return restFallback
        }
        return gql
    }

    private suspend fun fetchUserTimelineRestV1(
        account: AccountEntity,
        userId: String,
        includeReplies: Boolean,
        count: Int = 25,
    ): Result<TimelinePage> = withContext(Dispatchers.IO) {
        rateLimitTracker.getBlockedUntilEpochMs(account.userId, "user_timeline")?.let { resetAt ->
            return@withContext Result.failure(XApiException.RateLimited("user_timeline", resetAt))
        }
        runSuspendCatching {
            val path = "/i/api/1.1/statuses/user_timeline.json"
            val url = "https://x.com$path".toHttpUrl().newBuilder()
                .addQueryParameter("user_id", userId)
                .addQueryParameter("count", count.toString())
                .addQueryParameter("exclude_replies", if (includeReplies) "false" else "true")
                .addQueryParameter("include_rts", "true")
                .addQueryParameter("tweet_mode", "extended")
                .build()
                .toString()
            val txId = txIdProvider.generate("GET", path)
            val req = buildBaseRequest(url, account, txId).get().build()
            fastClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, "user_timeline", resp.headers)
                if (!resp.isSuccessful) error("user_timeline HTTP ${resp.code}")
                val tweets = XGraphqlParser.parseLegacyTweetsArray(resp.body.string())
                TimelinePage(
                    tweets = tweets,
                    nextCursor = null,
                    isFromPublicFallback = false,
                    sourceInfo = "X REST Timeline",
                )
            }
        }
    }

    suspend fun fetchUserArticles(
        account: AccountEntity,
        userId: String,
        cursor: String? = null,
        count: Int = 20,
    ): Result<TimelinePage> = fetchUserTimeline(
        account = account,
        userId = userId,
        operationName = "UserArticlesTweets",
        cursor = cursor,
        count = count,
    )

    suspend fun fetchThreadDetail(
        account: AccountEntity,
        tweetId: String,
        cursor: String? = null,
    ): Result<ThreadConversation> {
        val vars = JSONObject()
            .put("focalTweetId", tweetId)
            .put("with_rux_injections", false)
            .put("rankingMode", "Relevance")
            .put("includePromotedContent", false)
            .put("withCommunity", true)
            .put("withQuickPromoteEligibilityTweetFields", false)
            .put("withBirdwatchNotes", true)
            .put("withVoice", true)
        if (!cursor.isNullOrBlank()) vars.put("cursor", cursor)

        return executeGraphqlGet("TweetDetail", vars, account, useFastClient = true).mapCatching { raw ->
            XGraphqlParser.parseThreadDetail(raw, tweetId) ?: error("Thread unavailable")
        }
    }

    /**
     * Fast universal global tweet & #hashtag content search:
     * 1. Queries `SearchTimeline` directly without blocking on `refreshIfStale()` (hashes auto-heal
     *    on actual HTTP 404/400/GraphQL error).
     * 2. Short-circuits redundant POST / HTML scrapes when GET succeeded cleanly or returned results.
     * 3. Falls back cleanly with tight timeouts to POST GraphQL -> REST v2 `adaptive.json` -> HTML.
     */
    suspend fun searchTimeline(
        account: AccountEntity,
        query: String,
        tab: SearchTab,
        cursor: String? = null,
        count: Int = 25,
    ): Result<TimelinePage> {
        val trimmed = query.trim()
        val effectiveQuery = if (tab == SearchTab.MEDIA && !trimmed.contains("filter:media", ignoreCase = true)) {
            "$trimmed filter:media"
        } else {
            trimmed
        }
        val querySource = if (trimmed.startsWith("#")) "hashtag_click" else "typed_query"
        val encodedQuery = runCatching { URLEncoder.encode(effectiveQuery, "UTF-8") }.getOrDefault(effectiveQuery)
        val searchReferer = "https://x.com/search?q=$encodedQuery&src=$querySource"

        val vars = JSONObject()
            .put("rawQuery", effectiveQuery)
            .put("count", count)
            .put("querySource", querySource)
            .put("product", tab.product)
        if (!cursor.isNullOrBlank()) vars.put("cursor", cursor)

        // 1. Primary: GraphQL SearchTimeline GET (with targeted txid/feature repair only)
        val gql = executeGraphqlGet(
            operationName = "SearchTimeline",
            variables = vars,
            account = account,
            refererOverride = searchReferer,
            useFastClient = true,
        ).map { raw ->
            XGraphqlParser.parseTimeline(raw, "X Global Search (${tab.label})")
        }
        if (gql.isSuccess && gql.getOrNull()?.tweets.orEmpty().isNotEmpty()) {
            return gql
        }
        val gqlErr = gql.exceptionOrNull()
        if (gqlErr is XApiException.RateLimited || gqlErr is XApiException.AuthOrChallengeError) {
            return gql
        }

        // 1b. Only try GraphQL POST when GET actually failed (e.g. 414 / encoding quirk) or for #hashtag queries
        val gqlPost = if (gql.isFailure || trimmed.startsWith("#")) {
            executeGraphqlPost(
                operationName = "SearchTimeline",
                variables = vars,
                account = account,
                txIdRefreshed = true,
                refererOverride = searchReferer,
                validateData = true,
                useFastClient = true,
            ).map { raw ->
                XGraphqlParser.parseTimeline(raw, "X Global Search (${tab.label})")
            }
        } else {
            gql
        }
        if (gqlPost.isSuccess && gqlPost.getOrNull()?.tweets.orEmpty().isNotEmpty()) {
            return gqlPost
        }
        val postErr = gqlPost.exceptionOrNull()
        if (postErr is XApiException.RateLimited || postErr is XApiException.AuthOrChallengeError) {
            return gqlPost
        }

        // 2. REST v2 Adaptive global tweet & hashtag search (single fast request)
        val adaptive = searchAdaptive(account, effectiveQuery, tab, cursor, count, searchReferer)
        if (adaptive.isSuccess && adaptive.getOrNull()?.tweets.orEmpty().isNotEmpty()) {
            return adaptive
        }

        return when {
            gql.isSuccess -> gql
            gqlPost.isSuccess -> gqlPost
            else -> adaptive
        }
    }

    private suspend fun searchAdaptive(
        account: AccountEntity,
        query: String,
        tab: SearchTab,
        cursor: String? = null,
        count: Int = 25,
        refererOverride: String? = null,
    ): Result<TimelinePage> = withContext(Dispatchers.IO) {
        rateLimitTracker.getBlockedUntilEpochMs(account.userId, "search_adaptive")?.let { resetAt ->
            return@withContext Result.failure(XApiException.RateLimited("search/adaptive", resetAt))
        }
        val effectiveQuery = if (tab == SearchTab.MEDIA && !query.contains("filter:media", ignoreCase = true)) {
            "$query filter:media"
        } else {
            query
        }
        val querySource = if (query.trim().startsWith("#")) "hashtag_click" else "typed_query"
        val path = "/i/api/2/search/adaptive.json"
        runSuspendCatching {
            val builder = "https://x.com$path".toHttpUrl().newBuilder()
                .addQueryParameter("include_profile_interstitial_type", "1")
                .addQueryParameter("include_blocking", "1")
                .addQueryParameter("include_blocked_by", "1")
                .addQueryParameter("include_followed_by", "1")
                .addQueryParameter("include_want_retweets", "1")
                .addQueryParameter("include_mute_edge", "1")
                .addQueryParameter("include_can_dm", "1")
                .addQueryParameter("include_ext_alt_text", "true")
                .addQueryParameter("include_entities", "true")
                .addQueryParameter("skip_status", "1")
                .addQueryParameter("cards_platform", "Web-12")
                .addQueryParameter("include_cards", "1")
                .addQueryParameter("include_quote_count", "true")
                .addQueryParameter("q", effectiveQuery)
                .addQueryParameter("count", count.toString())
                .addQueryParameter("query_source", querySource)
                .addQueryParameter("pc", "1")
                .addQueryParameter("spelling_corrections", "1")
                .addQueryParameter("ext", "mediaStats,highlightedLabel,voiceInfo")
                .addQueryParameter("tweet_mode", "extended")
            if (tab == SearchTab.LATEST) {
                builder.addQueryParameter("tweet_search_mode", "live")
            }
            if (!cursor.isNullOrBlank()) {
                builder.addQueryParameter("cursor", cursor)
            }
            val txId = txIdProvider.generate("GET", path)
            val req = buildBaseRequest(builder.build().toString(), account, txId, refererOverride).get().build()
            fastClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, "search_adaptive", resp.headers)
                val body = resp.body.string()
                XErrorClassifier.classifyResponse("search/adaptive", resp.code, body, resp.headers, requireUsableData = false)
                    ?.let { err ->
                        if (err is XApiException.RateLimited) {
                            rateLimitTracker.markExhausted(account.userId, "search_adaptive", err.resetAtEpochMs)
                        }
                        throw err
                    }
                XGraphqlParser.parseAdaptiveSearch(body)
            }
        }
    }

    /**
     * Queries X's universal typeahead endpoint (`i/api/1.1/search/typeahead.json`) with
     * `result_type=events,users,topics` so search returns matching #hashtags, topics, AND accounts
     * instead of restricting results to user handles.
     */
    suspend fun fetchUniversalTypeahead(
        account: AccountEntity,
        query: String,
    ): Result<UniversalSearchSuggestions> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val path = "/i/api/1.1/search/typeahead.json"
            val url = "https://x.com$path".toHttpUrl().newBuilder()
                .addQueryParameter("include_ext_is_blue_verified", "1")
                .addQueryParameter("include_ext_verified_type", "1")
                .addQueryParameter("include_ext_profile_image_shape", "1")
                .addQueryParameter("q", query.trim())
                .addQueryParameter("src", "search_box")
                .addQueryParameter("result_type", "events,users,topics")
                .build()
                .toString()
            val txId = txIdProvider.generate("GET", path)
            val req = buildBaseRequest(url, account, txId).get().build()
            fastClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, "typeahead", resp.headers)
                if (!resp.isSuccessful) error("Typeahead HTTP ${resp.code}")
                XGraphqlParser.parseUniversalTypeahead(resp.body.string())
            }
        }
    }

    /**
     * Batch-hydrates full user metrics (follower counts, bios, verification) for up to 25 handles
     * in a single REST v1.1 `users/lookup.json` round-trip so typeahead results never display 0 followers.
     */
    suspend fun lookupUsersBatch(
        account: AccountEntity,
        handles: List<String>,
    ): Result<Map<String, XUser>> = withContext(Dispatchers.IO) {
        val cleanHandles = handles
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(25)
        if (cleanHandles.isEmpty()) return@withContext Result.success(emptyMap())
        runSuspendCatching {
            val path = "/i/api/1.1/users/lookup.json"
            val url = "https://x.com$path".toHttpUrl().newBuilder()
                .addQueryParameter("screen_name", cleanHandles.joinToString(","))
                .addQueryParameter("include_entities", "false")
                .build()
                .toString()
            val txId = txIdProvider.generate("GET", path)
            val req = buildBaseRequest(url, account, txId).get().build()
            fastClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, "users_lookup", resp.headers)
                if (!resp.isSuccessful) error("users/lookup HTTP ${resp.code}")
                XGraphqlParser.parseLegacyUsersArray(resp.body.string())
                    .associateBy { it.handle.lowercase() }
            }
        }
    }

    /**
     * Fuzzy people search: runs universal typeahead AND GraphQL People search CONCURRENTLY,
     * prefers full-metric GraphQL profiles per handle, and batch-hydrates any zero-follower
     * typeahead profiles via `users/lookup.json` so search cards display real follower counts.
     */
    suspend fun searchPeople(
        account: AccountEntity,
        query: String,
    ): Result<List<XUser>> = coroutineScope {
        val trimmed = query.trim()
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)

        val typeaheadDeferred = async(Dispatchers.IO) {
            fetchUniversalTypeahead(account, trimmed).getOrNull()?.users.orEmpty()
        }
        val gqlDeferred = async(Dispatchers.IO) {
            runCatching {
                val vars = JSONObject()
                    .put("rawQuery", trimmed)
                    .put("count", 20)
                    .put("querySource", "typed_query")
                    .put("product", "People")
                executeGraphqlGet(
                    operationName = "SearchTimeline",
                    variables = vars,
                    account = account,
                    refererOverride = "https://x.com/search?q=$encoded&f=user",
                    useFastClient = true,
                ).getOrNull()
                    ?.let { XGraphqlParser.parseUsersTimeline(it) }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

        val gqlUsers = gqlDeferred.await()
        val typeaheadUsers = typeaheadDeferred.await()

        // Merge preserving order while picking the richest profile record per handle
        val byHandle = LinkedHashMap<String, XUser>()
        fun mergeCandidate(candidate: XUser) {
            val key = candidate.handle.lowercase()
            val existing = byHandle[key]
            if (existing == null) {
                byHandle[key] = candidate
            } else {
                byHandle[key] = existing.copy(
                    id = existing.id.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: candidate.id,
                    displayName = existing.displayName.ifBlank { candidate.displayName },
                    avatarUrl = existing.avatarUrl.ifBlank { candidate.avatarUrl },
                    bannerUrl = existing.bannerUrl.ifBlank { candidate.bannerUrl },
                    bio = existing.bio.ifBlank { candidate.bio },
                    isVerified = existing.isVerified || candidate.isVerified,
                    followersCount = maxOf(existing.followersCount, candidate.followersCount),
                    followingCount = maxOf(existing.followingCount, candidate.followingCount),
                    tweetsCount = maxOf(existing.tweetsCount, candidate.tweetsCount),
                    isFollowing = existing.isFollowing || candidate.isFollowing,
                )
            }
        }
        gqlUsers.forEach(::mergeCandidate)
        typeaheadUsers.forEach(::mergeCandidate)

        val mergedList = byHandle.values.toList()
        if (mergedList.isEmpty()) {
            return@coroutineScope Result.failure(IllegalStateException("No matching people found for \"$query\""))
        }

        // Batch-hydrate follower counts & bios for any typeahead-only accounts missing counts
        val unhydratedHandles = mergedList
            .filter { it.followersCount == 0L && it.followingCount == 0L && it.tweetsCount == 0L }
            .map { it.handle }
        val hydratedMap: Map<String, XUser> = if (unhydratedHandles.isNotEmpty()) {
            val batch = lookupUsersBatch(account, unhydratedHandles).getOrNull().orEmpty()
            if (batch.isNotEmpty()) {
                batch
            } else {
                // Fast parallel fallback for top 5 matches when REST lookup is restricted
                unhydratedHandles.take(5).map { handle ->
                    async(Dispatchers.IO) {
                        fetchUserByScreenName(account, handle).getOrNull()
                            ?: fetchUserRestV1(account, screenName = handle).getOrNull()
                    }
                }.mapNotNull { it.await() }.associateBy { it.handle.lowercase() }
            }
        } else {
            emptyMap()
        }

        val finalUsers = if (hydratedMap.isEmpty()) {
            mergedList
        } else {
            mergedList.map { user ->
                val rich = hydratedMap[user.handle.lowercase()] ?: return@map user
                user.copy(
                    id = rich.id.ifBlank { user.id },
                    displayName = rich.displayName.ifBlank { user.displayName },
                    avatarUrl = rich.avatarUrl.ifBlank { user.avatarUrl },
                    bannerUrl = rich.bannerUrl.ifBlank { user.bannerUrl },
                    bio = rich.bio.ifBlank { user.bio },
                    isVerified = user.isVerified || rich.isVerified,
                    followersCount = maxOf(user.followersCount, rich.followersCount),
                    followingCount = maxOf(user.followingCount, rich.followingCount),
                    tweetsCount = maxOf(user.tweetsCount, rich.tweetsCount),
                    isFollowing = user.isFollowing || rich.isFollowing,
                )
            }
        }

        Result.success(finalUsers)
    }

    /**
     * Translates a tweet using X's native internal Strato translate endpoint first,
     * falling back cleanly to open Google Translate `gtx` endpoint when unauthenticated or blocked.
     */
    suspend fun translateTweet(
        account: AccountEntity?,
        tweetId: String,
        originalText: String,
        targetLanguageCode: String,
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        if (account != null && tweetId.all { it.isDigit() }) {
            val xNative = runSuspendCatching {
                val path = "/i/api/1.1/strato/column/None/tweetId=$tweetId,destinationLanguage=None,translationSource=Some(Google),feature=None,timeout=None,onlyCached=None/translation/service/translateTweet"
                val url = "https://x.com$path"
                val txId = txIdProvider.generate("GET", path)
                val req = buildBaseRequest(url, account, txId)
                    .header("x-twitter-client-language", targetLanguageCode)
                    .get()
                    .build()
                fastClient.newCall(req).awaitUse { resp ->
                    if (!resp.isSuccessful) error("X translate HTTP ${resp.code}")
                    val obj = JSONObject(resp.body.string())
                    val translated = obj.optString("translation")
                    if (translated.isBlank()) error("Empty X translation")
                    TranslationResult(
                        translatedText = translated,
                        sourceLanguage = obj.optString("localizedSourceLanguage")
                            .ifBlank { obj.optString("sourceLanguage", "Auto") },
                        provider = "X Translate",
                    )
                }
            }
            if (xNative.isSuccess) return@withContext xNative
        }

        // Open zero-cost translation fallback
        runSuspendCatching {
            val encoded = URLEncoder.encode(originalText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLanguageCode&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", XEndpointRegistry.WEB_USER_AGENT)
                .build()
            fastClient.newCall(req).awaitUse { resp ->
                if (!resp.isSuccessful) error("Translate HTTP ${resp.code}")
                val root = JSONArray(resp.body.string())
                val sentences = root.optJSONArray(0) ?: error("No translation segments")
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    sb.append(sentences.optJSONArray(i)?.optString(0).orEmpty())
                }
                val detected = root.optString(2).ifBlank { "Auto" }
                val finalStr = sb.toString().trim()
                if (finalStr.isBlank()) error("Empty translation")
                TranslationResult(
                    translatedText = finalStr,
                    sourceLanguage = detected.uppercase(),
                    provider = "Auto Translate",
                )
            }
        }
    }

    suspend fun fetchBookmarks(
        account: AccountEntity,
        cursor: String? = null,
        count: Int = 40,
    ): Result<TimelinePage> {
        val vars = JSONObject()
            .put("count", count)
            .put("includePromotedContent", false)
        if (!cursor.isNullOrBlank()) vars.put("cursor", cursor)
        return executeGraphqlGet("Bookmarks", vars, account, useFastClient = true).map { raw ->
            XGraphqlParser.parseTimeline(raw, "X Synced Bookmarks", sortBySortIndexDesc = true)
        }
    }

    /**
     * Fetches live X trends within a strict 6s budget:
     * Races GraphQL `ExploreSidebar` + REST v2 `guide.json` concurrently (cutting 6 sequential
     * 7s fallbacks down to 1 parallel round-trip), with one lightweight `1.1/trends/place.json` fallback.
     */
    suspend fun fetchTrends(
        account: AccountEntity,
        forceNetwork: Boolean = false,
    ): Result<List<TrendItem>> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(6_000L) {
            coroutineScope {
                val sidebarDeferred = async {
                    executeGraphqlGet(
                        operationName = "ExploreSidebar",
                        variables = JSONObject().put("count", 20),
                        account = account,
                        refererOverride = "https://x.com/explore",
                        useFastClient = true,
                        forceNetwork = forceNetwork,
                    ).map { XGraphqlParser.parseTrendsFromGuide(it) }
                }
                val guideDeferred = async {
                    val guideQuery = "include_profile_interstitial_type=1&include_blocking=1&include_blocked_by=1" +
                        "&include_followed_by=1&include_want_retweets=1&include_mute_edge=1&include_can_dm=1" +
                        "&include_can_media_tag=1&skip_status=1&cards_platform=Web-12&include_cards=1" +
                        "&include_ext_alt_text=true&tweet_mode=extended&include_page_configuration=true" +
                        "&initial_tab_id=trending&count=25"
                    val path = "/i/api/2/guide.json"
                    runSuspendCatching {
                        val txId = txIdProvider.generate("GET", path)
                        val reqBuilder = buildBaseRequest(
                            "https://x.com$path?$guideQuery",
                            account,
                            txId,
                            refererOverride = "https://x.com/explore",
                        ).get()
                        if (forceNetwork) reqBuilder.cacheControl(CacheControl.FORCE_NETWORK)
                        fastClient.newCall(reqBuilder.build()).awaitUse { resp ->
                            rateLimitTracker.recordResponseHeaders(account.userId, "guide_trends", resp.headers)
                            if (!resp.isSuccessful) emptyList()
                            else XGraphqlParser.parseTrendsFromGuide(resp.body.string())
                        }
                    }.getOrDefault(emptyList())
                }

                val sidebarRes = sidebarDeferred.await()
                val sidebarItems = sidebarRes.getOrNull().orEmpty()
                if (sidebarItems.isNotEmpty()) {
                    guideDeferred.cancel()
                    return@coroutineScope Result.success(sidebarItems)
                }

                val guideItems = guideDeferred.await()
                if (guideItems.isNotEmpty()) {
                    return@coroutineScope Result.success(guideItems)
                }

                val sidebarErr = sidebarRes.exceptionOrNull()
                if (sidebarErr is XApiException.RateLimited || sidebarErr is XApiException.AuthOrChallengeError) {
                    return@coroutineScope Result.failure(sidebarErr)
                }

                // Single fast fallback: REST v1.1 Worldwide Trends
                val restItems = runSuspendCatching {
                    val path = "/i/api/1.1/trends/place.json"
                    val txId = txIdProvider.generate("GET", path)
                    val reqBuilder = buildBaseRequest(
                        "https://x.com$path?id=1",
                        account,
                        txId,
                        refererOverride = "https://x.com/explore",
                    ).get()
                    if (forceNetwork) reqBuilder.cacheControl(CacheControl.FORCE_NETWORK)
                    fastClient.newCall(reqBuilder.build()).awaitUse { resp ->
                        rateLimitTracker.recordResponseHeaders(account.userId, "trends_place", resp.headers)
                        if (!resp.isSuccessful) emptyList()
                        else XGraphqlParser.parseTrendsRestV1(resp.body.string())
                    }
                }.getOrDefault(emptyList())
                if (restItems.isNotEmpty()) {
                    Result.success(restItems)
                } else {
                    Result.failure(IllegalStateException("X trends endpoints returned empty"))
                }
            }
        } ?: Result.failure(IllegalStateException("X trends lookup timed out"))
    }

    suspend fun fetchNotifications(
        account: AccountEntity,
        mentionsOnly: Boolean,
    ): Result<List<NotificationItem>> = withContext(Dispatchers.IO) {
        val bucket = if (mentionsOnly) "notifications_mentions" else "notifications_all"
        rateLimitTracker.getBlockedUntilEpochMs(account.userId, bucket)?.let { resetAt ->
            return@withContext Result.failure(XApiException.RateLimited(bucket, resetAt))
        }
        runSuspendCatching {
            val endpoint = if (mentionsOnly) "mentions.json" else "all.json"
            val path = "/i/api/2/notifications/$endpoint"
            val url = "https://x.com$path?include_profile_interstitial_type=1&include_blocking=1&include_blocked_by=1&include_followed_by=1&include_want_retweets=1&include_mute_edge=1&include_can_dm=1&include_can_media_tag=1&skip_status=1&cards_platform=Web-12&include_cards=1&include_ext_alt_text=true&tweet_mode=extended&count=30"
            val txId = txIdProvider.generate("GET", path)
            val req = buildBaseRequest(url, account, txId).get().build()
            fastClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, bucket, resp.headers)
                val body = resp.body.string()
                XErrorClassifier.classifyResponse(bucket, resp.code, body, resp.headers, requireUsableData = false)
                    ?.let { err ->
                        if (err is XApiException.RateLimited) {
                            rateLimitTracker.markExhausted(account.userId, bucket, err.resetAtEpochMs)
                        }
                        throw err
                    }
                XGraphqlParser.parseNotificationsV2(body)
            }
        }
    }

    suspend fun setLike(account: AccountEntity, tweetId: String, like: Boolean): Result<Unit> {
        val op = if (like) "FavoriteTweet" else "UnfavoriteTweet"
        val vars = JSONObject().put("tweet_id", tweetId)
        return executeGraphqlPost(op, vars, account).map {}
    }

    suspend fun setRepost(account: AccountEntity, tweetId: String, repost: Boolean): Result<Unit> {
        val op = if (repost) "CreateRetweet" else "DeleteRetweet"
        val key = if (repost) "tweet_id" else "source_tweet_id"
        val vars = JSONObject().put(key, tweetId).put("dark_request", false)
        return executeGraphqlPost(op, vars, account).map {}
    }

    suspend fun setBookmark(account: AccountEntity, tweetId: String, bookmark: Boolean): Result<Unit> {
        val op = if (bookmark) "CreateBookmark" else "DeleteBookmark"
        val vars = JSONObject().put("tweet_id", tweetId)
        return executeGraphqlPost(op, vars, account).map {}
    }

    suspend fun createTweet(
        account: AccountEntity,
        text: String,
        replyToTweetId: String? = null,
        quoteTweetUrl: String? = null,
        mediaIds: List<String> = emptyList(),
    ): Result<Tweet?> {
        val mediaEntities = JSONArray().apply {
            mediaIds.forEach { mid ->
                put(JSONObject().put("media_id", mid).put("tagged_users", JSONArray()))
            }
        }
        val vars = JSONObject()
            .put("tweet_text", text)
            .put("dark_request", false)
            .put("media", JSONObject().put("media_entities", mediaEntities).put("possibly_sensitive", false))
            .put("semantic_annotation_ids", JSONArray())

        if (!replyToTweetId.isNullOrBlank()) {
            vars.put(
                "reply",
                JSONObject()
                    .put("in_reply_to_tweet_id", replyToTweetId)
                    .put("exclude_reply_user_ids", JSONArray())
            )
        }
        if (!quoteTweetUrl.isNullOrBlank()) {
            vars.put("attachment_url", quoteTweetUrl)
        }

        return executeGraphqlPost("CreateTweet", vars, account).map { raw ->
            XGraphqlParser.parseCreatedTweet(raw)
        }
    }

    suspend fun deleteTweet(account: AccountEntity, tweetId: String): Result<Unit> {
        val vars = JSONObject().put("tweet_id", tweetId).put("dark_request", false)
        return executeGraphqlPost("DeleteTweet", vars, account).map {}
    }

    suspend fun setFollow(account: AccountEntity, targetUserId: String, follow: Boolean): Result<Unit> =
        postRestForm(
            account = account,
            path = if (follow) "/i/api/1.1/friendships/create.json" else "/i/api/1.1/friendships/destroy.json",
            params = mapOf("user_id" to targetUserId),
        )

    private fun moderationTargetParams(targetUserId: String, handle: String): Map<String, String> {
        val cleanId = targetUserId.trim()
        val cleanHandle = handle.trim().removePrefix("@")
        return when {
            cleanId.isNotBlank() && cleanId.all(Char::isDigit) -> mapOf("user_id" to cleanId)
            cleanHandle.isNotBlank() -> mapOf("screen_name" to cleanHandle)
            else -> mapOf("user_id" to cleanId)
        }
    }

    suspend fun setMuteUser(
        account: AccountEntity,
        targetUserId: String,
        handle: String = "",
        mute: Boolean,
    ): Result<Unit> = postRestForm(
        account = account,
        path = if (mute) "/i/api/1.1/mutes/users/create.json" else "/i/api/1.1/mutes/users/destroy.json",
        params = moderationTargetParams(targetUserId, handle),
    )

    suspend fun setBlockUser(
        account: AccountEntity,
        targetUserId: String,
        handle: String = "",
        block: Boolean,
    ): Result<Unit> = postRestForm(
        account = account,
        path = if (block) "/i/api/1.1/blocks/create.json" else "/i/api/1.1/blocks/destroy.json",
        params = moderationTargetParams(targetUserId, handle),
    )

    suspend fun votePoll(
        account: AccountEntity,
        tweetId: String,
        cardUri: String,
        choiceIndex: Int,
        choiceCount: Int = 4,
        cardNameOverride: String = "",
    ): Result<String> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = "https://caps.x.com/v2/capi/passthrough/1"
            val txId = txIdProvider.generate("POST", "/v2/capi/passthrough/1")
            val cardName = cardNameOverride.ifBlank {
                "poll${choiceCount.coerceIn(2, 4)}choice_text_only"
            }
            val body = FormBody.Builder()
                .add("twitter:string:card_uri", cardUri)
                .add("twitter:long:original_tweet_id", tweetId)
                .add("twitter:string:response_card_name", cardName)
                .add("twitter:string:cards_platform", "Web-12")
                .add("twitter:string:selected_choice", choiceIndex.toString())
                .build()
            val req = buildBaseRequest(url, account, txId).post(body).build()
            okHttpClient.newCall(req).awaitUse { resp ->
                if (!resp.isSuccessful) error("Poll vote HTTP ${resp.code}")
                resp.body.string()
            }
        }
    }

    suspend fun uploadImageBytes(
        account: AccountEntity,
        bytes: ByteArray,
        mimeType: String = "image/jpeg",
    ): Result<String> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val uploadUrl = "https://upload.x.com/i/media/upload.json"
            val initBody = FormBody.Builder()
                .add("command", "INIT")
                .add("total_bytes", bytes.size.toString())
                .add("media_type", mimeType)
                .add("media_category", "tweet_image")
                .build()
            val initReq = buildBaseRequest(uploadUrl, account, txIdProvider.generate("POST", "/i/media/upload.json"))
                .post(initBody).build()
            val mediaId = okHttpClient.newCall(initReq).awaitUse { resp ->
                if (!resp.isSuccessful) error("Media INIT failed (${resp.code})")
                JSONObject(resp.body.string()).optString("media_id_string")
            }
            if (mediaId.isBlank()) error("Media INIT returned empty media_id")

            val appendBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("command", "APPEND")
                .addFormDataPart("media_id", mediaId)
                .addFormDataPart("segment_index", "0")
                .addFormDataPart("media", "upload.jpg", bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            val appendReq = buildBaseRequest(uploadUrl, account, txIdProvider.generate("POST", "/i/media/upload.json"))
                .post(appendBody).build()
            okHttpClient.newCall(appendReq).awaitUse { resp ->
                if (!resp.isSuccessful) error("Media APPEND failed (${resp.code})")
            }

            val finBody = FormBody.Builder()
                .add("command", "FINALIZE")
                .add("media_id", mediaId)
                .build()
            val finReq = buildBaseRequest(uploadUrl, account, txIdProvider.generate("POST", "/i/media/upload.json"))
                .post(finBody).build()
            okHttpClient.newCall(finReq).awaitUse { resp ->
                if (!resp.isSuccessful) error("Media FINALIZE failed (${resp.code})")
            }
            mediaId
        }
    }

    private suspend fun postRestForm(
        account: AccountEntity,
        path: String,
        params: Map<String, String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = "https://x.com$path"
            val txId = txIdProvider.generate("POST", path)
            val bodyBuilder = FormBody.Builder()
            params.forEach { (k, v) -> bodyBuilder.add(k, v) }
            val req = buildBaseRequest(url, account, txId).post(bodyBuilder.build()).build()
            okHttpClient.newCall(req).awaitUse { resp ->
                rateLimitTracker.recordResponseHeaders(account.userId, path, resp.headers)
                if (!resp.isSuccessful) error("Request $path failed (${resp.code})")
            }
        }
    }

    /**
     * Executes an authenticated X GraphQL GET query with:
     * - 0ms per-(account, endpoint) rate-limit short-circuit
     * - Coroutine-cancellable OkHttp I/O
     * - Exact X error classification (rate limits, auth/challenges, transient LoadShed, code 336, 404)
     * - Targeted sub-second repair ONLY (code 336 `<1ms` flag merge; 404 single txid refresh capped at 2.5s)
     * - NEVER blocks the interactive request path on webpack/manifest scraping (schedules silent background sync instead).
     */
    private suspend fun executeGraphqlGet(
        operationName: String,
        variables: JSONObject,
        account: AccountEntity,
        txIdRefreshed: Boolean = false,
        featurePatched: Boolean = false,
        transientRetries: Int = 0,
        refererOverride: String? = null,
        useFastClient: Boolean = false,
        forceNetwork: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        rateLimitTracker.getBlockedUntilEpochMs(account.userId, operationName)?.let { resetAt ->
            return@withContext Result.failure(XApiException.RateLimited(operationName, resetAt))
        }

        val resolved = registry.resolve(operationName)
        val path = "/i/api/graphql/${resolved.queryId}/${resolved.operationName}"
        val urlBuilder = "https://x.com$path".toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("features", resolved.featuresJson)
        XEndpointRegistry.fieldTogglesFor(operationName)?.let { toggles ->
            urlBuilder.addQueryParameter("fieldToggles", toggles)
        }
        val httpUrl = urlBuilder.build()

        val txId = txIdProvider.generate("GET", path)
        val reqBuilder = buildBaseRequest(httpUrl.toString(), account, txId, refererOverride).get()
        if (forceNetwork) reqBuilder.cacheControl(CacheControl.FORCE_NETWORK)
        val req = reqBuilder.build()
        val activeClient = if (useFastClient) fastClient else okHttpClient

        val responseTriple = runSuspendCatching {
            activeClient.newCall(req).awaitUse { resp ->
                Triple(resp.code, resp.body.string(), resp.headers)
            }
        }
        val (code, body, headers) = responseTriple.getOrElse { return@withContext Result.failure(it) }
        rateLimitTracker.recordResponseHeaders(account.userId, operationName, headers)

        val classified = XErrorClassifier.classifyResponse(
            operationName = operationName,
            httpCode = code,
            body = body,
            headers = headers,
            requireUsableData = true,
        ) ?: return@withContext Result.success(body)

        when (classified) {
            is XApiException.RateLimited -> {
                rateLimitTracker.markExhausted(account.userId, operationName, classified.resetAtEpochMs)
                Result.failure(classified)
            }

            is XApiException.AuthOrChallengeError -> Result.failure(classified)

            is XApiException.MissingFeatureFlags -> {
                if (!featurePatched && classified.missingFlags.isNotEmpty() &&
                    registry.patchMissingFeatures(operationName, classified.missingFlags)
                ) {
                    executeGraphqlGet(
                        operationName = operationName,
                        variables = variables,
                        account = account,
                        txIdRefreshed = txIdRefreshed,
                        featurePatched = true,
                        transientRetries = transientRetries,
                        refererOverride = refererOverride,
                        useFastClient = useFastClient,
                        forceNetwork = forceNetwork,
                    )
                } else {
                    registry.scheduleBackgroundRefresh()
                    Result.failure(classified)
                }
            }

            is XApiException.TransientServerError -> {
                if (transientRetries < 1) {
                    delay(450L)
                    executeGraphqlGet(
                        operationName = operationName,
                        variables = variables,
                        account = account,
                        txIdRefreshed = txIdRefreshed,
                        featurePatched = featurePatched,
                        transientRetries = transientRetries + 1,
                        refererOverride = refererOverride,
                        useFastClient = useFastClient,
                        forceNetwork = forceNetwork,
                    )
                } else {
                    Result.failure(classified)
                }
            }

            is XApiException.NotFoundOrInvalidTxId -> {
                if (!txIdRefreshed && txIdProvider.forceRefresh(timeoutMs = 2_500L)) {
                    executeGraphqlGet(
                        operationName = operationName,
                        variables = variables,
                        account = account,
                        txIdRefreshed = true,
                        featurePatched = featurePatched,
                        transientRetries = transientRetries,
                        refererOverride = refererOverride,
                        useFastClient = useFastClient,
                        forceNetwork = forceNetwork,
                    )
                } else {
                    // Persistent 404 after fresh txid: trigger out-of-band background hash sync, never block UI
                    registry.scheduleBackgroundRefresh()
                    Result.failure(classified)
                }
            }

            is XApiException.GraphqlError -> Result.failure(classified)
        }
    }

    private suspend fun executeGraphqlPost(
        operationName: String,
        variables: JSONObject,
        account: AccountEntity,
        txIdRefreshed: Boolean = false,
        featurePatched: Boolean = false,
        transientRetries: Int = 0,
        refererOverride: String? = null,
        validateData: Boolean = false,
        useFastClient: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        rateLimitTracker.getBlockedUntilEpochMs(account.userId, operationName)?.let { resetAt ->
            return@withContext Result.failure(XApiException.RateLimited(operationName, resetAt))
        }

        val resolved = registry.resolve(operationName)
        val path = "/i/api/graphql/${resolved.queryId}/${resolved.operationName}"
        val url = "https://x.com$path"
        val featuresObj = runCatching { JSONObject(resolved.featuresJson) }.getOrElse { JSONObject() }
        val payload = JSONObject()
            .put("variables", variables)
            .put("features", featuresObj)
            .put("queryId", resolved.queryId)

        val txId = txIdProvider.generate("POST", path)
        val req = buildBaseRequest(url, account, txId, refererOverride)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        val activeClient = if (useFastClient) fastClient else okHttpClient

        val responseTriple = runSuspendCatching {
            activeClient.newCall(req).awaitUse { resp ->
                Triple(resp.code, resp.body.string(), resp.headers)
            }
        }
        val (code, body, headers) = responseTriple.getOrElse { return@withContext Result.failure(it) }
        rateLimitTracker.recordResponseHeaders(account.userId, operationName, headers)

        val classified = XErrorClassifier.classifyResponse(
            operationName = operationName,
            httpCode = code,
            body = body,
            headers = headers,
            requireUsableData = validateData,
        ) ?: return@withContext Result.success(body)

        when (classified) {
            is XApiException.RateLimited -> {
                rateLimitTracker.markExhausted(account.userId, operationName, classified.resetAtEpochMs)
                Result.failure(classified)
            }

            is XApiException.AuthOrChallengeError -> Result.failure(classified)

            is XApiException.MissingFeatureFlags -> {
                if (!featurePatched && classified.missingFlags.isNotEmpty() &&
                    registry.patchMissingFeatures(operationName, classified.missingFlags)
                ) {
                    executeGraphqlPost(
                        operationName = operationName,
                        variables = variables,
                        account = account,
                        txIdRefreshed = txIdRefreshed,
                        featurePatched = true,
                        transientRetries = transientRetries,
                        refererOverride = refererOverride,
                        validateData = validateData,
                        useFastClient = useFastClient,
                    )
                } else {
                    registry.scheduleBackgroundRefresh()
                    Result.failure(classified)
                }
            }

            is XApiException.TransientServerError -> {
                if (transientRetries < 1) {
                    delay(450L)
                    executeGraphqlPost(
                        operationName = operationName,
                        variables = variables,
                        account = account,
                        txIdRefreshed = txIdRefreshed,
                        featurePatched = featurePatched,
                        transientRetries = transientRetries + 1,
                        refererOverride = refererOverride,
                        validateData = validateData,
                        useFastClient = useFastClient,
                    )
                } else {
                    Result.failure(classified)
                }
            }

            is XApiException.NotFoundOrInvalidTxId -> {
                if (!txIdRefreshed && txIdProvider.forceRefresh(timeoutMs = 2_500L)) {
                    executeGraphqlPost(
                        operationName = operationName,
                        variables = variables,
                        account = account,
                        txIdRefreshed = true,
                        featurePatched = featurePatched,
                        transientRetries = transientRetries,
                        refererOverride = refererOverride,
                        validateData = validateData,
                        useFastClient = useFastClient,
                    )
                } else {
                    registry.scheduleBackgroundRefresh()
                    Result.failure(classified)
                }
            }

            is XApiException.GraphqlError -> Result.failure(classified)
        }
    }

    private fun buildBaseRequest(
        url: String,
        account: AccountEntity,
        txId: String,
        refererOverride: String? = null,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${XEndpointRegistry.WEB_BEARER_TOKEN}")
        .header("Cookie", "auth_token=${account.authToken}; ct0=${account.ct0}")
        .header("x-csrf-token", account.ct0)
        .header("x-twitter-auth-type", "OAuth2Session")
        .header("x-twitter-active-user", "yes")
        .header("x-twitter-client-language", "en")
        .header("x-client-transaction-id", txId)
        .header("User-Agent", XEndpointRegistry.WEB_USER_AGENT)
        .header("Referer", refererOverride ?: "https://x.com/home")
        .header("Origin", "https://x.com")
        .header("Accept", "*/*")
}
