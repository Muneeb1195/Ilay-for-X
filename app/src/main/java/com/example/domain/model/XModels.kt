package com.example.domain.model

import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonClass

@Immutable
@JsonClass(generateAdapter = true)
data class XUser(
    val id: String,
    val handle: String,
    val displayName: String,
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val bio: String = "",
    val location: String = "",
    val websiteUrl: String = "",
    val isVerified: Boolean = false,
    val followersCount: Long = 0L,
    val followingCount: Long = 0L,
    val tweetsCount: Long = 0L,
    val joinedLabel: String = "",
    val joinedAtEpochMs: Long = 0L,
    val isFollowing: Boolean = false,
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false,
)

enum class MediaType { PHOTO, VIDEO, GIF }

@Immutable
@JsonClass(generateAdapter = true)
data class MediaAttachment(
    val id: String,
    val type: MediaType = MediaType.PHOTO,
    val url: String,
    val thumbnailUrl: String = url,
    val videoUrl: String? = null,
    val dataSaverVideoUrl: String? = null,
    val width: Int = 1200,
    val height: Int = 675,
    val altText: String = "",
    val durationMs: Long = 0L,
)

@Immutable
@JsonClass(generateAdapter = true)
data class PollChoice(
    val index: Int,
    val label: String,
    val votes: Long,
)

