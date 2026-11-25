package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BookmarkEntity
import com.example.data.local.IlayDatabase
import com.example.data.remote.XGraphqlParser
import com.example.data.repository.BookmarkRepository
import com.example.domain.model.Tweet
import com.example.domain.model.XUser
import com.example.ui.components.IlayLinkRouter
import com.example.ui.components.InAppLinkDestination
import com.example.ui.viewmodel.computeBookmarkFolders
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepliesBookmarksArticlesTest {

    @Test
    fun `parseTimeline extracts replies inside UserTweetsAndReplies profile conversation modules`() {
        val rawJson = """
            {
              "data": {
                "user": {
                  "result": {
                    "timeline_v2": {
                      "timeline": {
                        "instructions": [
                          {
                            "type": "TimelineAddEntries",
                            "entries": [
                              {
                                "entryId": "profile-conversation-1001",
                                "sortIndex": "9000",
                                "content": {
                                  "entryType": "TimelineTimelineModule",
                                  "items": [
                                    {
                                      "entryId": "profile-conversation-1001-tweet-1001",
                                      "item": {
                                        "itemContent": {
                                          "itemType": "TimelineTweet",
                                          "tweet_results": {
                                            "result": {
                                              "__typename": "Tweet",
                                              "rest_id": "1001",
                                              "core": {
                                                "user_results": {
                                                  "result": {
                                                    "rest_id": "u1",
                                                    "legacy": {
                                                      "screen_name": "alice",
                                                      "name": "Alice"
                                                    }
                                                  }
                                                }
                                              },
                                              "legacy": {
                                                "full_text": "Original parent post",
                                                "created_at": "Wed Nov 25 10:00:00 +0000 2026"
                                              }
                                            }
                                          }
                                        }
                                      }
                                    },
                                    {
                                      "entryId": "profile-conversation-1001-tweet-1002",
                                      "item": {
                                        "itemContent": {
                                          "itemType": "TimelineTweet",
                                          "tweet_results": {
                                            "result": {
                                              "__typename": "Tweet",
                                              "rest_id": "1002",
                                              "core": {
                                                "user_results": {
                                                  "result": {
                                                    "rest_id": "u2",
                                                    "legacy": {
                                                      "screen_name": "bob",
                                                      "name": "Bob"
                                                    }
                                                  }
                                                }
                                              },
                                              "legacy": {
                                                "full_text": "@alice Here is my reply!",
                                                "in_reply_to_status_id_str": "1001",
                                                "in_reply_to_screen_name": "alice",
                                                "created_at": "Wed Nov 25 10:05:00 +0000 2026"
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
            }
        """.trimIndent()

        val page = XGraphqlParser.parseTimeline(rawJson, sourceInfo = "X GraphQL · UserTweetsAndReplies")
        assertEquals(2, page.tweets.size)
        assertEquals("1001", page.tweets[0].id)
        assertEquals("1002", page.tweets[1].id)
        assertEquals("1001", page.tweets[1].replyToTweetId)
        assertEquals("alice", page.tweets[1].replyToHandle)
    }

    @Test
    fun `parseTimeline with sortBySortIndexDesc false preserves newest-bookmarked-first server order`() {
        // Older post (smaller tweet id and smaller sortIndex) was bookmarked most recently, so X returns it first.
        val rawJson = """
            {
              "data": {
                "bookmark_timeline_v2": {
                  "timeline": {
                    "instructions": [
                      {
                        "type": "TimelineAddEntries",
                        "entries": [
                          {
                            "entryId": "tweet-100",
                            "sortIndex": "100",
                            "content": {
                              "itemContent": {
                                "tweet_results": {
                                  "result": {
                                    "__typename": "Tweet",
                                    "rest_id": "100",
                                    "core": {
                                      "user_results": {
                                        "result": {
                                          "rest_id": "u1",
                                          "legacy": { "screen_name": "older_post", "name": "Older Post" }
                                        }
                                      }
                                    },
                                    "legacy": {
                                      "full_text": "Old post bookmarked just now",
                                      "created_at": "Mon Jan 01 10:00:00 +0000 2024"
                                    }
                                  }
                                }
                              }
                            }
                          },
                          {
                            "entryId": "tweet-999",
                            "sortIndex": "999",
                            "content": {
                              "itemContent": {
                                "tweet_results": {
                                  "result": {
                                    "__typename": "Tweet",
                                    "rest_id": "999",
                                    "core": {
                                      "user_results": {
                                        "result": {
                                          "rest_id": "u2",
                                          "legacy": { "screen_name": "newer_post", "name": "Newer Post" }
                                        }
                                      }
                                    },
                                    "legacy": {
                                      "full_text": "Newer post bookmarked earlier",
                                      "created_at": "Wed Nov 25 10:00:00 +0000 2026"
                                    }
                                  }
                                }
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

        val page = XGraphqlParser.parseTimeline(
            rawJson = rawJson,
            sourceInfo = "X Bookmarks",
            sortBySortIndexDesc = false,
        )
        assertEquals(listOf("100", "999"), page.tweets.map { it.id })
    }

    @Test
    fun `BookmarkRepository preserves newest-bookmarked-first order and custom folders across syncs`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, IlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val moshi = Moshi.Builder().build()
        val repo = BookmarkRepository(db.bookmarkDao(), moshi.adapter(Tweet::class.java))

        fun sampleTweet(id: String, text: String) = Tweet(
            id = id,
            author = XUser(id = "u_$id", handle = "user$id", displayName = "User $id"),
            text = text,
            createdAtEpochMs = 1_700_000_000_000L,
        )

        try {
            // Page 1 from X (newest bookmarked first: b1 -> b2)
            repo.saveAllBookmarks(
                tweets = listOf(sampleTweet("b1", "Most recent"), sampleTweet("b2", "Second recent")),
                folderTag = "Synced from X",
                isSyncedWithX = true,
                appendOlderPage = false,
            )

            // Page 2 from X (older bookmarks: b3 -> b4, plus duplicate b2 at cursor boundary)
            repo.saveAllBookmarks(
                tweets = listOf(sampleTweet("b2", "Duplicate"), sampleTweet("b3", "Third"), sampleTweet("b4", "Oldest")),
                folderTag = "Synced from X",
                isSyncedWithX = true,
                appendOlderPage = true,
            )

            val afterPaging = repo.observeBookmarks().first()
            assertEquals(listOf("b1", "b2", "b3", "b4"), afterPaging.map { it.second.id })
            assertTrue(afterPaging[0].first.savedAtEpochMs > afterPaging[1].first.savedAtEpochMs)
            assertTrue(afterPaging[1].first.savedAtEpochMs > afterPaging[2].first.savedAtEpochMs)
            assertTrue(afterPaging[2].first.savedAtEpochMs > afterPaging[3].first.savedAtEpochMs)

            // Organize b2 into a custom folder and verify subsequent Page 1 sync preserves it
            repo.updateFolderTag("b2", "Reading List")
            repo.saveAllBookmarks(
                tweets = listOf(sampleTweet("b0", "Brand new bookmark"), sampleTweet("b1", "Most recent"), sampleTweet("b2", "Second recent")),
                folderTag = "Synced from X",
                isSyncedWithX = true,
                appendOlderPage = false,
            )

            val afterResync = repo.observeBookmarks().first()
            assertEquals(listOf("b0", "b1", "b2"), afterResync.map { it.second.id })
            val b2Row = afterResync.first { it.second.id == "b2" }.first
            assertEquals("Reading List", b2Row.folderTag)

            // Add an offline Saved item and verify folder tab computation & sort order
            repo.saveBookmark(sampleTweet("local1", "Offline save"), folderTag = "Saved", isSyncedWithX = false)
            val allWithLocal = repo.observeBookmarks().first()
            val folders = computeBookmarkFolders(allWithLocal)
            assertEquals(
                listOf("Synced from X" to 2, "Saved" to 1, "Reading List" to 1),
                folders,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `parseArticleNode parses X GraphQL content_state blocks headings quotes lists dividers and inline media`() {
        val articleJson = JSONObject(
            """
            {
              "rest_id": "204001",
              "title": "Building Native X Readers",
              "preview_text": "How Draft.js content_state maps to Compose blocks.",
              "cover_media": {
                "media_info": {
                  "original_img_url": "https://pbs.twimg.com/media/cover_hero.jpg"
                }
              },
              "metadata": {
                "first_published_at_secs": 1764000000
              },
              "media_entities": [
                {
                  "media_id": "m_inline_1",
                  "media_info": {
                    "original_img_url": "https://pbs.twimg.com/media/inline_diagram.png"
                  }
                }
              ],
              "content_state": {
                "blocks": [
                  { "type": "header-one", "text": "Introduction" },
                  { "type": "unstyled", "text": "Welcome to our native reader." },
                  { "type": "blockquote", "text": "Good typography makes long reads effortless." },
                  { "type": "unordered-list-item", "text": "Supports headings and lists" },
                  { "type": "atomic", "text": "", "entityRanges": [{ "key": 0, "offset": 0, "length": 1 }] },
                  { "type": "atomic", "text": "", "entityRanges": [{ "key": 1, "offset": 0, "length": 1 }] },
                  { "type": "code-block", "text": "val reader = ArticleReader()" }
                ],
                "entityMap": [
                  {
                    "key": "0",
                    "value": {
                      "type": "MEDIA",
                      "data": {
                        "caption": "Architecture diagram",
                        "mediaItems": [{ "mediaId": "m_inline_1" }]
                      }
                    }
                  },
                  {
                    "key": "1",
                    "value": {
                      "type": "DIVIDER",
                      "data": {}
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val article = XGraphqlParser.parseArticleNode(articleJson, tweetId = "999001", authorHandle = "ilaydev")
        assertNotNull(article)
        article!!
        assertEquals("204001", article.id)
        assertEquals("Building Native X Readers", article.title)
        assertEquals("https://pbs.twimg.com/media/cover_hero.jpg", article.coverImageUrl)
        assertEquals("https://x.com/ilaydev/article/204001", article.articleUrl)

        val blockTypes = article.bodyBlocks.map { it.type }
        assertEquals(
            listOf("header-one", "unstyled", "blockquote", "unordered-list-item", "media", "divider", "code-block"),
            blockTypes,
        )
        val mediaBlock = article.bodyBlocks.first { it.type == "media" }
        assertEquals("https://pbs.twimg.com/media/inline_diagram.png", mediaBlock.imageUrl)
        assertEquals("Architecture diagram", mediaBlock.caption)
    }

    @Test
    fun `parseArticleNode parses FxTwitter article content object entityMap cleanly`() {
        val fxArticleJson = JSONObject(
            """
            {
              "id": "305002",
              "title": "FxTwitter Long Read",
              "preview_text": "Fallback article summary.",
              "cover_media": {
                "media_url_https": "https://pbs.twimg.com/media/fx_cover.jpg"
              },
              "media_entities": [
                {
                  "id": "fx_m1",
                  "url": "https://pbs.twimg.com/media/fx_photo.jpg"
                }
              ],
              "content": {
                "blocks": [
                  { "type": "header-two", "text": "Section One" },
                  { "type": "atomic", "text": "", "entityRanges": [{ "key": 0 }] }
                ],
                "entityMap": {
                  "0": {
                    "type": "IMAGE",
                    "data": {
                      "mediaId": "fx_m1",
                      "caption": "Field photo"
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val article = XGraphqlParser.parseArticleNode(fxArticleJson, tweetId = "888002", authorHandle = "writer")
        assertNotNull(article)
        article!!
        assertEquals("FxTwitter Long Read", article.title)
        assertEquals("https://pbs.twimg.com/media/fx_cover.jpg", article.coverImageUrl)
        assertEquals(2, article.bodyBlocks.size)
        assertEquals("header-two", article.bodyBlocks[0].type)
        assertEquals("media", article.bodyBlocks[1].type)
        assertEquals("https://pbs.twimg.com/media/fx_photo.jpg", article.bodyBlocks[1].imageUrl)
        assertEquals("Field photo", article.bodyBlocks[1].caption)
    }

    @Test
    fun `IlayLinkRouter resolves X article URLs to native Article destination`() {
        val authorArticle = IlayLinkRouter.resolveUrl("https://x.com/alice/article/1901234567890")
        assertTrue(authorArticle is InAppLinkDestination.Article)
        authorArticle as InAppLinkDestination.Article
        assertEquals("1901234567890", authorArticle.articleId)
        assertEquals("alice", authorArticle.authorHandle)

        val genericArticle = IlayLinkRouter.resolveUrl("https://twitter.com/i/article/1909876543210")
        assertTrue(genericArticle is InAppLinkDestination.Article)
        genericArticle as InAppLinkDestination.Article
        assertEquals("1909876543210", genericArticle.articleId)
        assertEquals("", genericArticle.authorHandle)
    }
}
