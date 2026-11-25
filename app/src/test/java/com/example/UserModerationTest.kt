package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CachedTweetEntity
import com.example.data.local.IlayDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.remote.PublicFallbackClient
import com.example.data.remote.XClientTransactionIdProvider
import com.example.data.remote.XEndpointRegistry
import com.example.data.remote.XGraphqlClient
import com.example.data.repository.BookmarkRepository
import com.example.data.repository.XRepository
import com.example.data.repository.XSearchRepository
import com.example.domain.model.FeedType
import com.example.domain.model.TrendsResult
import com.example.domain.model.Tweet
import com.example.domain.model.XUser
import com.example.domain.ranking.LocalForYouRanker
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserModerationTest {

    @Test
    fun `mute and block persist locally, clear affinity boost on block, and exclude posts from For You`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = PreferencesRepository(context)
        val okHttp = OkHttpClient.Builder().build()
        val moshi = Moshi.Builder().build()
        val tweetAdapter = moshi.adapter(Tweet::class.java)
        val userAdapter = moshi.adapter(XUser::class.java)
        val trendsAdapter = moshi.adapter(TrendsResult::class.java)
        val registry = XEndpointRegistry(okHttp, db.endpointHashDao())
        val txId = XClientTransactionIdProvider(context, okHttp)
        val gql = XGraphqlClient(okHttp, registry, txId)
        val publicClient = PublicFallbackClient(okHttp)
        val searchRepo = XSearchRepository(db, prefs, gql, publicClient, registry, tweetAdapter, trendsAdapter)
        val bookmarkRepo = BookmarkRepository(db.bookmarkDao(), tweetAdapter)
        val repo = XRepository(
            appContext = context,
            db = db,
            preferencesRepository = prefs,
            graphqlClient = gql,
            publicClient = publicClient,
            endpointRegistry = registry,
            ranker = LocalForYouRanker(),
            tweetAdapter = tweetAdapter,
            userAdapter = userAdapter,
            searchRepository = searchRepo,
            bookmarkRepository = bookmarkRepo,
        )

        val now = System.currentTimeMillis()
        val alice = XUser(id = "111", handle = "AliceDev", displayName = "Alice", isFollowing = true)
        val bob = XUser(id = "222", handle = "BobSpam", displayName = "Bob", isFollowing = true)
        val carol = XUser(id = "333", handle = "CarolClean", displayName = "Carol", isFollowing = true)

        val tAlice = Tweet(id = "t1", author = alice, text = "Alice post about Compose", createdAtEpochMs = now - 60_000L)
        val tBob = Tweet(id = "t2", author = bob, text = "Bob spam thread", createdAtEpochMs = now - 30_000L)
        val tCarol = Tweet(id = "t3", author = carol, text = "Carol architecture note", createdAtEpochMs = now - 10_000L)

        try {
            db.tweetCacheDao().replaceBucket(
                FeedType.FOR_YOU.name,
                listOf(tAlice, tBob, tCarol).map { t ->
                    CachedTweetEntity(
                        compositeKey = "${FeedType.FOR_YOU.name}_${t.id}",
                        tweetId = t.id,
                        feedBucket = FeedType.FOR_YOU.name,
                        authorHandle = t.author.handle,
                        tweetJson = tweetAdapter.toJson(t),
                        createdAtEpochMs = t.createdAtEpochMs,
                    )
                },
            )
            repo.togglePinCreatorBoost(bob.handle, bob.displayName)
            assertTrue(repo.isCreatorPinned(bob.handle))

            // 1. Mute Alice -> excluded from For You and profile reports isMuted = true
            repo.setMuteUser(alice, mute = true)
            val cachedAlice = repo.getCachedUserProfile(handle = alice.handle)
            assertTrue(cachedAlice?.isMuted == true)
            assertFalse(cachedAlice?.isBlocked == true)
            assertEquals(setOf("t2", "t3"), repo.getCachedTimeline(FeedType.FOR_YOU).map { it.id }.toSet())

            // 2. Block Bob -> excluded from For You, unfollowed locally, and pinned boost cleared
            repo.setBlockUser(bob, block = true)
            val cachedBob = repo.getCachedUserProfile(handle = bob.handle)
            assertTrue(cachedBob?.isBlocked == true)
            assertFalse(cachedBob?.isFollowing == true)
            assertFalse(repo.isCreatorPinned(bob.handle))
            assertEquals(listOf("t3"), repo.getCachedTimeline(FeedType.FOR_YOU).map { it.id })

            // 3. Unmute Alice & Unblock Bob -> both return to For You feed and moderation rows are removed
            repo.setMuteUser(alice, mute = false)
            repo.setBlockUser(bob, block = false)
            assertTrue(db.userModerationDao().getAllOnce().isEmpty())
            assertEquals(setOf("t1", "t2", "t3"), repo.getCachedTimeline(FeedType.FOR_YOU).map { it.id }.toSet())
        } finally {
            db.close()
        }
    }
}
