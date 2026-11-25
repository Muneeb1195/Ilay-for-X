package com.example.ui.screens

import androidx.annotation.StringRes
import com.example.R
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.remote.NetworkTier
import com.example.domain.model.FeedDensity
import com.example.ui.components.LinkVerificationStatus
import com.example.ui.theme.ThemeMode

/**
 * The 6 focused Settings category subpages navigated from the top-level Settings Hub.
 */
enum class SettingsCategory(
    val routeArg: String,
    @StringRes val titleRes: Int,
) {
    APPEARANCE("appearance", R.string.settings_cat_appearance_title),
    FEED_RANKING("feed_ranking", R.string.settings_cat_feed_ranking_title),
    SAFETY("safety", R.string.settings_cat_safety_title),
    MEDIA("media", R.string.settings_cat_media_title),
    X_LINKS("links", R.string.settings_cat_links_title),
    ADVANCED("advanced", R.string.settings_cat_advanced_title);

    companion object {
        fun fromRouteArg(arg: String?): SettingsCategory =
            entries.firstOrNull { it.routeArg.equals(arg, ignoreCase = true) } ?: APPEARANCE
    }
}

/**
 * Pure, unit-testable formatter producing concise 1-line live configuration summaries
 * for each category row on the Settings Hub.
 */
object SettingsHubSummaryFormatter {

    fun appearanceSummary(preferences: AppPreferences): String {
        val themeLabel = when (preferences.themeMode) {
            ThemeMode.SYSTEM -> "System"
            ThemeMode.DARK -> "Dark"
            ThemeMode.LIGHT -> "Light"
        }
        val parts = mutableListOf(themeLabel)
        if (preferences.amoledDark && preferences.themeMode != ThemeMode.LIGHT) {
            parts.add("AMOLED")
        }
        parts.add(if (preferences.useDynamicColor) "Dynamic color" else preferences.accentPreset.label)
        parts.add(if (preferences.feedDensity == FeedDensity.CARD) "Card view" else "Compact list")
        return parts.joinToString(" · ")
    }

    fun feedRankingSummary(
        preferences: AppPreferences,
        trackedSafeCreatorCount: Int,
    ): String {
        val swipePart = "Swipe ${preferences.swipeRightAction.label} / ${preferences.swipeLeftAction.label}"
        val creatorPart = when (trackedSafeCreatorCount) {
            0 -> "On-device ranker ready"
            1 -> "1 creator tracked"
            else -> "$trackedSafeCreatorCount creators tracked"
        }
        return "$swipePart · $creatorPart"
    }

    fun safetySummary(
        preferences: AppPreferences,
        mutedKeywordCount: Int,
        mutedAccountCount: Int,
        blockedAccountCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(if (preferences.excludeRiskyAndNsfw) "NSFW firewall on" else "NSFW firewall paused")
        if (preferences.blurSensitiveMedia) {
            parts.add("Blur on")
        }
        val totalFilters = mutedKeywordCount + mutedAccountCount + blockedAccountCount
        if (totalFilters > 0) {
            val filterParts = mutableListOf<String>()
            val totalMuted = mutedKeywordCount + mutedAccountCount
            if (totalMuted > 0) filterParts.add("$totalMuted muted")
            if (blockedAccountCount > 0) filterParts.add("$blockedAccountCount blocked")
            parts.add(filterParts.joinToString(", "))
        } else {
            parts.add("No muted filters")
        }
        return parts.joinToString(" · ")
    }

    fun mediaSummary(
        preferences: AppPreferences,
        networkTier: NetworkTier,
    ): String {
        return "${preferences.imageQualityMode.label} quality · ${networkTier.label}"
    }

    fun linksSummary(linkStatus: LinkVerificationStatus): String {
        return when {
            !linkStatus.requiresSystemApproval -> "Ready to open X & Twitter links"
            linkStatus.enabledCount > 0 -> "Active for ${linkStatus.enabledCount} of ${linkStatus.totalCount} domains"
            else -> "Approval needed in Android Settings"
        }
    }

    fun advancedSummary(
        activeAccount: AccountEntity?,
        accountCount: Int,
        endpointCount: Int,
        hasCustomProxy: Boolean,
        fallbackHost: String = "",
    ): String {
        val accountPart = when {
            activeAccount != null && accountCount > 1 -> "@${activeAccount.handle} (+${accountCount - 1})"
            activeAccount != null -> "@${activeAccount.handle}"
            else -> "Public Preview"
        }
        val endpointPart = if (endpointCount > 0) {
            "$endpointCount endpoints synced"
        } else {
            "Sync X endpoints"
        }
        return when {
            fallbackHost.isNotBlank() -> "$accountPart · $fallbackHost · $endpointPart"
            hasCustomProxy -> "$accountPart · Custom proxy · $endpointPart"
            else -> "$accountPart · $endpointPart"
        }
    }
}
