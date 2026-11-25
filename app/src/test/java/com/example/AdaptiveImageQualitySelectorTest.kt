package com.example

import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.data.remote.IlayImageCache
import com.example.data.remote.ImageUsage
import com.example.data.remote.NetworkTier
import com.example.data.remote.XClientTransactionMath
import com.example.data.remote.XGraphqlParser
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveImageQualitySelectorTest {

    private val rawPathPhoto = "https://pbs.twimg.com/media/Gb12345XAAAbcde.jpg"
    private val rawQueryPhoto = "https://pbs.twimg.com/media/Gb12345XAAAbcde?format=jpg&name=large"

    @Test
    fun timelineCard_autoOnWifi_selectsSmallPreviewNotFullRes() {
        val variant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawPathPhoto,
            usage = ImageUsage.TIMELINE_CARD,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )

        assertEquals("$rawPathPhoto?name=small", variant.url)
        assertFalse(variant.isFullResolution)
        assertTrue(variant.allowRgb565)
        assertEquals(680, variant.targetWidthPx)
    }

    @Test
    fun timelineCard_autoOnCellularOrDataSaver_selects360pxPreview() {
        val cellular = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawQueryPhoto,
            usage = ImageUsage.TIMELINE_CARD,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.METERED_CELLULAR,
        )
        val systemDataSaver = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawPathPhoto,
            usage = ImageUsage.TIMELINE_CARD,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.DATA_SAVER_RESTRICTED,
        )

        assertEquals("https://pbs.twimg.com/media/Gb12345XAAAbcde?format=jpg&name=360x360", cellular.url)
        assertEquals("$rawPathPhoto?name=360x360", systemDataSaver.url)
        assertFalse(cellular.isFullResolution)
        assertEquals(360, cellular.targetWidthPx)
    }

    @Test
    fun timelineCard_dataSaverMode_alwaysSelects360pxEvenOnFastWifi() {
        val variant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawPathPhoto,
            usage = ImageUsage.TIMELINE_CARD,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.UNMETERED_FAST,
        )

        assertEquals("$rawPathPhoto?name=360x360", variant.url)
        assertFalse(variant.isFullResolution)
    }

    @Test
    fun compactThumbnail_alwaysSelects360pxRegardlessOfHighMode() {
        val variant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawQueryPhoto,
            usage = ImageUsage.COMPACT_THUMBNAIL,
            mode = ImageQualityMode.HIGH,
            networkTier = NetworkTier.UNMETERED_FAST,
        )

        assertEquals("https://pbs.twimg.com/media/Gb12345XAAAbcde?format=jpg&name=360x360", variant.url)
        assertFalse(variant.isFullResolution)
        assertEquals(360, variant.targetWidthPx)
        assertEquals(360, variant.targetHeightPx)
    }

    @Test
    fun expandedLightbox_upgradesToOriginalOnWifiAndLargeOnCellular() {
        val wifiExpanded = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawQueryPhoto,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )
        val cellularExpanded = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawPathPhoto,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.METERED_CELLULAR,
        )
        val cellularForcedOrig = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawPathPhoto,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.METERED_CELLULAR,
            forceOriginal = true,
        )

        assertEquals("https://pbs.twimg.com/media/Gb12345XAAAbcde?format=jpg&name=orig", wifiExpanded.url)
        assertTrue(wifiExpanded.isFullResolution)
        assertFalse(wifiExpanded.allowRgb565)
        assertEquals("ORIG", wifiExpanded.variantLabel)

        assertEquals("$rawPathPhoto?name=large", cellularExpanded.url)
        assertTrue(cellularExpanded.isFullResolution)
        assertEquals("HD", cellularExpanded.variantLabel)

        assertEquals("$rawPathPhoto?name=orig", cellularForcedOrig.url)
        assertEquals("ORIG", cellularForcedOrig.variantLabel)
    }

    @Test
    fun avatarAndBanner_rewriteTwimgVariantsForBandwidthSavings() {
        val avatarUrl = "https://pbs.twimg.com/profile_images/12345/photo_400x400.jpg"
        val wifiAvatar = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = avatarUrl,
            usage = ImageUsage.AVATAR,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )
        val cellularAvatar = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = avatarUrl,
            usage = ImageUsage.AVATAR,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.METERED_CELLULAR,
        )
        assertEquals("https://pbs.twimg.com/profile_images/12345/photo_200x200.jpg", wifiAvatar.url)
        assertEquals("https://pbs.twimg.com/profile_images/12345/photo_bigger.jpg", cellularAvatar.url)

        val bannerUrl = "https://pbs.twimg.com/profile_banners/98765/1700000000"
        val cellularBanner = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = bannerUrl,
            usage = ImageUsage.PROFILE_BANNER,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.METERED_CELLULAR,
        )
        val wifiBanner = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = "$bannerUrl/1500x500",
            usage = ImageUsage.PROFILE_BANNER,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )
        assertEquals("$bannerUrl/600x200", cellularBanner.url)
        // Verified X CDN suffix is 1500x500 (never 404-prone 1080x360), with 1080x360 decode target in Adaptive mode
        assertEquals("$bannerUrl/1500x500", wifiBanner.url)
        assertEquals(1080, wifiBanner.targetWidthPx)
        assertEquals(360, wifiBanner.targetHeightPx)
    }

    @Test
    fun nonTwimgUrl_isPreservedUnmodifiedWithAdaptiveTargetDimensions() {
        val externalUrl = "https://cdn.example.org/hero/article-image.webp?v=2"
        val preview = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = externalUrl,
            usage = ImageUsage.TIMELINE_CARD,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.METERED_CELLULAR,
        )
        assertEquals(externalUrl, preview.url)
        assertEquals(360, preview.targetWidthPx)
        assertFalse(preview.isFullResolution)
    }

    @Test
    fun previewCanonicalKey_matchesBetweenTimelineCardAndPreviewHelper() {
        val cardVariant = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawQueryPhoto,
            usage = ImageUsage.TIMELINE_CARD,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )
        val helperUrl = AdaptiveImageQualitySelector.previewUrlFor(
            rawUrl = rawQueryPhoto,
            compact = false,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
        )
        assertEquals(IlayImageCache.canonicalKey(cardVariant.url), IlayImageCache.canonicalKey(helperUrl))
    }

    @Test
    fun expandedLightbox_dataSaverCapsPhotoAt1080pAndUpgradesWhenForcedOriginal() {
        val dataSaverExpanded = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawQueryPhoto,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.UNMETERED_FAST,
            forceOriginal = false,
        )
        assertEquals("https://pbs.twimg.com/media/Gb12345XAAAbcde?format=jpg&name=medium", dataSaverExpanded.url)
        assertEquals("1080p", dataSaverExpanded.variantLabel)
        assertEquals(1920, dataSaverExpanded.targetWidthPx)
        assertEquals(1080, dataSaverExpanded.targetHeightPx)
        assertFalse(dataSaverExpanded.isFullResolution)

        val systemDataSaverExpanded = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawPathPhoto,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.DATA_SAVER_RESTRICTED,
            forceOriginal = false,
        )
        assertEquals("$rawPathPhoto?name=medium", systemDataSaverExpanded.url)
        assertEquals("1080p", systemDataSaverExpanded.variantLabel)
        assertFalse(systemDataSaverExpanded.isFullResolution)

        val upgraded = AdaptiveImageQualitySelector.selectVariant(
            rawUrl = rawQueryPhoto,
            usage = ImageUsage.EXPANDED_LIGHTBOX,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.UNMETERED_FAST,
            forceOriginal = true,
        )
        assertEquals("https://pbs.twimg.com/media/Gb12345XAAAbcde?format=jpg&name=orig", upgraded.url)
        assertEquals("ORIG", upgraded.variantLabel)
        assertTrue(upgraded.isFullResolution)
    }

    @Test
    fun videoVariants_dataSaverCapsAt1080pAndUpgradesOnDemand() {
        val variants = JSONArray().apply {
            put(JSONObject().put("content_type", "application/x-mpegURL").put("url", "https://video.twimg.com/v/master.m3u8"))
            put(JSONObject().put("content_type", "video/mp4").put("bitrate", 632_000).put("url", "https://video.twimg.com/ext_tw_video/1/pu/vid/640x360/low.mp4"))
            put(JSONObject().put("content_type", "video/mp4").put("bitrate", 2_176_000).put("url", "https://video.twimg.com/ext_tw_video/1/pu/vid/1920x1080/hd1080.mp4"))
            put(JSONObject().put("content_type", "video/mp4").put("bitrate", 10_368_000).put("url", "https://video.twimg.com/ext_tw_video/1/pu/vid/3840x2160/uhd4k.mp4"))
        }

        val (bestUrl, dataSaverUrl) = AdaptiveImageQualitySelector.pickVideoVariants(variants)
        assertEquals("https://video.twimg.com/ext_tw_video/1/pu/vid/3840x2160/uhd4k.mp4", bestUrl)
        assertEquals("https://video.twimg.com/ext_tw_video/1/pu/vid/1920x1080/hd1080.mp4", dataSaverUrl)

        val media = MediaAttachment(
            id = "v1",
            type = MediaType.VIDEO,
            url = "https://pbs.twimg.com/thumb.jpg",
            videoUrl = bestUrl,
            dataSaverVideoUrl = dataSaverUrl,
        )
        val (saverPicked, saverCapped) = AdaptiveImageQualitySelector.selectVideoUrl(
            media = media,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.UNMETERED_FAST,
            forceOriginal = false,
        )
        assertEquals(dataSaverUrl, saverPicked)
        assertTrue(saverCapped)

        val (forcedMax, forcedCapped) = AdaptiveImageQualitySelector.selectVideoUrl(
            media = media,
            mode = ImageQualityMode.DATA_SAVER,
            networkTier = NetworkTier.UNMETERED_FAST,
            forceOriginal = true,
        )
        assertEquals(bestUrl, forcedMax)
        assertFalse(forcedCapped)

        val (normalPicked, normalCapped) = AdaptiveImageQualitySelector.selectVideoUrl(
            media = media,
            mode = ImageQualityMode.AUTO,
            networkTier = NetworkTier.UNMETERED_FAST,
            forceOriginal = false,
        )
        assertEquals(bestUrl, normalPicked)
        assertFalse(normalCapped)
    }

    @Test
    fun graphqlUserParser_extractsBannerAcrossLegacyAndModernSchemas() {
        val modernJson = JSONObject().apply {
            put("rest_id", "777")
            put("core", JSONObject().put("screen_name", "modern_user").put("name", "Modern User"))
            put("profile_banner", JSONObject().put("image_url", "https://pbs.twimg.com/profile_banners/777/1700000001"))
        }
        val legacyJson = JSONObject().apply {
            put("rest_id", "888")
            put("legacy", JSONObject()
                .put("screen_name", "legacy_user")
                .put("name", "Legacy User")
                .put("profile_banner_url", "https://pbs.twimg.com/profile_banners/888/1700000002"))
        }
        val nestedBannerJson = JSONObject().apply {
            put("rest_id", "999")
            put("core", JSONObject().put("screen_name", "nested_user").put("name", "Nested User"))
            put("banner", JSONObject().put("url", "https://pbs.twimg.com/profile_banners/999/1700000003"))
        }

        assertEquals("https://pbs.twimg.com/profile_banners/777/1700000001", XGraphqlParser.parseUserObject(modernJson)?.bannerUrl)
        assertEquals("https://pbs.twimg.com/profile_banners/888/1700000002", XGraphqlParser.parseUserObject(legacyJson)?.bannerUrl)
        assertEquals("https://pbs.twimg.com/profile_banners/999/1700000003", XGraphqlParser.parseUserObject(nestedBannerJson)?.bannerUrl)
    }

    @Test
    fun transactionMath_pureKotlinBase64NoPadMatchesRfc4648() {
        assertEquals("", XClientTransactionMath.encodeBase64NoPad(byteArrayOf()))
        assertEquals("Zg", XClientTransactionMath.encodeBase64NoPad("f".toByteArray()))
        assertEquals("Zm8", XClientTransactionMath.encodeBase64NoPad("fo".toByteArray()))
        assertEquals("Zm9v", XClientTransactionMath.encodeBase64NoPad("foo".toByteArray()))
        assertEquals("Zm9vYg", XClientTransactionMath.encodeBase64NoPad("foob".toByteArray()))
        assertEquals("Zm9vYmE", XClientTransactionMath.encodeBase64NoPad("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", XClientTransactionMath.encodeBase64NoPad("foobar".toByteArray()))
    }
}
