package com.example

import com.example.data.remote.XGraphqlParser
import com.example.domain.model.MediaAttachment
import com.example.domain.model.SearchTab
import com.example.domain.model.ThreadConversation
import com.example.domain.model.Tweet
import com.example.domain.model.TweetMetrics
import com.example.domain.model.XUser
import com.example.ui.components.ThreadListItem
import com.example.ui.components.buildThreadListItems
import com.example.ui.screens.fullResProfileImageUrl
import com.example.ui.viewmodel.ThreadTab
import com.example.ui.viewmodel.buildProfileSearchQuery
import com.example.ui.viewmodel.filterProfileLocalTweets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchProfileAndThreadUxTest {

    @Test
    fun parseUsersTimeline_extractsUsersFromModuleItemsWithFollowerCounts() {
        val rawJson = """
            {
              "data": {
                "search_by_raw_query": {
                  "search_timeline": {
                    "timeline": {
                      "instructions": [
                        {
                          "type": "TimelineAddEntries",
                          "entries": [
                            {
                              "entryId": "user-module-0",
                              "content": {
                                "items": [
                                  {
                                    "item": {
                                      "itemContent": {
                                        "user_results": {
                                          "result": {
                                            "rest_id": "44196397",
                                            "is_blue_verified": true,
                                            "legacy": {
                                              "screen_name": "androiddev",
                                              "name": "Android Developers",
                                              "description": "Official Android news",
                                              "followers_count": 2150400,
                                              "friends_count": 320,
                                              "statuses_count": 14500,
                                              "profile_image_url_https": "https://pbs.twimg.com/profile_images/1/abc_normal.jpg"
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val users = XGraphqlParser.parseUsersTimeline(rawJson)
        assertEquals(1, users.size)
        assertEquals("androiddev", users.first().handle)
        assertEquals(2150400L, users.first().followersCount)
        assertEquals(320L, users.first().followingCount)
        assertEquals(14500L, users.first().tweetsCount)
    }

    @Test
    fun parseLegacyUsersArray_parsesFollowerAndFollowingCounts() {
        val rawJson = """
            [
              {
                "id_str": "12345",
                "screen_name": "kotlin",
                "name": "Kotlin",
                "description": "Concise. Cross-platform. Fun.",
                "followers_count": 489120,
                "friends_count": 210,
                "statuses_count": 8200,
                "verified": true,
                "profile_image_url_https": "https://pbs.twimg.com/profile_images/2/kt_normal.png"
              }
            ]
        """.trimIndent()

        val users = XGraphqlParser.parseLegacyUsersArray(rawJson)
        assertEquals(1, users.size)
        assertEquals("kotlin", users.first().handle)
        assertEquals(489120L, users.first().followersCount)
        assertEquals(210L, users.first().followingCount)
    }

    @Test
    fun fullResProfileImageUrl_stripsThumbnailSuffixes() {
        assertEquals(
            "https://pbs.twimg.com/profile_images/99/avatar.jpg",
            fullResProfileImageUrl("https://pbs.twimg.com/profile_images/99/avatar_normal.jpg"),
        )
        assertEquals(
            "https://pbs.twimg.com/profile_images/99/avatar.png",
            fullResProfileImageUrl("https://pbs.twimg.com/profile_images/99/avatar_400x400.png"),
        )
        assertEquals(
            "https://pbs.twimg.com/profile_images/99/avatar.jpg?name=orig",
            fullResProfileImageUrl("https://pbs.twimg.com/profile_images/99/avatar_bigger.jpg?name=orig"),
        )
        assertEquals(
            "https://pbs.twimg.com/profile_images/99/avatar.jpg",
            fullResProfileImageUrl("https://pbs.twimg.com/profile_images/99/avatar.jpg"),
        )
    }

    @Test
    fun buildThreadListItems_rendersFocalImmediatelyAndShowsRepliesLoaderWhileLoading() {
        val focalTweet = Tweet(
            id = "9001",
            author = XUser(id = "u1", handle = "compose", displayName = "Compose"),
            text = "Instant tweet detail transition test",
            createdAtEpochMs = 1_700_000_000_000L,
            metrics = TweetMetrics(replies = 5, likes = 42),
        )
        val seededConversation = ThreadConversation(
            ancestors = emptyList(),
            focalTweet = focalTweet,
            replies = emptyList(),
        )

        val loadingItems = buildThreadListItems(
            conversation = seededConversation,
            selectedTab = ThreadTab.REPLIES,
            quotes = emptyList(),
            quotesCursor = null,
            isLoadingReplies = true,
            isLoadingQuotes = false,
            isLoadingMoreQuotes = false,
            quotesError = null,
            ancestorsExpanded = false,
        )

        assertTrue(loadingItems.any { it is ThreadListItem.Focal && it.tweet.id == "9001" })
        assertTrue(loadingItems.any { it is ThreadListItem.Loading && it.id == "replies_loading" })
        assertTrue(loadingItems.none { it is ThreadListItem.EmptyMessage })

        val replyTweet = Tweet(
            id = "9002",
            author = XUser(id = "u2", handle = "reader", displayName = "Reader"),
            text = "Loaded smoothly!",
            createdAtEpochMs = 1_700_000_060_000L,
            replyToTweetId = "9001",
        )
        val hydratedItems = buildThreadListItems(
            conversation = seededConversation.copy(replies = listOf(replyTweet)),
            selectedTab = ThreadTab.REPLIES,
            quotes = emptyList(),
            quotesCursor = null,
            isLoadingReplies = false,
            isLoadingQuotes = false,
            isLoadingMoreQuotes = false,
            quotesError = null,
            ancestorsExpanded = false,
        )

        assertTrue(hydratedItems.any { it is ThreadListItem.Focal && it.tweet.id == "9001" })
        assertTrue(hydratedItems.any { it is ThreadListItem.Reply && it.tweet.id == "9002" })
        assertTrue(hydratedItems.none { it is ThreadListItem.Loading })
    }

    @Test
    fun buildProfileSearchQuery_scopesToAuthorHandleWithoutDuplication() {
        assertEquals("from:androiddev compose", buildProfileSearchQuery("@androiddev", "  compose  "))
        assertEquals("from:androiddev compose", buildProfileSearchQuery("androiddev", "from:androiddev compose"))
        assertEquals("", buildProfileSearchQuery("androiddev", "   "))
    }

    @Test
    fun filterProfileLocalTweets_filtersKeywordsMediaAndSortingModes() {
        val author = XUser(id = "u1", handle = "androiddev", displayName = "Android Developers")
        val olderHighLikes = Tweet(
            id = "t1",
            author = author,
            text = "Jetpack Compose performance tips #AndroidDev",
            createdAtEpochMs = 1_700_000_000_000L,
            metrics = TweetMetrics(likes = 500, reposts = 100),
        )
        val newerWithMedia = Tweet(
            id = "t2",
            author = author,
            text = "New Compose animation guide",
            createdAtEpochMs = 1_700_000_500_000L,
            media = listOf(MediaAttachment(id = "m1", url = "https://pbs.twimg.com/media/a.jpg")),
            metrics = TweetMetrics(likes = 20, reposts = 2),
        )
        val unrelated = Tweet(
            id = "t3",
            author = author,
            text = "Gradle release notes",
            createdAtEpochMs = 1_700_000_900_000L,
        )

        val latest = filterProfileLocalTweets(
            tweets = listOf(olderHighLikes, newerWithMedia, unrelated),
            query = "compose",
            tab = SearchTab.LATEST,
        )
        assertEquals(listOf("t2", "t1"), latest.map { it.id })

        val top = filterProfileLocalTweets(
            tweets = listOf(olderHighLikes, newerWithMedia, unrelated),
            query = "compose",
            tab = SearchTab.TOP,
        )
        assertEquals(listOf("t1", "t2"), top.map { it.id })

        val mediaOnly = filterProfileLocalTweets(
            tweets = listOf(olderHighLikes, newerWithMedia, unrelated),
            query = "compose",
            tab = SearchTab.MEDIA,
        )
        assertEquals(listOf("t2"), mediaOnly.map { it.id })
    }
}
