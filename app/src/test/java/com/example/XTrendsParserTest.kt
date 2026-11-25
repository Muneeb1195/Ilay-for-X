package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AccountEntity
import com.example.data.local.IlayDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.remote.PublicFallbackClient
import com.example.data.remote.XClientTransactionIdProvider
import com.example.data.remote.XEndpointRegistry
import com.example.data.remote.XGraphqlClient
import com.example.data.remote.XGraphqlParser
import com.example.data.repository.XSearchRepository
import com.example.domain.model.TrendItem
import com.example.domain.model.TrendsResult
import com.example.domain.model.Tweet
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XTrendsParserTest {

    @Test
    fun `parses modern GraphQL TimelineTrend items and skips promoted trends`() {
        val graphqlExploreJson = """
            {
              "data": {
                "explore_sidebar": {
                  "timeline": {
                    "instructions": [
                      {
                        "entries": [
                          {
                            "content": {
                              "itemContent": {
                                "__typename": "TimelineTrend",
                                "name": "#Android16",
                                "trend_metadata": {
                                  "domain_context": "Technology · Trending",
                                  "meta_description": "48.2K posts"
                                },
                                "trend_url": {
                                  "url": "twitter://search?query=%23Android16&q=%23Android16"
                                }
                              }
                            }
                          },
                          {
                            "content": {
                              "itemContent": {
                                "__typename": "TimelineTrend",
                                "name": "Promoted Brand",
                                "promoted_metadata": { "impressionId": "ad123" },
                                "trend_metadata": { "domain_context": "Promoted" }
                              }
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val trends = XGraphqlParser.parseTrendsFromGuide(graphqlExploreJson)
        assertEquals(1, trends.size)
        assertEquals("#Android16", trends[0].name)
        assertEquals("Technology · Trending", trends[0].category)
        assertEquals("48.2K posts", trends[0].tweetVolumeLabel)
        assertEquals("#Android16", trends[0].query)
    }

    @Test
    fun `parses REST v1 trends place payload cleanly`() {
        val restJson = """
            [
              {
                "locations": [{ "name": "Worldwide", "woeid": 1 }],
                "trends": [
                  { "name": "Kotlin 2.3", "query": "Kotlin+2.3", "tweet_volume": 125400, "promoted_content": null },
                  { "name": "Sponsored Ad", "query": "Ad", "tweet_volume": 500, "promoted_content": "promoted" }
                ]
              }
            ]
        """.trimIndent()

        val trends = XGraphqlParser.parseTrendsFromGuide(restJson)
        assertEquals(1, trends.size)
        assertEquals("Kotlin 2.3", trends[0].name)
        assertEquals("Trending · Worldwide", trends[0].category)
        assertTrue(trends[0].tweetVolumeLabel.contains("125.4K"))
    }

    @Test
    fun `TrendsResult isStale enforces 60 minute freshness threshold`() {
        val t0 = 1_700_000_000_000L
        val sample = TrendsResult(
            items = listOf(TrendItem(id = "1", name = "#Compose")),
            sourceLabel = "Live from X",
            fetchedAtEpochMs = t0,
        )

        assertFalse(sample.isStale(nowEpochMs = t0))
        assertFalse(sample.isStale(nowEpochMs = t0 + 30L * 60_000L))
        assertFalse(sample.isStale(nowEpochMs = t0 + 60L * 60_000L - 1L))
        assertTrue(sample.isStale(nowEpochMs = t0 + 60L * 60_000L))
        assertTrue(sample.isStale(nowEpochMs = t0 + 90L * 60_000L))
        assertTrue(sample.isStale(nowEpochMs = t0 - 1_000L))
        assertTrue(sample.copy(fetchedAtEpochMs = 0L).isStale(nowEpochMs = t0))
        assertTrue(sample.copy(items = emptyList()).isStale(nowEpochMs = t0))
    }

    @Test
    fun `fetchTrends uses fresh cache under 60m and forces network refresh when older than 60m`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.accountDao().upsertAccount(
            AccountEntity(
                userId = "42",
                handle = "ilaytester",
                displayName = "Ilay Tester",
                avatarUrl = "",
                bannerUrl = "",
                authToken = "test_auth_token",
                ct0 = "test_ct0_token",
                isActive = true,
            ),
        )
        val prefs = PreferencesRepository(context)
        prefs.clearCachedTrendsSnapshot()

        val fakeClock = AtomicLong(1_750_000_000_000L)
        val trendNetworkHits = AtomicInteger(0)
        val forcedNetworkHits = AtomicInteger(0)

        val stubClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val jsonMediaType = "application/json; charset=utf-8".toMediaType()
                val body = when {
                    url.contains("/2/guide.json") -> {
                        val hit = trendNetworkHits.incrementAndGet()
                        if (request.cacheControl.noCache) {
                            forcedNetworkHits.incrementAndGet()
                        }
                        """
                            {
                              "timeline": {
                                "instructions": [
                                  {
                                    "entries": [
                                      {
                                        "content": {
                                          "itemContent": {
                                            "__typename": "TimelineTrend",
                                            "name": "#LiveTrendBatch$hit",
                                            "trend_metadata": {
                                              "domain_context": "Trending",
                                              "meta_description": "${hit * 10}K posts"
                                            }
                                          }
                                        }
                                      }
                                    ]
                                  }
                                ]
                              }
                            }
                        """.trimIndent()
                    }
                    else -> "{}"
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(jsonMediaType))
                    .build()
            })
            .build()

        val moshi = Moshi.Builder().build()
        val tweetAdapter = moshi.adapter(Tweet::class.java)
        val trendsAdapter = moshi.adapter(TrendsResult::class.java)
        val endpointRegistry = XEndpointRegistry(stubClient, db.endpointHashDao())
        val txIdProvider = XClientTransactionIdProvider(context, stubClient)
        val graphqlClient = XGraphqlClient(stubClient, endpointRegistry, txIdProvider)
        val publicClient = PublicFallbackClient(stubClient)

        fun createRepo() = XSearchRepository(
            db = db,
            preferencesRepository = prefs,
            graphqlClient = graphqlClient,
            publicClient = publicClient,
            endpointRegistry = endpointRegistry,
            tweetAdapter = tweetAdapter,
            trendsResultAdapter = trendsAdapter,
            nowProvider = { fakeClock.get() },
        )

        try {
            val repo = createRepo()

            // 1. Cold fetch hits network and stores timestamp T0
            val first = repo.fetchTrends().getOrThrow()
            assertEquals("#LiveTrendBatch1", first.items.first().name)
            assertEquals(fakeClock.get(), first.fetchedAtEpochMs)
            assertEquals(1, trendNetworkHits.get())

            // 2. 59 minutes later: cached trends are still fresh -> 0 network calls
            fakeClock.addAndGet(59L * 60_000L)
            val cached = repo.fetchTrends().getOrThrow()
            assertEquals("#LiveTrendBatch1", cached.items.first().name)
            assertEquals(1, trendNetworkHits.get())

            // 3. 60 minutes + 1 second after T0: stale -> forces a live network refresh
            fakeClock.addAndGet(61_000L)
            val refreshed = repo.fetchTrends().getOrThrow()
            assertEquals("#LiveTrendBatch2", refreshed.items.first().name)
            assertEquals(fakeClock.get(), refreshed.fetchedAtEpochMs)
            assertEquals(2, trendNetworkHits.get())
            assertEquals(1, forcedNetworkHits.get())

            // 4. Simulated cold app restart 10 minutes later: reads fresh persisted snapshot (0 network calls)
            fakeClock.addAndGet(10L * 60_000L)
            val restartedFresh = createRepo().fetchTrends().getOrThrow()
            assertEquals("#LiveTrendBatch2", restartedFresh.items.first().name)
            assertEquals(2, trendNetworkHits.get())

            // 5. Simulated cold app restart 51 more minutes later (> 60m old): forces network refresh
            fakeClock.addAndGet(51L * 60_000L)
            val restartedStale = createRepo().fetchTrends().getOrThrow()
            assertEquals("#LiveTrendBatch3", restartedStale.items.first().name)
            assertEquals(3, trendNetworkHits.get())
            assertEquals(2, forcedNetworkHits.get())
        } finally {
            prefs.clearCachedTrendsSnapshot()
            db.close()
        }
    }
}
