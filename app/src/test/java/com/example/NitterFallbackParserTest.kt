package com.example

import com.example.data.remote.NitterHtmlParser
import com.example.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NitterFallbackParserTest {

    @Test
    fun normalizeBaseUrl_defaultsToNitterNetbubAndNormalizesCustomHosts() {
        assertEquals("https://nitter.netbub.com", NitterHtmlParser.normalizeBaseUrl(""))
        assertEquals("https://nitter.netbub.com", NitterHtmlParser.normalizeBaseUrl("   "))
        assertEquals("nitter.netbub.com", NitterHtmlParser.displayHost(""))
        assertEquals("https://nitter.example.org", NitterHtmlParser.normalizeBaseUrl("nitter.example.org/"))
        assertEquals("https://nitter.netbub.com", NitterHtmlParser.normalizeBaseUrl("https://nitter.netbub.com/"))
        assertEquals("nitter.example.org", NitterHtmlParser.displayHost("https://www.nitter.example.org/"))
    }

    @Test
    fun resolveMediaUrl_decodesPicAndVideoProxyPathsToDirectCdnUrls() {
        val base = "https://nitter.netbub.com"
        assertEquals(
            "https://pbs.twimg.com/media/AbCdEf.jpg",
            NitterHtmlParser.resolveMediaUrl("/pic/orig/media%2FAbCdEf.jpg", base),
        )
        assertEquals(
            "https://pbs.twimg.com/profile_images/12345/avatar_400x400.jpg",
            NitterHtmlParser.resolveMediaUrl("/pic/profile_images%2F12345%2Favatar_400x400.jpg", base),
        )
        assertEquals(
            "https://pbs.twimg.com/profile_banners/99/1700000000/1500x500",
            NitterHtmlParser.resolveMediaUrl("/pic/profile_banners%2F99%2F1700000000%2F1500x500", base),
        )
        assertEquals(
            "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/clip.mp4",
            NitterHtmlParser.resolveMediaUrl(
                "/video/abc123/https%3A%2F%2Fvideo.twimg.com%2Fext_tw_video%2F1%2Fpu%2Fvid%2F720x1280%2Fclip.mp4",
                base,
            ),
        )
        assertEquals(
            "https://nitter.netbub.com/custom/thumb.png",
            NitterHtmlParser.resolveMediaUrl("/custom/thumb.png", base),
        )
    }

    @Test
    fun parseProfile_extractsFullMetadataBannerAndCounts() {
        val html = """
            <html><body>
              <div class="profile-banner"><a href="/pic/profile_banners%2F42%2F1700000%2F1500x500"><img src="/pic/profile_banners%2F42%2F1700000%2F600x200" /></a></div>
              <div class="profile-card">
                <a class="profile-card-avatar" href="/pic/profile_images%2F42%2Fpic_400x400.jpg"><img src="/pic/profile_images%2F42%2Fpic_normal.jpg" /></a>
                <a class="profile-card-fullname" href="/AndroidDev">Android Developers <span class="verified-icon"></span></a>
                <a class="profile-card-username" href="/AndroidDev">@AndroidDev</a>
                <div class="profile-bio"><p>Official news for Android &amp; Jetpack Compose.</p></div>
                <div class="profile-location">Mountain View, CA</div>
                <div class="profile-website"><a href="https://developer.android.com">developer.android.com</a></div>
                <div class="profile-joindate"><span title="Joined November 2009">Joined November 2009</span></div>
                <ul class="profile-statlist">
                  <li class="posts"><span class="profile-stat-header">Tweets</span><span class="profile-stat-num">12,345</span></li>
                  <li class="following"><span class="profile-stat-header">Following</span><span class="profile-stat-num">310</span></li>
                  <li class="followers"><span class="profile-stat-header">Followers</span><span class="profile-stat-num">2.4M</span></li>
                </ul>
              </div>
              <div class="timeline"></div>
            </body></html>
        """.trimIndent()

        val user = NitterHtmlParser.parseProfile(html, fallbackHandle = "AndroidDev")
        assertNotNull(user)
        assertEquals("AndroidDev", user!!.handle)
        assertEquals("Android Developers", user.displayName)
        assertTrue(user.isVerified)
        assertEquals("https://pbs.twimg.com/profile_images/42/pic_400x400.jpg", user.avatarUrl)
        assertEquals("https://pbs.twimg.com/profile_banners/42/1700000/1500x500", user.bannerUrl)
        assertEquals("Official news for Android & Jetpack Compose.", user.bio)
        assertEquals("Mountain View, CA", user.location)
        assertEquals("https://developer.android.com", user.websiteUrl)
        assertEquals("Joined November 2009", user.joinedLabel)
        assertEquals(12_345L, user.tweetsCount)
        assertEquals(310L, user.followingCount)
        assertEquals(2_400_000L, user.followersCount)
    }

    @Test
    fun parseTimelinePage_extractsPostsMediaQuotesStatsAndPaginationCursor() {
        val html = """
            <div class="timeline">
              <div class="timeline-item">
                <div class="retweet-header"><div>Kotlin retweeted</div></div>
                <div class="tweet-header">
                  <a class="tweet-avatar" href="/AndroidDev"><img class="avatar" src="/pic/profile_images%2F1%2Fa_normal.jpg" /></a>
                  <a class="fullname" href="/AndroidDev">Android Dev</a>
                  <a class="username" href="/AndroidDev">@AndroidDev</a>
                  <span class="verified-icon"></span>
                  <span class="tweet-date"><a href="/AndroidDev/status/1800000001#m" title="Oct 10, 2024 · 8:19 PM UTC">Oct 10</a></span>
                </div>
                <div class="tweet-content media-body">Jetpack Compose 1.8 is live!<br/>Check the notes.</div>
                <div class="attachments">
                  <a class="still-image" href="/pic/orig/media%2FComposeShot.jpg"><img src="/pic/media%2FComposeShot.jpg" alt="Compose screenshot" /></a>
                </div>
                <div class="quote quote-big">
                  <a class="quote-link" href="/kotlin/status/1799999999#m"></a>
                  <div class="tweet-name-row">
                    <a class="fullname" href="/kotlin">Kotlin</a>
                    <a class="username" href="/kotlin">@kotlin</a>
                  </div>
                  <div class="quote-text">Kotlin 2.1 release highlights!</div>
                </div>
                <div class="tweet-stats">
                  <span class="tweet-stat"><div class="icon-container"><span class="icon-comment"></span> 42</div></span>
                  <span class="tweet-stat"><div class="icon-container"><span class="icon-retweet"></span> 1.2K</div></span>
                  <span class="tweet-stat"><div class="icon-container"><span class="icon-quote"></span> 15</div></span>
                  <span class="tweet-stat"><div class="icon-container"><span class="icon-heart"></span> 5,400</div></span>
                </div>
              </div>
              <div class="show-more"><a href="/AndroidDev?cursor=DAABCgABGnextCursor123">Load more</a></div>
            </div>
        """.trimIndent()

        val page = NitterHtmlParser.parseTimelinePage(html)
        assertEquals(1, page.tweets.size)
        val tweet = page.tweets.first()
        assertEquals("1800000001", tweet.id)
        assertEquals("AndroidDev", tweet.author.handle)
        assertEquals("Kotlin", tweet.repostedBy)
        assertTrue(tweet.text.contains("Jetpack Compose 1.8 is live!"))
        assertTrue(tweet.text.contains("Check the notes."))
        assertEquals(1, tweet.media.size)
        assertEquals(MediaType.PHOTO, tweet.media.first().type)
        assertEquals("https://pbs.twimg.com/media/ComposeShot.jpg", tweet.media.first().url)
        assertEquals("Compose screenshot", tweet.media.first().altText)
        val quoted = requireNotNull(tweet.quotedTweet)
        assertEquals("1799999999", quoted.id)
        assertEquals("kotlin", quoted.author.handle)
        assertEquals(42L, tweet.metrics.replies)
        assertEquals(1_200L, tweet.metrics.reposts)
        assertEquals(15L, tweet.metrics.quotes)
        assertEquals(5_400L, tweet.metrics.likes)
        assertEquals("DAABCgABGnextCursor123", page.nextCursor)
    }

    @Test
    fun parseThreadConversation_extractsAncestorsFocalTweetAndReplies() {
        val html = """
            <div class="conversation">
              <div class="before-tweet thread-line">
                <div class="timeline-item">
                  <div class="tweet-header">
                    <a class="fullname" href="/alice">Alice</a>
                    <a class="username" href="/alice">@alice</a>
                    <span class="tweet-date"><a href="/alice/status/100#m" title="Oct 10, 2024 · 7:00 PM UTC">Oct 10</a></span>
                  </div>
                  <div class="tweet-content">Parent context post</div>
                </div>
              </div>
              <div id="m" class="main-tweet">
                <div class="timeline-item">
                  <div class="tweet-header">
                    <a class="fullname" href="/bob">Bob</a>
                    <a class="username" href="/bob">@bob</a>
                    <span class="tweet-date"><a href="/bob/status/200#m" title="Oct 10, 2024 · 7:05 PM UTC">Oct 10</a></span>
                  </div>
                  <div class="tweet-content">Focal tweet body</div>
                  <div class="tweet-stats">
                    <span class="tweet-stat"><div class="icon-container"><span class="icon-heart"></span> 99</div></span>
                  </div>
                </div>
              </div>
              <div class="replies">
                <div class="reply thread thread-line">
                  <div class="timeline-item">
                    <div class="tweet-header">
                      <a class="fullname" href="/carol">Carol</a>
                      <a class="username" href="/carol">@carol</a>
                      <span class="tweet-date"><a href="/carol/status/300#m" title="Oct 10, 2024 · 7:10 PM UTC">Oct 10</a></span>
                    </div>
                    <div class="tweet-content">Great reply!</div>
                  </div>
                </div>
                <div class="show-more"><a href="/bob/status/200?cursor=replyCursor99">Load more</a></div>
              </div>
            </div>
        """.trimIndent()

        val conv = NitterHtmlParser.parseThreadConversation(html, focalTweetId = "200")
        assertNotNull(conv)
        assertEquals(listOf("100"), conv!!.ancestors.map { it.id })
        assertEquals("200", conv.focalTweet.id)
        assertEquals("Focal tweet body", conv.focalTweet.text)
        assertEquals(99L, conv.focalTweet.metrics.likes)
        assertEquals(listOf("300"), conv.replies.map { it.id })
        assertEquals("replyCursor99", conv.bottomCursor)
    }

    @Test
    fun parseChallengeOrErrorHtml_returnsNullAndEmptySoFxTwitterSafetyNetTriggers() {
        val challengeHtml = "<html><head><title>Just a moment...</title></head><body>cf-browser-verification</body></html>"
        assertTrue(NitterHtmlParser.isChallengeOrErrorHtml(challengeHtml))
        assertNull(NitterHtmlParser.parseProfile(challengeHtml, "AndroidDev"))
        assertNull(NitterHtmlParser.parseThreadConversation(challengeHtml, "123"))
        assertTrue(NitterHtmlParser.parseTimelinePage(challengeHtml).tweets.isEmpty())
    }
}
