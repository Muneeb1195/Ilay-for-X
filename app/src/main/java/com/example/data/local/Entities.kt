package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val userId: String,
    val handle: String,
    val displayName: String,
    val avatarUrl: String,
    val bannerUrl: String,
    val authToken: String,
    val ct0: String,
    val isActive: Boolean = false,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "cached_tweets",
    indices = [
        Index(value = ["feedBucket", "createdAtEpochMs"]),
        Index(value = ["tweetId"]),
    ],
)
data class CachedTweetEntity(
    @PrimaryKey val compositeKey: String,
    val tweetId: String,
    val feedBucket: String,
    val authorHandle: String,
    val tweetJson: String,
    val createdAtEpochMs: Long,
    val cachedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["savedAtEpochMs"]),
        Index(value = ["folderTag", "savedAtEpochMs"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey val tweetId: String,
    val authorHandle: String,
    val authorName: String,
    val textSearchable: String,
    val tweetJson: String,
    val folderTag: String = "Saved",
    val isSyncedWithX: Boolean = false,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "author_affinities")
data class AuthorAffinityEntity(
    @PrimaryKey val handleLower: String,
    val displayName: String = "",
    val likesGiven: Int = 0,
    val repostsGiven: Int = 0,
    val repliesGiven: Int = 0,
    val bookmarksGiven: Int = 0,
    val profileOpens: Int = 0,
    val isPinnedBoost: Boolean = false,
    val lastInteractedEpochMs: Long = System.currentTimeMillis(),
    val threadOpens: Int = 0,
    val totalDwellSeconds: Long = 0L,
    val skipsCount: Int = 0,
    val notInterestedCount: Int = 0,
    val isSoftDemoted: Boolean = false,
    val isRiskyOrNsfw: Boolean = false,
)

@Entity(
    tableName = "seen_tweets",
    indices = [
        Index(value = ["lastSeenEpochMs"]),
        Index(value = ["isDismissed"]),
    ],
)
data class SeenTweetEntity(
    @PrimaryKey val tweetId: String,
    val authorHandleLower: String = "",
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val viewCount: Int = 1,
    val totalDwellMs: Long = 0L,
    val wasInteracted: Boolean = false,
    val wasSkipped: Boolean = false,
    val isDismissed: Boolean = false,
)

object TopicKind {
    const val HASHTAG = "HASHTAG"
    const val KEYWORD = "KEYWORD"
    const val DOMAIN = "DOMAIN"
}

object TopicManualState {
    const val AUTO = "AUTO"
    const val BOOSTED = "BOOSTED"
    const val DEMOTED = "DEMOTED"
    const val BLOCKED = "BLOCKED"
}

@Entity(
    tableName = "topic_interests",
    indices = [
        Index(value = ["manualState"]),
        Index(value = ["lastUpdatedEpochMs"]),
    ],
)
data class TopicInterestEntity(
    @PrimaryKey val tokenLower: String,
    val displayLabel: String,
    val kind: String = TopicKind.KEYWORD,
    val positiveScore: Double = 0.0,
    val negativeScore: Double = 0.0,
    val manualState: String = TopicManualState.AUTO,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "muted_rules")
data class MutedRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val value: String,
    val isHandle: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "draft_tweets")
data class DraftTweetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    val replyToTweetId: String? = null,
    val replyToHandle: String? = null,
    val quoteTweetId: String? = null,
    val quoteTweetUrl: String? = null,
    val mediaUrisCsv: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "endpoint_hashes")
data class EndpointHashEntity(
    @PrimaryKey val operationName: String,
    val queryId: String,
    val featuresJson: String,
    val source: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "cached_profiles",
    indices = [
        Index(value = ["userId"]),
    ],
)
data class CachedProfileEntity(
    @PrimaryKey val handleLower: String,
    val userId: String,
    val userJson: String,
    val cachedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "user_moderations",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["isMuted"]),
        Index(value = ["isBlocked"]),
    ],
)
data class UserModerationEntity(
    @PrimaryKey val handleLower: String,
    val userId: String = "",
    val displayName: String = "",
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)