@Immutable
@JsonClass(generateAdapter = true)
data class Poll(
    val cardUri: String,
    val choices: List<PollChoice>,
    val totalVotes: Long,
    val endsAtEpochMs: Long = 0L,
    val isFinal: Boolean = false,
    val votedChoiceIndex: Int? = null,
    val cardName: String = "",
) {
    val hasVoted: Boolean
        get() = votedChoiceIndex != null

    fun isClosedAt(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        isFinal || (endsAtEpochMs > 0L && endsAtEpochMs <= nowEpochMs)

    fun showResultsAt(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        isClosedAt(nowEpochMs) || hasVoted

    fun fractionFor(choice: PollChoice): Float {
        if (totalVotes <= 0L) return 0f
        return (choice.votes.toFloat() / totalVotes.toFloat()).coerceIn(0f, 1f)
    }

    fun percentageFor(choice: PollChoice): Int =
        kotlin.math.round(fractionFor(choice) * 100f).toInt().coerceIn(0, 100)

    fun withLocalVote(choiceIndex: Int): Poll {
        if (votedChoiceIndex != null || choices.none { it.index == choiceIndex }) return this
        val updatedChoices = choices.map { choice ->
            if (choice.index == choiceIndex) choice.copy(votes = choice.votes + 1L) else choice
        }
        val recomputedTotal = maxOf(totalVotes + 1L, updatedChoices.sumOf { it.votes })
        return copy(
            choices = updatedChoices,
            totalVotes = recomputedTotal,
            votedChoiceIndex = choiceIndex,
        )
    }
}

@Immutable
@JsonClass(generateAdapter = true)
data class TweetMetrics(
    val replies: Long = 0L,
    val reposts: Long = 0L,
    val quotes: Long = 0L,
    val likes: Long = 0L,
    val bookmarks: Long = 0L,
    val views: Long = 0L,
)

@Immutable
@JsonClass(generateAdapter = true)
data class ViewerState(
    val isLiked: Boolean = false,
    val isReposted: Boolean = false,
    val isBookmarked: Boolean = false,
)

@Immutable
@JsonClass(generateAdapter = true)
data class QuotedTweet(
    val id: String,
    val conversationId: String = id,
    val author: XUser,
    val text: String,
    val createdAtEpochMs: Long,
    val media: List<MediaAttachment> = emptyList(),
    val poll: Poll? = null,
    val linkPreview: LinkPreview? = null,
    val metrics: TweetMetrics = TweetMetrics(),
    val isSensitive: Boolean = false,
    val lang: String = "",
    val isUnavailable: Boolean = false,
    val unavailableReason: String = "",
) {
    fun toTweet(): Tweet = Tweet(
        id = id,
        conversationId = conversationId.ifBlank { id },
        author = author,
        text = text,
        createdAtEpochMs = createdAtEpochMs,
        media = media,
        poll = poll,
        linkPreview = linkPreview,
        metrics = metrics,
        isSensitive = isSensitive,
        lang = lang,
    )

    companion object {
        fun fromTweet(tweet: Tweet): QuotedTweet = QuotedTweet(
            id = tweet.id,
            conversationId = tweet.conversationId,
            author = tweet.author,
            text = tweet.text,
            createdAtEpochMs = tweet.createdAtEpochMs,
            media = tweet.media,
            poll = tweet.poll,
            linkPreview = tweet.linkPreview,
            metrics = tweet.metrics,
            isSensitive = tweet.isSensitive,
            lang = tweet.lang,
        )

        fun unavailable(id: String, reason: String = "This post is unavailable."): QuotedTweet = QuotedTweet(
            id = id,
            author = XUser(id = "", handle = "", displayName = ""),
            text = "",
            createdAtEpochMs = 0L,
            isUnavailable = true,
            unavailableReason = reason.ifBlank { "This post is unavailable." },
        )
    }
}

@Immutable
@JsonClass(generateAdapter = true)
data class LinkPreview(
    val url: String,
    val displayUrl: String = "",
    val domain: String = "",
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
)

@Immutable
@JsonClass(generateAdapter = true)
data class ArticleBlock(
    val type: String = "paragraph", // "header-one", "header-two", "header-three", "blockquote", "unordered-list-item", "ordered-list-item", "code-block", "media", "divider", "unstyled"
    val text: String = "",
    val imageUrl: String = "",
    val caption: String = "",
    val linkUrl: String = "",
)

@Immutable
@JsonClass(generateAdapter = true)
data class XArticle(
    val id: String,
    val title: String,
    val previewText: String = "",
    val bodyBlocks: List<ArticleBlock> = emptyList(),
    val coverImageUrl: String = "",
    val readTimeMinutes: Int = 1,
    val publishedAtEpochMs: Long = 0L,
    val articleUrl: String = "",
)

@Immutable
@JsonClass(generateAdapter = true)
data class Tweet(
    val id: String,
    val conversationId: String = id,
    val author: XUser,
    val text: String,
    val createdAtEpochMs: Long,
    val media: List<MediaAttachment> = emptyList(),
    val poll: Poll? = null,
    val quotedTweet: QuotedTweet? = null,
    val quotedTweetId: String? = null,
    val linkPreview: LinkPreview? = null,
    val article: XArticle? = null,
    val repostedBy: String? = null,
    val replyToHandle: String? = null,
    val replyToTweetId: String? = null,
    val metrics: TweetMetrics = TweetMetrics(),
    val viewerState: ViewerState = ViewerState(),
    val isSensitive: Boolean = false,
    val isPromoted: Boolean = false,
    val lang: String = "",
    val sourceLabel: String = "",
    val localRankScore: Double = 0.0,
    val localRankReason: String = "",
) {
    val isQuotePost: Boolean
        get() = quotedTweet != null || !quotedTweetId.isNullOrBlank()
}

enum class FeedType(val label: String) {
    FOR_YOU("For You"),
    FOLLOWING("Following"),
}

enum class FeedDensity(val label: String) {
    CARD("Expressive Card"),
    COMPACT("Compact List"),
}

enum class ImageQualityMode(
    val label: String,
    val description: String,
) {
    AUTO(
        label = "Adaptive",
        description = "Adapts timeline previews to Wi-Fi or cellular; loads full resolution only when expanded",
    ),
    DATA_SAVER(
        label = "Data Saver",
        description = "Lightweight 360px timeline previews; expanded photos and videos cap at 1080p max",
    ),
    HIGH(
        label = "High",
        description = "Loads crisper timeline previews on Wi-Fi; original full resolution when expanded",
    ),
}

enum class SwipeAction(val label: String) {
    LIKE("Like"),
    REPOST("Repost"),
    BOOKMARK("Bookmark"),
    REPLY("Reply"),
    NONE("Disabled"),
}

enum class NotificationType(val label: String) {
    MENTION("Mentioned you"),
    REPLY("Replied to you"),
    LIKE("Liked your post"),
    REPOST("Reposted your post"),
    FOLLOW("Followed you"),
    QUOTE("Quoted your post"),
}

@Immutable
data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val message: String,
    val actor: XUser,
    val targetTweet: Tweet? = null,
    val timestampEpochMs: Long,
    val isRead: Boolean = false,
)

@Immutable
@JsonClass(generateAdapter = true)
data class TrendItem(
    val id: String,
    val name: String,
    val category: String = "Trending",
    val tweetVolumeLabel: String = "",
    val query: String = name,
)

@Immutable
@JsonClass(generateAdapter = true)
data class TrendsResult(
    val items: List<TrendItem>,
    val sourceLabel: String = "",
    val fetchedAtEpochMs: Long = System.currentTimeMillis(),
) {
    /**
     * Returns true when cached trends are empty, missing a valid timestamp,
     * older than [maxAgeMs] (default: 60 minutes), or have a future timestamp from clock skew.
     */
    fun isStale(
        nowEpochMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = MAX_CACHE_AGE_MS,
    ): Boolean {
        if (items.isEmpty() || fetchedAtEpochMs <= 0L) return true
        val ageMs = nowEpochMs - fetchedAtEpochMs
        return ageMs < 0L || ageMs >= maxAgeMs
    }

    companion object {
        const val MAX_CACHE_AGE_MS: Long = 60L * 60_000L
    }
}

enum class SearchTab(val label: String, val product: String) {
    TOP("Top", "Top"),
    LATEST("Latest", "Latest"),
    PEOPLE("People", "People"),
    MEDIA("Media", "Media"),
}

@Immutable
data class UniversalSearchSuggestions(
    val hashtags: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val users: List<XUser> = emptyList(),
)

enum class ProfileTab(val label: String) {
    POSTS("Posts"),
    REPLIES("Replies"),
    ARTICLES("Articles"),
    MEDIA("Media"),
    LIKES("Likes"),
    BOOKMARKS("Bookmarks"),
}

@Immutable
data class ThreadConversation(
    val ancestors: List<Tweet>,
    val focalTweet: Tweet,
    val replies: List<Tweet>,
    val bottomCursor: String? = null,
)

@Immutable
data class TimelinePage(
    val tweets: List<Tweet>,
    val nextCursor: String? = null,
    val isFromPublicFallback: Boolean = false,
    val sourceInfo: String = "",
)
