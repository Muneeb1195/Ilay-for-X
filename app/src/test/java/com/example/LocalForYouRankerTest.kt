package com.example

import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.UserModerationEntity
import com.example.domain.model.QuotedTweet
import com.example.domain.model.Tweet
import com.example.domain.model.TweetMetrics
import com.example.domain.model.XUser
import com.example.domain.ranking.LocalForYouRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalForYouRankerTest {

    private val ranker = LocalForYouRanker()
    private val now = 1_760_000_000_000L

    private fun tweet(
        id: String,
        handle: String,
        text: String = "Clean engineering update about Android architecture.",
        ageMinutes: Long = 30L,
        metrics: TweetMetrics = TweetMetrics(likes = 50, reposts = 5, replies = 4, bookmarks = 8, views = 2_000),
        isSensitive: Boolean = false,
        replyToHandle: String? = null,
        repostedBy: String? = null,
        quotedTweet: QuotedTweet? = null,
        authorId: String = "u_$handle",
    ): Tweet = Tweet(
        id = id,
        conversationId = "conv_$id",
        author = XUser(id = authorId, handle = handle, displayName = handle),
        text = text,
        createdAtEpochMs = now - ageMinutes * 60_000L,
        metrics = metrics,
        isSensitive = isSensitive,
        replyToHandle = replyToHandle,
        repostedBy = repostedBy,
        quotedTweet = quotedTweet,
    )

    @Test
    fun `recent affinity decays stale interactions while honoring bookmarks interest profile`() {
        val recentAuthor = AuthorAffinityEntity(
            handleLower = "recent_dev",
            displayName = "Recent Dev",
            likesGiven = 8,
            repliesGiven = 3,
            lastInteractedEpochMs = now - 2 * 3_600_000L,
        )
        val staleAuthor = AuthorAffinityEntity(
            handleLower = "stale_dev",
            displayName = "Stale Dev",
            likesGiven = 8,
            repliesGiven = 3,
            lastInteractedEpochMs = now - 90L * 86_400_000L,
        )

        val ranked = ranker.rankForYou(
            tweets = listOf(
                tweet("stale", "stale_dev", text = "General status update"),
                tweet("recent", "recent_dev", text = "General status update"),
            ),
            affinities = listOf(recentAuthor, staleAuthor),
            mutedRules = emptyList(),
            prefs = AppPreferences(),
            nowEpochMs = now,
        )

        assertEquals("recent", ranked.first().id)
        assertTrue(ranked[0].localRankScore > ranked[1].localRankScore)
    }

    @Test
    fun `on-device bookmark and search interests boost matching topics`() {
        val ranked = ranker.rankForYou(
            tweets = listOf(
                tweet("offtopic", "alice", text = "Random lunch photo from downtown cafe."),
                tweet("ontopic", "bob", text = "Deep dive into #Kotlin coroutines and Jetpack Compose benchmarks."),
            ),
            affinities = emptyList(),
            mutedRules = emptyList(),
            prefs = AppPreferences(),
            nowEpochMs = now,
            bookmarkedTexts = listOf(
                "Best practices for #Kotlin coroutines and structured concurrency",
                "Optimizing Jetpack Compose LazyColumn performance",
            ),
            recentSearchQueries = listOf("#Kotlin", "Jetpack Compose"),
        )

        assertEquals("ontopic", ranked.first().id)
        assertEquals("Matches your interests", ranked.first().localRankReason)
    }

    @Test
    fun `penalizes engagement bait and hashtag stuffing compared to high-save substantive post`() {
        val spammy = tweet(
            id = "spam",
            handle = "growth_bot",
            text = "RT TO WIN! FOLLOW + RETWEET AND DROP YOUR WALLET #crypto #giveaway #airdrop #free #win #promo",
            metrics = TweetMetrics(likes = 120, reposts = 90, replies = 10, bookmarks = 1, views = 50_000),
        )
        val highSave = tweet(
            id = "quality",
            handle = "systems_eng",
            text = "Here is a complete breakdown of SQLite WAL mode checkpoint tuning on Android devices, including latency percentiles, write amplification trade-offs, and practical Room configuration tips that reduced our cold-start jank by 42 percent across mid-range phones.",
            metrics = TweetMetrics(likes = 120, reposts = 25, replies = 14, bookmarks = 68, views = 4_000),
        )

        val ranked = ranker.rankForYou(
            tweets = listOf(spammy, highSave),
            affinities = emptyList(),
            mutedRules = emptyList(),
            prefs = AppPreferences(),
            nowEpochMs = now,
        )

        assertEquals("quality", ranked.first().id)
        assertTrue(ranked[0].localRankScore > ranked[1].localRankScore)
    }

    @Test
    fun `spreads adjacent authors so one creator does not dominate consecutive top slots`() {
        val tweets = listOf(
            tweet("a1", "prolific", ageMinutes = 5),
            tweet("a2", "prolific", ageMinutes = 8),
            tweet("a3", "prolific", ageMinutes = 10),
            tweet("b1", "other_creator", ageMinutes = 12),
        )

        val ranked = ranker.rankForYou(
            tweets = tweets,
            affinities = emptyList(),
            mutedRules = emptyList(),
            prefs = AppPreferences(),
            nowEpochMs = now,
        )

        assertEquals(4, ranked.size)
        assertNotEquals(ranked[0].author.handle, ranked[1].author.handle)
    }

    @Test
    fun `excludes muted and blocked users across posts, reposts, quoted tweets, and replies to blocked accounts`() {
        val moderations = listOf(
            UserModerationEntity(handleLower = "noisy_user", userId = "101", isMuted = true, isBlocked = false),
            UserModerationEntity(handleLower = "toxic_user", userId = "202", isMuted = false, isBlocked = true),
        )

        val tweets = listOf(
            tweet("t_muted_direct", "noisy_user", authorId = "101"),
            tweet("t_blocked_direct", "toxic_user", authorId = "202"),
            tweet("t_blocked_by_id", "renamed_handle", authorId = "202"),
            tweet("t_reposted_by_muted", "innocent_author", repostedBy = "noisy_user"),
            tweet(
                id = "t_quoting_blocked",
                handle = "innocent_author",
                quotedTweet = QuotedTweet(
                    id = "q1",
                    author = XUser(id = "202", handle = "toxic_user", displayName = "Toxic"),
                    text = "Original take",
                    createdAtEpochMs = now - 10 * 60_000L,
                ),
            ),
            tweet("t_reply_to_blocked", "innocent_author", replyToHandle = "toxic_user"),
            tweet("t_clean", "clean_creator", text = "Fresh Kotlin Multiplatform benchmarks."),
        )

        val ranked = ranker.rankForYou(
            tweets = tweets,
            affinities = emptyList(),
            mutedRules = emptyList(),
            prefs = AppPreferences(),
            nowEpochMs = now,
            moderations = moderations,
        )

        assertEquals(listOf("t_clean"), ranked.map { it.id })
    }
}
