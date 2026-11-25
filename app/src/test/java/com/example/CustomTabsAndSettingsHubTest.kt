package com.example

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.remote.NetworkTier
import com.example.domain.model.FeedDensity
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.SwipeAction
import com.example.ui.components.InAppCustomTabsLauncher
import com.example.ui.components.LinkVerificationStatus
import com.example.ui.screens.SettingsCategory
import com.example.ui.screens.SettingsCategoryScreen
import com.example.ui.screens.SettingsHubSummaryFormatter
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.IlayForXTheme
import com.example.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomTabsAndSettingsHubTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun customTabsLauncher_normalizesValidWebUrlsAndRejectsUnsafeSchemes() {
        assertEquals("https://example.com/article", InAppCustomTabsLauncher.normalizeWebUrl("https://example.com/article"))
        assertEquals("http://news.ycombinator.com", InAppCustomTabsLauncher.normalizeWebUrl("http://news.ycombinator.com"))
        assertEquals("https://github.com/android", InAppCustomTabsLauncher.normalizeWebUrl("github.com/android"))

        assertNull(InAppCustomTabsLauncher.normalizeWebUrl(""))
        assertNull(InAppCustomTabsLauncher.normalizeWebUrl("javascript:alert(1)"))
        assertNull(InAppCustomTabsLauncher.normalizeWebUrl("file:///etc/passwd"))
        assertNull(InAppCustomTabsLauncher.normalizeWebUrl("content://media/external/images/1"))
    }

    @Test
    fun customTabsLauncher_buildsThemedIntentWithExplicitAntiLoopBrowserPackage() {
        val toolbarColor = 0xFF0B0E14.toInt()
        val customTabsIntent = InAppCustomTabsLauncher.buildCustomTabsIntent(
            context = appContext,
            toolbarColorArgb = toolbarColor,
            isDarkTheme = true,
            targetBrowserPackage = "org.mozilla.firefox",
        )

        val rawIntent = customTabsIntent.intent
        assertEquals("org.mozilla.firefox", rawIntent.`package`)
        assertEquals(CustomTabsIntent.COLOR_SCHEME_DARK, rawIntent.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1))
        assertEquals(CustomTabsIntent.SHOW_PAGE_TITLE, rawIntent.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1))
        assertEquals(CustomTabsIntent.SHARE_STATE_ON, rawIntent.getIntExtra(CustomTabsIntent.EXTRA_SHARE_STATE, -1))
        assertTrue((rawIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertNotNull(rawIntent.extras)
    }

    @Test
    fun customTabsLauncher_neverTargetsOwnPackageToPreventRedirectLoops() {
        val customTabsIntent = InAppCustomTabsLauncher.buildCustomTabsIntent(
            context = appContext,
            toolbarColorArgb = 0xFFFFFFFF.toInt(),
            isDarkTheme = false,
            targetBrowserPackage = appContext.packageName,
        )
        assertNull(customTabsIntent.intent.`package`)

        val fallback = InAppCustomTabsLauncher.buildFallbackBrowserIntent(
            context = appContext,
            normalizedUrl = "https://x.com/i/spaces/123",
            targetBrowserPackage = appContext.packageName,
        )
        assertNull(fallback.`package`)
        assertTrue(fallback.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun settingsHubSummaryFormatter_formatsAllSixLiveSummariesAccurately() {
        val prefs = AppPreferences(
            themeMode = ThemeMode.DARK,
            amoledDark = true,
            useDynamicColor = false,
            accentPreset = AccentPreset.SKY,
            feedDensity = FeedDensity.CARD,
            imageQualityMode = ImageQualityMode.AUTO,
            swipeRightAction = SwipeAction.LIKE,
            swipeLeftAction = SwipeAction.BOOKMARK,
            blurSensitiveMedia = true,
            excludeRiskyAndNsfw = true,
            customProxyBaseUrl = "",
        )

        assertEquals(
            "Dark · AMOLED · Sky Cobalt · Card view",
            SettingsHubSummaryFormatter.appearanceSummary(prefs),
        )
        assertEquals(
            "Swipe Like / Bookmark · 12 creators tracked",
            SettingsHubSummaryFormatter.feedRankingSummary(prefs, trackedSafeCreatorCount = 12),
        )
        assertEquals(
            "NSFW firewall on · Blur on · 3 muted, 1 blocked",
            SettingsHubSummaryFormatter.safetySummary(prefs, mutedKeywordCount = 2, mutedAccountCount = 1, blockedAccountCount = 1),
        )
        assertEquals(
            "Adaptive quality · Wi-Fi",
            SettingsHubSummaryFormatter.mediaSummary(prefs, NetworkTier.UNMETERED_FAST),
        )
        assertEquals(
            "Active for 5 of 8 domains",
            SettingsHubSummaryFormatter.linksSummary(
                linkStatus = LinkVerificationStatus(enabledCount = 5, totalCount = 8, requiresSystemApproval = true),
            ),
        )
        val account = AccountEntity(
            userId = "42",
            handle = "ilaydev",
            displayName = "Ilay Dev",
            avatarUrl = "",
            bannerUrl = "",
            authToken = "tok",
            ct0 = "ct0",
            isActive = true,
        )
        assertEquals(
            "@ilaydev (+1) · 24 endpoints synced",
            SettingsHubSummaryFormatter.advancedSummary(
                activeAccount = account,
                accountCount = 2,
                endpointCount = 24,
                hasCustomProxy = false,
            ),
        )
    }

    @Test
    fun settingsHub_displaysSixCategoriesAndNavigatesToSubpagesAndBack() {
        val prefs = AppPreferences(
            themeMode = ThemeMode.DARK,
            amoledDark = true,
            accentPreset = AccentPreset.SKY,
        )

        composeRule.setContent {
            IlayForXTheme(themeMode = ThemeMode.DARK, amoledDark = true) {
                var activeCategory by remember { mutableStateOf<SettingsCategory?>(null) }
                if (activeCategory == null) {
                    SettingsScreen(
                        preferences = prefs,
                        activeAccount = null,
                        accountCount = 0,
                        mutedRules = emptyList(),
                        userModerations = emptyList(),
                        affinities = emptyList(),
                        endpointDiagnostics = emptyList(),
                        onBack = {},
                        onOpenCategory = { activeCategory = it },
                        onOpenAccountSwitcher = {},
                    )
                } else {
                    SettingsCategoryScreen(
                        category = activeCategory!!,
                        preferences = prefs,
                        activeAccount = null,
                        accountCount = 0,
                        mutedRules = emptyList(),
                        userModerations = emptyList(),
                        affinities = emptyList(),
                        endpointDiagnostics = emptyList(),
                        isRefreshingRegistry = false,
                        onBack = { activeCategory = null },
                        onSetThemeMode = {},
                        onSetAmoledDark = {},
                        onSetDynamicColor = {},
                        onSetAccentPreset = {},
                        onSetFeedDensity = {},
                        onSetImageQualityMode = {},
                        onSetSwipeRight = {},
                        onSetSwipeLeft = {},
                        onSetRecencyWeight = {},
                        onSetAffinityWeight = {},
                        onSetBlurSensitive = {},
                        onSetExcludeRiskyAndNsfw = {},
                        onSetShowRankBadges = {},
                        onAddMutedRule = { _, _ -> },
                        onRemoveMutedRule = {},
                        onUnmuteUser = {},
                        onUnblockUser = {},
                        onResetForYou = {},
                        onSaveProxyUrl = {},
                        onSavePublicHandles = {},
                        onRefreshRegistry = {},
                        onOpenAccountSwitcher = {},
                        onOpenInterestInspector = {},
                    )
                }
            }
        }

        // Verify Hub and all 6 category rows render with live summaries
        composeRule.onNodeWithTag("settings_hub_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_summary_appearance", useUnmergedTree = true)
            .assertTextContains("Dark · AMOLED", substring = true)

        // Tap through key categories and verify their dedicated subpage controls render
        val categoryChecks = listOf(
            SettingsCategory.APPEARANCE to "amoled_dark_toggle",
            SettingsCategory.FEED_RANKING to "settings_open_inspector_button",
            SettingsCategory.SAFETY to "safety_nsfw_firewall_toggle",
            SettingsCategory.MEDIA to "image_quality_policy_summary",
            SettingsCategory.X_LINKS to "settings_open_default_links_button",
            SettingsCategory.ADVANCED to "refresh_endpoints_button",
        )

        categoryChecks.forEach { (category, expectedChildTag) ->
            composeRule.onNodeWithTag("settings_hub_list")
                .performScrollToNode(hasTestTag("settings_category_${category.routeArg}"))
            composeRule.onNodeWithTag("settings_category_${category.routeArg}").performClick()
            composeRule.onNodeWithTag("settings_subpage_${category.routeArg}").assertIsDisplayed()
            composeRule.onNodeWithTag("settings_subpage_list")
                .performScrollToNode(hasTestTag(expectedChildTag))
            composeRule.onNodeWithTag(expectedChildTag).assertIsDisplayed()
            if (category == SettingsCategory.X_LINKS) {
                composeRule.onNodeWithTag("settings_open_default_browser_button").assertDoesNotExist()
            }
            composeRule.onNodeWithTag("settings_category_back_button").performClick()
            composeRule.onNodeWithTag("settings_hub_screen").assertIsDisplayed()
        }
    }
}
