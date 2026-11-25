package com.example

import android.database.sqlite.SQLiteDatabase
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.IlayDatabase
import com.example.data.local.SeenTweetEntity
import com.example.data.local.TopicInterestEntity
import com.example.data.local.TopicKind
import com.example.data.local.TopicManualState
import com.example.domain.model.Tweet
import com.example.domain.model.TweetMetrics
import com.example.domain.model.XUser
import com.example.domain.ranking.LocalForYouRanker
import com.example.domain.ranking.RankingSignalStore
import com.example.domain.ranking.TweetImpressionEvent
import com.example.ui.screens.InterestInspectorScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnDeviceRankingEngineTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: IlayDatabase
    private lateinit var signalStore: RankingSignalStore
    private val ranker = LocalForYouRanker()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        signalStore = RankingSignalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleTweet(
        id: String,
        handle: String,
        text: String,
        displayName: String = handle,
        bio: String = "",
        websiteUrl: String = "",
        isSensitive: Boolean = false,
        likes: Long = 100L,
        ageMinutes: Long = 30L,
    ): Tweet {
        val now = 1_750_000_000_000L
        return Tweet(
            id = id,
            author = XUser(
                id = "u_$handle",
                handle = handle,
                displayName = displayName,
                bio = bio,
                websiteUrl = websiteUrl,
            ),
            text = text,
            createdAtEpochMs = now - ageMinutes * 60_000L,
            metrics = TweetMetrics(likes = likes, reposts = 12L, replies = 5L, bookmarks = 8L),
            isSensitive = isSensitive,
        )
    }

    @Test
    fun riskyAndNsfwFirewall_blocksAllLearningEvenAfterProfileVisitDwellAndInteractions() = runBlocking {
        val nsfwUser = XUser(
            id = "u_spicy",
            handle = "spicy_creator",
            displayName = "Luna 18+",
            bio = "Spicy content & VIP link in bio 🔞",
            websiteUrl = "https://onlyfans.com/luna",
        )
        val nsfwTweet = sampleTweet(
            id = "t_nsfw_1",
            handle = "spicy_creator",
            displayName = "Luna 18+",
            bio = nsfwUser.bio,
            websiteUrl = nsfwUser.websiteUrl,
            text = "Check out my new #exclusive #photography set!",
            isSensitive = true,
        )
        // Later, the SAME creator posts a completely innocent plain-text tweet without NSFW keywords
        val innocentTweetBySameCreator = sampleTweet(
            id = "t_nsfw_plain",
            handle = "spicy_creator",
            displayName = "Luna",
            text = "Good morning everyone, loving this coffee today!",
            isSensitive = false,
        )
        val safeTweet = sampleTweet(
            id = "t_safe_1",
            handle = "kotlin",
            displayName = "Kotlin",
            text = "Jetpack Compose performance improvements in #Kotlin 2.1",
        )

        // User spends time on the NSFW profile, dwells 25s on their tweet, likes, bookmarks, and searches NSFW
        signalStore.recordProfileVisit(nsfwUser)
        signalStore.recordViewportImpressions(
            listOf(TweetImpressionEvent(tweet = nsfwTweet, dwellMs = 25_000L, wasInteracted = true))
        )
        signalStore.recordTweetInteraction(nsfwTweet, like = true, bookmark = true, threadOpen = true)
        signalStore.recordSearchInterest("onlyfans nsfw leaks")

        // Assert ZERO topics were learned and creator is flagged risky with ZERO positive affinity
        val topics = db.topicInterestDao().getAllOnce()
        assertTrue("Expected zero learned topics from NSFW activity, got $topics", topics.isEmpty())

        val creatorRow = db.affinityDao().getByHandle("spicy_creator")
        assertNotNull(creatorRow)
        assertTrue(creatorRow!!.isRiskyOrNsfw)
        assertEquals(0, creatorRow.likesGiven)
        assertEquals(0, creatorRow.bookmarksGiven)
        assertEquals(0, creatorRow.profileOpens)
        assertEquals(0L, creatorRow.totalDwellSeconds)

        // Assert BOTH For You AND Following exclude the NSFW tweet AND the innocent plain-text tweet by that creator
        val suppressed = signalStore.getSuppressedRiskyHandles()
        assertTrue("spicy_creator" in suppressed)

        val allAffinities = db.affinityDao().getAllOnce()
        val forYouRanked = ranker.rankForYou(
            tweets = listOf(nsfwTweet, innocentTweetBySameCreator, safeTweet),
            affinities = allAffinities,
            mutedRules = emptyList(),
            prefs = AppPreferences(excludeRiskyAndNsfw = true),
            nowEpochMs = 1_750_000_000_000L,
        )
        assertEquals(listOf("t_safe_1"), forYouRanked.map { it.id })

        val followingFiltered = ranker.filterMuted(
            tweets = listOf(nsfwTweet, innocentTweetBySameCreator, safeTweet),
            mutedRules = emptyList(),
            excludeRiskyAndNsfw = true,
            suppressedRiskyHandles = suppressed,
        )
        assertEquals(listOf("t_safe_1"), followingFiltered.map { it.id })

        // Manual topic input also rejects risky/NSFW terms
        val rejected = signalStore.addManualTopic("onlyfans")
        assertTrue(rejected.isFailure)
    }

    @Test
    fun seenPosts_strictlyDemoteBelowUnseenPostsAndDismissedPostsAreExcluded() {
        val now = 1_750_000_000_000L
        val highViralSeen = sampleTweet(id = "t_seen", handle = "viral", text = "Huge #Android news", likes = 50_000L, ageMinutes = 10L)
        val freshUnseen = sampleTweet(id = "t_unseen", handle = "indie", text = "Quiet Compose tip", likes = 15L, ageMinutes = 40L)
        val dismissed = sampleTweet(id = "t_dismissed", handle = "noisy", text = "Not interested post", likes = 9_000L, ageMinutes = 5L)

        val seenMap = mapOf(
            "t_seen" to SeenTweetEntity(tweetId = "t_seen", authorHandleLower = "viral", viewCount = 2, lastSeenEpochMs = now - 60_000L),
            "t_dismissed" to SeenTweetEntity(tweetId = "t_dismissed", authorHandleLower = "noisy", viewCount = 1, isDismissed = true),
        )

        val ranked = ranker.rankForYou(
            tweets = listOf(highViralSeen, freshUnseen, dismissed),
            affinities = emptyList(),
            mutedRules = emptyList(),
            prefs = AppPreferences(demoteSeenPosts = true),
            nowEpochMs = now,
            seenByTweetId = seenMap,
        )

        assertEquals(listOf("t_unseen", "t_seen"), ranked.map { it.id })
        assertEquals("Seen earlier", ranked[1].localRankReason)
        assertTrue(ranked[0].localRankScore > ranked[1].localRankScore)
    }

    @Test
    fun dwellSkipAndNotInterestedUndo_updateAndReverseOnDeviceSignalsCleanly() = runBlocking {
        val composeTweet = sampleTweet(
            id = "t_compose_1",
            handle = "androiddev",
            displayName = "Android Developers",
            text = "Building smooth #JetpackCompose animations with Material3",
        )
        val skippedTweet = sampleTweet(
            id = "t_skip_1",
            handle = "boringnews",
            text = "Quarterly #spreadsheet report summary",
        )

        signalStore.recordViewportImpressions(
            listOf(
                TweetImpressionEvent(tweet = composeTweet, dwellMs = 8_000L, wasInteracted = false, wasSkipped = false),
                TweetImpressionEvent(tweet = skippedTweet, dwellMs = 600L, wasInteracted = false, wasSkipped = true),
            )
        )

        val androidAff = db.affinityDao().getByHandle("androiddev")
        assertNotNull(androidAff)
        assertTrue(androidAff!!.totalDwellSeconds >= 8L)

        val composeTopic = db.topicInterestDao().getByToken("#jetpackcompose")
        assertNotNull(composeTopic)
        assertTrue(composeTopic!!.positiveScore > 0.0)

        val skippedAff = db.affinityDao().getByHandle("boringnews")
        assertNotNull(skippedAff)
        assertEquals(1, skippedAff!!.skipsCount)

        // Not interested + Undo
        val undoPostToken = signalStore.markNotInterested(composeTweet)
        assertTrue(db.seenTweetDao().getById("t_compose_1")!!.isDismissed)
        signalStore.undoFeedback(undoPostToken)
        assertFalse(db.seenTweetDao().getById("t_compose_1")!!.isDismissed)

        // Show less from author + Undo
        val undoAuthorToken = signalStore.markShowLessFromAuthor(skippedTweet)
        assertTrue(db.affinityDao().getByHandle("boringnews")!!.isSoftDemoted)
        signalStore.undoFeedback(undoAuthorToken)
        assertFalse(db.affinityDao().getByHandle("boringnews")!!.isSoftDemoted)
    }

    @Test
    fun migration4to5_preservesExistingAffinitiesAndCreatesNewTables() {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = context.getDatabasePath("migration_test_v4_v5.db")
        if (dbFile.exists()) dbFile.delete()

        // Create a real v4 author_affinities table with a pre-existing creator row
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS `author_affinities` (" +
                "`handleLower` TEXT NOT NULL, `displayName` TEXT NOT NULL, `likesGiven` INTEGER NOT NULL, " +
                "`repostsGiven` INTEGER NOT NULL, `repliesGiven` INTEGER NOT NULL, `bookmarksGiven` INTEGER NOT NULL, " +
                "`profileOpens` INTEGER NOT NULL, `isPinnedBoost` INTEGER NOT NULL, `lastInteractedEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`handleLower`))"
        )
        sqlite.execSQL(
            "INSERT INTO `author_affinities` VALUES ('kotlin', 'Kotlin', 7, 2, 1, 3, 4, 1, 1700000000000)"
        )
        sqlite.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val writable = helper.writableDatabase
        IlayDatabase.MIGRATION_4_5.migrate(writable)

        writable.query("SELECT handleLower, likesGiven, threadOpens, totalDwellSeconds, isSoftDemoted, isRiskyOrNsfw FROM author_affinities WHERE handleLower = 'kotlin'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("kotlin", c.getString(0))
            assertEquals(7, c.getInt(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0L, c.getLong(3))
            assertEquals(0, c.getInt(4))
            assertEquals(0, c.getInt(5))
        }
        writable.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('seen_tweets','topic_interests')").use { c ->
            assertEquals(2, c.count)
        }
        helper.close()
        dbFile.delete()
    }

    @Test
    fun interestInspectorScreen_allowsAddingTopicSwitchingTabsAndTogglingFirewall() {
        var addedTopic: String? = null
        var toggledFirewall: Boolean? = null

        composeTestRule.setContent {
            MaterialTheme {
                InterestInspectorScreen(
                    preferences = AppPreferences(excludeRiskyAndNsfw = true),
                    topics = listOf(
                        TopicInterestEntity(
                            tokenLower = "#kotlin",
                            displayLabel = "#Kotlin",
                            kind = TopicKind.HASHTAG,
                            positiveScore = 8.5,
                            manualState = TopicManualState.BOOSTED,
                        )
                    ),
                    creators = listOf(
                        AuthorAffinityEntity(
                            handleLower = "androiddev",
                            displayName = "Android Developers",
                            likesGiven = 4,
                            totalDwellSeconds = 42L,
                        )
                    ),
                    seenCount = 12,
                    dismissedCount = 2,
                    riskySuppressedCount = 1,
                    onBack = {},
                    onAddTopic = { addedTopic = it },
                    onToggleBoostTopic = {},
                    onToggleDemoteTopic = {},
                    onRemoveTopic = {},
                    onTogglePinCreator = {},
                    onToggleShowLessCreator = {},
                    onResetCreator = {},
                    onSetDemoteSeenPosts = {},
                    onSetBlendMultiSource = {},
                    onSetExcludeRiskyAndNsfw = { toggledFirewall = it },
                    onClearSeenHistory = {},
                    onResetAllSignals = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("inspector_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("inspector_topic_input").performTextInput("#Compose")
        composeTestRule.onNodeWithTag("inspector_add_topic_button").performClick()
        assertEquals("#Compose", addedTopic)

        // Switch to Creators tab
        composeTestRule.onNodeWithTag("inspector_tab_1").performClick()
        composeTestRule.onNodeWithText("Android Developers").assertIsDisplayed()

        // Switch to Signals tab & toggle Risky/NSFW firewall
        composeTestRule.onNodeWithTag("inspector_tab_2").performClick()
        composeTestRule.onNodeWithTag("inspector_toggle_nsfw_firewall").performClick()
        assertEquals(false, toggledFirewall)
    }
}
