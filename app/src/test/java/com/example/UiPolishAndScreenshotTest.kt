package com.example

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.data.remote.NetworkTier
import com.example.data.remote.XGraphqlParser
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.LinkPreview
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.ui.components.DefaultLinkHandlerHelper
import com.example.ui.components.EntityKind
import com.example.ui.components.FormatUtils
import com.example.ui.components.IlayChromeState
import com.example.ui.components.IlayLinkMovementMethod
import com.example.ui.components.IlayLinkMovementText
import com.example.ui.components.IlayLinkRouter
import com.example.ui.components.InAppLinkDestination
import com.example.ui.components.LIGHTBOX_CHROME_AUTO_HIDE_MS
import com.example.ui.components.LinkPreviewCard
import com.example.ui.components.LocalTweetCardHost
import com.example.ui.components.TWEET_MEDIA_MAX_ASPECT_RATIO
import com.example.ui.components.TWEET_MEDIA_MIN_ASPECT_RATIO
import com.example.ui.components.TweetCardHost
import com.example.ui.components.TweetMediaSaver
import com.example.ui.components.buildMediaCandidateUrls
import com.example.ui.components.clampedTweetMediaAspectRatio
import com.example.ui.components.computeTopBarColorProgress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiPolishAndScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun extractTextEntities_detectsMentionsHashtagsFullAndBareUrlsWithoutEmailFalsePositives() {
        val text = "Hey @androiddev, check #JetpackCompose at https://developer.android.com/compose. " +
            "Also github.com/ilay/x-client, www.example.org/docs! Contact hi@example.com."
        val spans = FormatUtils.extractTextEntities(text)

        val mentions = spans.filter { it.kind == EntityKind.MENTION }
        val hashtags = spans.filter { it.kind == EntityKind.HASHTAG }
        val urls = spans.filter { it.kind == EntityKind.URL }

        assertEquals(listOf("androiddev"), mentions.map { it.value })
        assertEquals(listOf("#JetpackCompose"), hashtags.map { it.value })
        assertEquals(
            listOf(
                "https://developer.android.com/compose",
                "https://github.com/ilay/x-client",
                "https://www.example.org/docs",
            ),
            urls.map { it.value },
        )
    }

    @Test
    fun extractTextEntities_preservesBalancedParenthesesInUrls() {
        val text = "Docs: https://en.wikipedia.org/wiki/Kotlin_(programming_language) and (https://kotlinlang.org/)."
        val urls = FormatUtils.extractTextEntities(text).filter { it.kind == EntityKind.URL }
        assertEquals(
            listOf(
                "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
                "https://kotlinlang.org/",
            ),
            urls.map { it.value },
        )
    }

    @Test
    fun buildInteractiveEntityText_attachesClickableLinksAndInvokesCallbacks() {
        var clickedMention: String? = null
        var clickedHashtag: String? = null
        var clickedUrl: String? = null

        val annotated = FormatUtils.buildInteractiveEntityText(
            text = "Built by @ilay_dev for #AndroidDev -> github.com/ilay",
            accentColor = Color.Blue,
            onMentionClick = { clickedMention = it },
            onHashtagClick = { clickedHashtag = it },
            onUrlClick = { clickedUrl = it },
        )

        val linkRanges = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(3, linkRanges.size)

        linkRanges.forEach { range ->
            val clickable = range.item as LinkAnnotation.Clickable
            clickable.linkInteractionListener?.onClick(clickable)
        }

        assertEquals("ilay_dev", clickedMention)
        assertEquals("#AndroidDev", clickedHashtag)
        assertEquals("https://github.com/ilay", clickedUrl)
    }

    @Test
    fun linkRouter_routesXLinksToNativeScreensAndExternalUrlsToInAppWebBrowser() {
        assertEquals(
            InAppLinkDestination.Profile("androiddev"),
            IlayLinkRouter.resolveUrl("https://x.com/androiddev"),
        )
        assertEquals(
            InAppLinkDestination.Thread(tweetId = "189451234567890", authorHandle = "kotlin"),
            IlayLinkRouter.resolveUrl("https://twitter.com/kotlin/status/189451234567890?s=20"),
        )
        assertEquals(
            InAppLinkDestination.HashtagSearch("#JetpackCompose"),
            IlayLinkRouter.resolveUrl("https://x.com/hashtag/JetpackCompose"),
        )
        assertEquals(
            InAppLinkDestination.HashtagSearch("compose performance"),
            IlayLinkRouter.resolveUrl("https://x.com/search?q=compose+performance"),
        )
        assertEquals(
            InAppLinkDestination.WebPage("https://x.com/settings"),
            IlayLinkRouter.resolveUrl("https://x.com/settings"),
        )
        assertEquals(
            InAppLinkDestination.WebPage("https://github.com/ilay/app"),
            IlayLinkRouter.resolveUrl("github.com/ilay/app"),
        )
    }

    @Test
    fun linkMovementMethod_findEntityAtOffsetMatchesExactSpanBoundaries() {
        val text = "Hi @alice see #news and example.com now"
        val method = IlayLinkMovementMethod(FormatUtils.extractTextEntities(text))

        assertNull(method.findEntityAtOffset(0))
        assertEquals("alice", method.findEntityAtOffset(text.indexOf("@alice") + 1)?.value)
        assertEquals("#news", method.findEntityAtOffset(text.indexOf("#news") + 2)?.value)
        assertEquals("https://example.com", method.findEntityAtOffset(text.indexOf("example.com") + 3)?.value)
        assertNull(method.findEntityAtOffset(text.length - 1))
    }

    @Test
    fun linkMovementText_interceptsMentionHashtagAndUrlClicksWithoutTriggeringParentCardClick() {
        var parentClicks = 0
        val clickedMentions = mutableListOf<String>()
        val clickedHashtags = mutableListOf<String>()
        val clickedUrls = mutableListOf<String>()

        composeTestRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .width(360.dp)
                        .clickable { parentClicks++ }
                        .testTag("parent_card"),
                ) {
                    IlayLinkMovementText(
                        text = "@ilay_dev",
                        onMentionClick = { clickedMentions += it },
                        onHashtagClick = { clickedHashtags += it },
                        onUrlClick = { clickedUrls += it },
                        modifier = Modifier.testTag("mention_text"),
                    )
                    IlayLinkMovementText(
                        text = "#ComposeUI",
                        onMentionClick = { clickedMentions += it },
                        onHashtagClick = { clickedHashtags += it },
                        onUrlClick = { clickedUrls += it },
                        modifier = Modifier.testTag("hashtag_text"),
                    )
                    IlayLinkMovementText(
                        text = "https://github.com/ilay/x",
                        onMentionClick = { clickedMentions += it },
                        onHashtagClick = { clickedHashtags += it },
                        onUrlClick = { clickedUrls += it },
                        modifier = Modifier.testTag("url_text"),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("mention_text", useUnmergedTree = true).performTouchInput {
            click(Offset(x = 12f, y = centerY))
        }
        composeTestRule.onNodeWithTag("hashtag_text", useUnmergedTree = true).performTouchInput {
            click(Offset(x = 12f, y = centerY))
        }
        composeTestRule.onNodeWithTag("url_text", useUnmergedTree = true).performTouchInput {
            click(Offset(x = 12f, y = centerY))
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("ilay_dev"), clickedMentions)
        assertEquals(listOf("#ComposeUI"), clickedHashtags)
        assertEquals(listOf("https://github.com/ilay/x"), clickedUrls)
        assertEquals(0, parentClicks)
    }

    @Test
    fun linkMovementText_allowsPlainTextTapToPassThroughToParentCard() {
        var parentClicks = 0
        var entityClicks = 0

        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .clickable { parentClicks++ }
                        .testTag("parent_card"),
                ) {
                    IlayLinkMovementText(
                        text = "Plain status text with no entities",
                        onMentionClick = { entityClicks++ },
                        onHashtagClick = { entityClicks++ },
                        onUrlClick = { entityClicks++ },
                        modifier = Modifier.testTag("plain_text"),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("plain_text", useUnmergedTree = true).performTouchInput {
            click(center)
        }
        composeTestRule.waitForIdle()

        assertEquals(0, entityClicks)
        assertEquals(1, parentClicks)
    }

    @Test
    fun graphqlUserParser_expandsBioShortUrlsAndExtractsWebsiteUrl() {
        val rawUser = JSONObject().apply {
            put("rest_id", "42")
            put("legacy", JSONObject().apply {
                put("screen_name", "ilayuser")
                put("name", "Ilay User")
                put("description", "Building apps at https://t.co/abc123 with @kotlin")
                put("entities", JSONObject().apply {
                    put("description", JSONObject().apply {
                        put("urls", JSONArray().apply {
                            put(JSONObject().apply {
                                put("url", "https://t.co/abc123")
                                put("expanded_url", "https://github.com/ilay/apps")
                            })
                        })
                    })
                    put("url", JSONObject().apply {
                        put("urls", JSONArray().apply {
                            put(JSONObject().apply {
                                put("url", "https://t.co/site")
                                put("expanded_url", "https://ilay.dev")
                            })
                        })
                    })
                })
            })
        }

        val parsed = XGraphqlParser.parseUserObject(rawUser)
        assertEquals("Building apps at https://github.com/ilay/apps with @kotlin", parsed?.bio)
        assertEquals("https://ilay.dev", parsed?.websiteUrl)

        val legacyParsed = XGraphqlParser.parseLegacyUser(rawUser.getJSONObject("legacy"))
        assertEquals("Building apps at https://github.com/ilay/apps with @kotlin", legacyParsed.bio)
        assertEquals("https://ilay.dev", legacyParsed.websiteUrl)
    }

    @Test
    fun createMarkupEditorIntent_setsActionEditPngMimeAndReadWriteGrants() {
        val uri = "content://media/external/images/media/99".toUri()
        val intent = TweetMediaSaver.createMarkupEditorIntent(uri)

        assertEquals(Intent.ACTION_EDIT, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("image/png", intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue((intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
    }

    @Test
    fun computeTopBarColorProgress_isContinuousAndMonotonicWithoutThresholdJump() {
        assertEquals(0f, computeTopBarColorProgress(-0.5f), 0.0001f)
        assertEquals(0f, computeTopBarColorProgress(0f), 0.0001f)
        assertEquals(1f, computeTopBarColorProgress(1f), 0.0001f)
        assertEquals(1f, computeTopBarColorProgress(1.5f), 0.0001f)

        var previous = computeTopBarColorProgress(0f)
        for (step in 1..100) {
            val current = computeTopBarColorProgress(step / 100f)
            assertTrue("Expected monotonic progression at step $step", current >= previous)
            assertTrue("Expected no sudden jump at step $step", (current - previous) < 0.05f)
            previous = current
        }
    }

    @Test
    fun ilayLinkRouter_resolvesAllXTwitterAndProxyTweetVariantsToNativeThread() {
        val tweetUrls = listOf(
            "https://x.com/AndroidDev/status/1901234567890123456" to ("1901234567890123456" to "AndroidDev"),
            "https://mobile.twitter.com/kotlin/statuses/1901234567890123457?s=20&t=abc" to ("1901234567890123457" to "kotlin"),
            "https://x.com/i/web/status/1901234567890123458" to ("1901234567890123458" to ""),
            "https://fxtwitter.com/Compose/status/1901234567890123459/photo/1" to ("1901234567890123459" to "Compose"),
            "https://vxtwitter.com/Google/status/1901234567890123460/video/1" to ("1901234567890123460" to "Google"),
            "https://fixupx.com/JetBrains/status/1901234567890123461" to ("1901234567890123461" to "JetBrains"),
            "https://fixvx.com/MaterialDesign/status/1901234567890123462" to ("1901234567890123462" to "MaterialDesign"),
            "https://x.com/intent/like?tweet_id=1901234567890123463" to ("1901234567890123463" to ""),
        )
        tweetUrls.forEach { (url, expected) ->
            assertEquals(
                "Failed for $url",
                InAppLinkDestination.Thread(expected.first, expected.second),
                IlayLinkRouter.resolveUrl(url),
            )
        }
    }

    @Test
    fun ilayLinkRouter_resolvesCustomSchemesAndNativeAppRoutes() {
        assertEquals(
            InAppLinkDestination.Thread("190555666777888999", ""),
            IlayLinkRouter.resolveUrl("twitter://status?status_id=190555666777888999"),
        )
        assertEquals(
            InAppLinkDestination.Thread("190555666777888998", ""),
            IlayLinkRouter.resolveUrl("x://status?id=190555666777888998"),
        )
        assertEquals(
            InAppLinkDestination.Profile("AndroidDev"),
            IlayLinkRouter.resolveUrl("twitter://user?screen_name=AndroidDev"),
        )
        assertEquals(
            InAppLinkDestination.HashtagSearch("JetpackCompose"),
            IlayLinkRouter.resolveUrl("x://search?q=JetpackCompose"),
        )
        assertEquals(InAppLinkDestination.Home, IlayLinkRouter.resolveUrl("x://home"))
        assertEquals(InAppLinkDestination.Activity, IlayLinkRouter.resolveUrl("x://notifications"))
        assertEquals(InAppLinkDestination.Bookmarks, IlayLinkRouter.resolveUrl("x://bookmarks"))
        assertEquals(
            InAppLinkDestination.Compose(prefillText = "Hello from deep link"),
            IlayLinkRouter.resolveUrl("twitter://post?message=Hello%20from%20deep%20link"),
        )

        assertEquals(InAppLinkDestination.Home, IlayLinkRouter.resolveUrl("https://x.com/home"))
        assertEquals(InAppLinkDestination.Activity, IlayLinkRouter.resolveUrl("https://x.com/notifications"))
        assertEquals(InAppLinkDestination.Bookmarks, IlayLinkRouter.resolveUrl("https://x.com/i/bookmarks"))
        assertEquals(
            InAppLinkDestination.Compose(prefillText = "Check this out"),
            IlayLinkRouter.resolveUrl("https://x.com/intent/tweet?text=Check%20this%20out"),
        )
        assertEquals(
            InAppLinkDestination.WebPage("https://x.com/i/spaces/1abc2def"),
            IlayLinkRouter.resolveUrl("https://x.com/i/spaces/1abc2def"),
        )
    }

    @Test
    fun ilayLinkRouter_extractSupportedDeepLinkUrl_handlesViewAndShareIntents() {
        val viewProxyIntent = Intent(Intent.ACTION_VIEW, "https://fixupx.com/kotlin/status/19123456789".toUri())
        assertEquals(
            "https://fixupx.com/kotlin/status/19123456789",
            IlayLinkRouter.extractSupportedDeepLinkUrl(viewProxyIntent),
        )

        val customSchemeIntent = Intent(Intent.ACTION_VIEW, "twitter://status?status_id=19123456790".toUri())
        assertEquals(
            "twitter://status?status_id=19123456790",
            IlayLinkRouter.extractSupportedDeepLinkUrl(customSchemeIntent),
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Look at this post https://vxtwitter.com/AndroidDev/status/19123456791!",
            )
        }
        assertEquals(
            "https://vxtwitter.com/AndroidDev/status/19123456791",
            IlayLinkRouter.extractSupportedDeepLinkUrl(shareIntent),
        )

        val unrelatedIntent = Intent(Intent.ACTION_VIEW, "https://example.com/status/12345".toUri())
        assertNull(IlayLinkRouter.extractSupportedDeepLinkUrl(unrelatedIntent))
    }

    @Test
    fun defaultLinkHandlerHelper_createsAndroid12OpenByDefaultSettingsIntent() {
        val context = RuntimeEnvironment.getApplication()
        val intent = DefaultLinkHandlerHelper.createOpenByDefaultSettingsIntent(context)

        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun lightboxChromeAutoHide_isSetToThreeSeconds() {
        assertEquals(3_000L, LIGHTBOX_CHROME_AUTO_HIDE_MS)
    }

    @Test
    fun createShareImageIntent_setsActionSendPngMimeExtraStreamAndReadGrant() {
        val uri = "content://media/external/images/media/101".toUri()
        val intent = TweetMediaSaver.createShareImageIntent(uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun linkPreviewCard_suppressesImageAndSkipsEnrichmentWhenShowImageIsFalse() {
        var resolveCalls = 0
        val preview = LinkPreview(
            url = "https://kotlinlang.org/docs/home.html",
            domain = "kotlinlang.org",
            title = "Kotlin Docs",
            description = "Official documentation",
            imageUrl = "https://kotlinlang.org/og.png",
        )

        composeTestRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalTweetCardHost provides TweetCardHost(
                        resolveLinkPreview = {
                            resolveCalls++
                            it
                        },
                    ),
                ) {
                    LinkPreviewCard(
                        preview = preview,
                        compact = false,
                        showImage = false,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("link_preview_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("link_preview_image", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, resolveCalls)
    }

    @Test
    fun linkPreviewCard_showsImageWhenShowImageIsTrue() {
        val preview = LinkPreview(
            url = "https://kotlinlang.org/docs/home.html",
            domain = "kotlinlang.org",
            title = "Kotlin Docs",
            imageUrl = "https://kotlinlang.org/og.png",
        )

        composeTestRule.setContent {
            MaterialTheme {
                LinkPreviewCard(
                    preview = preview,
                    compact = false,
                    showImage = true,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("link_preview_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("link_preview_image", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun buildMediaCandidateUrls_prioritizesCachedFeedVariantsAndExcludesMp4VideoStreams() {
        val photo = MediaAttachment(
            id = "p1",
            type = MediaType.PHOTO,
            url = "https://pbs.twimg.com/media/AbCdEf.jpg",
            thumbnailUrl = "https://pbs.twimg.com/media/AbCdEf.jpg",
        )
        val photoCandidates = buildMediaCandidateUrls(
            item = photo,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )
        assertTrue(photoCandidates.first().contains("name=small"))
        assertTrue(photoCandidates.any { it.contains("name=360x360") })
        assertTrue(photoCandidates.contains("https://pbs.twimg.com/media/AbCdEf.jpg"))

        val video = MediaAttachment(
            id = "v1",
            type = MediaType.VIDEO,
            url = "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/clip.mp4",
            thumbnailUrl = "https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/poster.jpg",
            videoUrl = "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/clip.mp4",
        )
        val videoCandidates = buildMediaCandidateUrls(
            item = video,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.METERED_CELLULAR,
        )
        assertTrue(videoCandidates.isNotEmpty())
        assertTrue(videoCandidates.none { it.contains(".mp4") })
        assertTrue(videoCandidates.any { it.contains("poster.jpg") })
    }

    @Test
    fun ilayChromeState_preservesExactCollapseStateWhileDetailScrollTrackingDisabled() {
        val chrome = IlayChromeState(collapseDistancePx = 200f)

        // Scroll down on feed to collapse top & bottom bars completely
        chrome.nestedScrollConnection.onPreScroll(Offset(0f, -200f), NestedScrollSource.UserInput)
        assertEquals(1f, chrome.collapseProgress, 0.001f)

        // Open tweet detail: disable scroll tracking without resetting collapseProgress
        chrome.isScrollTrackingEnabled = false
        chrome.nestedScrollConnection.onPreScroll(Offset(0f, 180f), NestedScrollSource.UserInput)
        chrome.nestedScrollConnection.onPreScroll(Offset(0f, -90f), NestedScrollSource.UserInput)
        assertEquals(1f, chrome.collapseProgress, 0.001f)

        // Return to feed: exact collapsed state remains intact until user scrolls feed again
        chrome.isScrollTrackingEnabled = true
        assertEquals(1f, chrome.collapseProgress, 0.001f)
        chrome.nestedScrollConnection.onPreScroll(Offset(0f, 100f), NestedScrollSource.UserInput)
        assertEquals(0.5f, chrome.collapseProgress, 0.001f)
    }

    @Test
    fun clampedTweetMediaAspectRatio_clampsBetweenOneToOneAndSixteenByNine() {
        val tallPortrait = MediaAttachment(id = "1", url = "u", width = 1080, height = 1920)
        val fourByFive = MediaAttachment(id = "2", url = "u", width = 800, height = 1000)
        val square = MediaAttachment(id = "3", url = "u", width = 1000, height = 1000)
        val fourByThree = MediaAttachment(id = "4", url = "u", width = 1600, height = 1200)
        val sixteenByNine = MediaAttachment(id = "5", url = "u", width = 1920, height = 1080)
        val ultraWide = MediaAttachment(id = "6", url = "u", width = 2560, height = 800)
        val invalid = MediaAttachment(id = "7", url = "u", width = 0, height = 0)

        assertEquals(TWEET_MEDIA_MIN_ASPECT_RATIO, clampedTweetMediaAspectRatio(tallPortrait), 0.0001f)
        assertEquals(TWEET_MEDIA_MIN_ASPECT_RATIO, clampedTweetMediaAspectRatio(fourByFive), 0.0001f)
        assertEquals(1f, clampedTweetMediaAspectRatio(square), 0.0001f)
        assertEquals(4f / 3f, clampedTweetMediaAspectRatio(fourByThree), 0.0001f)
        assertEquals(TWEET_MEDIA_MAX_ASPECT_RATIO, clampedTweetMediaAspectRatio(sixteenByNine), 0.0001f)
        assertEquals(TWEET_MEDIA_MAX_ASPECT_RATIO, clampedTweetMediaAspectRatio(ultraWide), 0.0001f)
        assertEquals(TWEET_MEDIA_MAX_ASPECT_RATIO, clampedTweetMediaAspectRatio(invalid), 0.0001f)
        assertEquals(TWEET_MEDIA_MAX_ASPECT_RATIO, clampedTweetMediaAspectRatio(null), 0.0001f)
    }

    @Test
    fun extractMediaDimensions_resolvesOriginalSizesAndVideoAspectRatioFallbacks() {
        val fromOriginal = JSONObject().put(
            "original_info",
            JSONObject().put("width", 900).put("height", 1600),
        )
        assertEquals(900 to 1600, XGraphqlParser.extractMediaDimensions(fromOriginal))

        val fromSizes = JSONObject().put(
            "sizes",
            JSONObject().put("large", JSONObject().put("w", 1200).put("h", 900)),
        )
        assertEquals(1200 to 900, XGraphqlParser.extractMediaDimensions(fromSizes))

        val fromVideoAspect = JSONObject().put(
            "video_info",
            JSONObject().put("aspect_ratio", JSONArray().put(9).put(16)),
        )
        assertEquals(900 to 1600, XGraphqlParser.extractMediaDimensions(fromVideoAspect))

        assertEquals(1200 to 675, XGraphqlParser.extractMediaDimensions(JSONObject()))
    }

    @Test
    fun parseThreadDetailAndTimeline_stripsPromotedEntriesInTimelinesAndNestedReplyModules() {
        fun tweetResultJson(id: String, text: String): JSONObject = JSONObject()
            .put("__typename", "Tweet")
            .put("rest_id", id)
            .put(
                "core",
                JSONObject().put(
                    "user_results",
                    JSONObject().put(
                        "result",
                        JSONObject()
                            .put("rest_id", "u_$id")
                            .put("legacy", JSONObject().put("screen_name", "user_$id").put("name", "User $id"))
                    )
                )
            )
            .put(
                "legacy",
                JSONObject()
                    .put("id_str", id)
                    .put("full_text", text)
                    .put("created_at", "Wed Oct 10 20:19:24 +0000 2024")
            )

        val rawThreadJson = JSONObject().put(
            "data",
            JSONObject().put(
                "threaded_conversation_with_injections_v2",
                JSONObject().put(
                    "instructions",
                    JSONArray().put(
                        JSONObject().put(
                            "entries",
                            JSONArray()
                                // Focal tweet (organic)
                                .put(
                                    JSONObject()
                                        .put("entryId", "tweet-100")
                                        .put(
                                            "content",
                                            JSONObject().put(
                                                "itemContent",
                                                JSONObject().put(
                                                    "tweet_results",
                                                    JSONObject().put("result", tweetResultJson("100", "Main post"))
                                                )
                                            )
                                        )
                                )
                                // Top-level promoted tweet
                                .put(
                                    JSONObject()
                                        .put("entryId", "promoted-tweet-999")
                                        .put(
                                            "content",
                                            JSONObject().put(
                                                "itemContent",
                                                JSONObject()
                                                    .put("promotedMetadata", JSONObject().put("impressionId", "ad1"))
                                                    .put(
                                                        "tweet_results",
                                                        JSONObject().put("result", tweetResultJson("999", "Top ad"))
                                                    )
                                            )
                                        )
                                )
                                // Conversation module containing 1 organic reply + 2 promoted replies (one via promotedMetadata, one via entryId)
                                .put(
                                    JSONObject()
                                        .put("entryId", "conversationthread-200")
                                        .put(
                                            "content",
                                            JSONObject().put(
                                                "items",
                                                JSONArray()
                                                    .put(
                                                        JSONObject()
                                                            .put("entryId", "conversationthread-200-tweet-201")
                                                            .put(
                                                                "item",
                                                                JSONObject().put(
                                                                    "itemContent",
                                                                    JSONObject().put(
                                                                        "tweet_results",
                                                                        JSONObject().put("result", tweetResultJson("201", "Real reply"))
                                                                    )
                                                                )
                                                            )
                                                    )
                                                    .put(
                                                        JSONObject()
                                                            .put("entryId", "conversationthread-200-tweet-202")
                                                            .put(
                                                                "item",
                                                                JSONObject().put(
                                                                    "itemContent",
                                                                    JSONObject()
                                                                        .put("promotedMetadata", JSONObject().put("impressionId", "ad2"))
                                                                        .put(
                                                                            "tweet_results",
                                                                            JSONObject().put("result", tweetResultJson("202", "Reply ad 1"))
                                                                        )
                                                                )
                                                            )
                                                    )
                                                    .put(
                                                        JSONObject()
                                                            .put("entryId", "conversationthread-200-promoted-tweet-203")
                                                            .put(
                                                                "item",
                                                                JSONObject().put(
                                                                    "itemContent",
                                                                    JSONObject().put(
                                                                        "tweet_results",
                                                                        JSONObject().put("result", tweetResultJson("203", "Reply ad 2"))
                                                                    )
                                                                )
                                                            )
                                                    )
                                            )
                                        )
                                )
                        )
                    )
                )
            )
        ).toString()

        val conversation = XGraphqlParser.parseThreadDetail(rawThreadJson, focalTweetId = "100")
        assertEquals("100", conversation?.focalTweet?.id)
        assertEquals(listOf("201"), conversation?.replies?.map { it.id })
    }
}
