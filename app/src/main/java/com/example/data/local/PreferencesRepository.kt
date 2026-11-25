package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.FeedDensity
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.SwipeAction
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ilayPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ilay_for_x_prefs"
)

/** Onboarding hints auto-expire 24 hours after initial install (or immediately when dismissed). */
private const val ONBOARDING_HINT_WINDOW_MS = 24L * 60L * 60L * 1000L

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledDark: Boolean = true,
    val useDynamicColor: Boolean = false,
    val accentPreset: AccentPreset = AccentPreset.SKY,
    val feedDensity: FeedDensity = FeedDensity.CARD,
    val imageQualityMode: ImageQualityMode = ImageQualityMode.AUTO,
    val swipeRightAction: SwipeAction = SwipeAction.LIKE,
    val swipeLeftAction: SwipeAction = SwipeAction.BOOKMARK,
    val recencyWeight: Float = 0.55f,
    val affinityWeight: Float = 0.70f,
    val blurSensitiveMedia: Boolean = true,
    val showLocalRankBadges: Boolean = false,
    val demoteSeenPosts: Boolean = true,
    val blendMultiSourceForYou: Boolean = true,
    val excludeRiskyAndNsfw: Boolean = true,
    val customProxyBaseUrl: String = "",
    val publicPreviewHandlesCsv: String = "AndroidDev,kotlin,NASA,TheVerge,GitHub,MKBHD",
    val firstInstallEpochMs: Long = 0L,
    val onboardingHintsDismissed: Boolean = false,
) {
    val publicPreviewHandles: List<String>
        get() = publicPreviewHandlesCsv
            .split(",")
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotBlank() }

    /** Shows brief onboarding hints only during the first 24 hours post-install until dismissed. */
    val showOnboardingHints: Boolean
        get() = !onboardingHintsDismissed &&
            firstInstallEpochMs > 0L &&
            (System.currentTimeMillis() - firstInstallEpochMs) in 0L until ONBOARDING_HINT_WINDOW_MS
}

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED_DARK = booleanPreferencesKey("amoled_dark")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val FEED_DENSITY = stringPreferencesKey("feed_density")
        val IMAGE_QUALITY_MODE = stringPreferencesKey("image_quality_mode")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left")
        val RECENCY_WEIGHT = floatPreferencesKey("recency_weight")
        val AFFINITY_WEIGHT = floatPreferencesKey("affinity_weight")
        val BLUR_SENSITIVE = booleanPreferencesKey("blur_sensitive")
        val SHOW_RANK_BADGES = booleanPreferencesKey("show_rank_badges")
        val DEMOTE_SEEN_POSTS = booleanPreferencesKey("demote_seen_posts")
        val BLEND_MULTI_SOURCE_FOR_YOU = booleanPreferencesKey("blend_multi_source_for_you")
        val EXCLUDE_RISKY_AND_NSFW = booleanPreferencesKey("exclude_risky_and_nsfw")
        val CUSTOM_PROXY_URL = stringPreferencesKey("custom_proxy_url")
        val PUBLIC_HANDLES = stringPreferencesKey("public_handles")
        val FIRST_INSTALL_EPOCH_MS = longPreferencesKey("first_install_epoch_ms")
        val ONBOARDING_HINTS_DISMISSED = booleanPreferencesKey("onboarding_hints_dismissed")
        val CACHED_TRENDS_JSON = stringPreferencesKey("cached_trends_json")
        val CACHED_TRENDS_TIMESTAMP_MS = longPreferencesKey("cached_trends_timestamp_ms")
    }

    val preferencesFlow: Flow<AppPreferences> = context.ilayPreferencesDataStore.data.map { prefs ->
        val rawTheme = prefs[Keys.THEME_MODE]
        val legacyWasAmoled = rawTheme == "AMOLED"
        val resolvedMode = when {
            legacyWasAmoled -> ThemeMode.DARK
            rawTheme != null -> runCatching { ThemeMode.valueOf(rawTheme) }.getOrNull() ?: ThemeMode.SYSTEM
            else -> ThemeMode.SYSTEM
        }
        val resolvedAmoled = prefs[Keys.AMOLED_DARK] ?: true
        AppPreferences(
            themeMode = resolvedMode,
            amoledDark = resolvedAmoled,
            useDynamicColor = prefs[Keys.USE_DYNAMIC_COLOR] ?: false,
            accentPreset = prefs[Keys.ACCENT_PRESET]?.let { runCatching { AccentPreset.valueOf(it) }.getOrNull() }
                ?: AccentPreset.SKY,
            feedDensity = prefs[Keys.FEED_DENSITY]?.let { runCatching { FeedDensity.valueOf(it) }.getOrNull() }
                ?: FeedDensity.CARD,
            imageQualityMode = prefs[Keys.IMAGE_QUALITY_MODE]?.let { runCatching { ImageQualityMode.valueOf(it) }.getOrNull() }
                ?: ImageQualityMode.AUTO,
            swipeRightAction = prefs[Keys.SWIPE_RIGHT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() }
                ?: SwipeAction.LIKE,
            swipeLeftAction = prefs[Keys.SWIPE_LEFT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() }
                ?: SwipeAction.BOOKMARK,
            recencyWeight = prefs[Keys.RECENCY_WEIGHT] ?: 0.55f,
            affinityWeight = prefs[Keys.AFFINITY_WEIGHT] ?: 0.70f,
            blurSensitiveMedia = prefs[Keys.BLUR_SENSITIVE] ?: true,
            showLocalRankBadges = prefs[Keys.SHOW_RANK_BADGES] ?: false,
            demoteSeenPosts = prefs[Keys.DEMOTE_SEEN_POSTS] ?: true,
            blendMultiSourceForYou = prefs[Keys.BLEND_MULTI_SOURCE_FOR_YOU] ?: true,
            excludeRiskyAndNsfw = prefs[Keys.EXCLUDE_RISKY_AND_NSFW] ?: true,
            customProxyBaseUrl = prefs[Keys.CUSTOM_PROXY_URL] ?: "",
            publicPreviewHandlesCsv = prefs[Keys.PUBLIC_HANDLES]
                ?: "AndroidDev,kotlin,NASA,TheVerge,GitHub,MKBHD",
            firstInstallEpochMs = prefs[Keys.FIRST_INSTALL_EPOCH_MS] ?: 0L,
            onboardingHintsDismissed = prefs[Keys.ONBOARDING_HINTS_DISMISSED] ?: false,
        )
    }

    suspend fun ensureFirstInstallRecorded() {
        context.ilayPreferencesDataStore.edit { prefs ->
            if (prefs[Keys.FIRST_INSTALL_EPOCH_MS] == null) {
                prefs[Keys.FIRST_INSTALL_EPOCH_MS] = System.currentTimeMillis()
            }
        }
    }

    suspend fun dismissOnboardingHints() {
        context.ilayPreferencesDataStore.edit { it[Keys.ONBOARDING_HINTS_DISMISSED] = true }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.ilayPreferencesDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAmoledDark(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.AMOLED_DARK] = enabled }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.USE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAccentPreset(preset: AccentPreset) {
        context.ilayPreferencesDataStore.edit { it[Keys.ACCENT_PRESET] = preset.name }
    }

    suspend fun setFeedDensity(density: FeedDensity) {
        context.ilayPreferencesDataStore.edit { it[Keys.FEED_DENSITY] = density.name }
    }

    suspend fun setImageQualityMode(mode: ImageQualityMode) {
        context.ilayPreferencesDataStore.edit { it[Keys.IMAGE_QUALITY_MODE] = mode.name }
    }

    suspend fun setSwipeRightAction(action: SwipeAction) {
        context.ilayPreferencesDataStore.edit { it[Keys.SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        context.ilayPreferencesDataStore.edit { it[Keys.SWIPE_LEFT] = action.name }
    }

    suspend fun setRecencyWeight(value: Float) {
        context.ilayPreferencesDataStore.edit { it[Keys.RECENCY_WEIGHT] = value.coerceIn(0f, 1f) }
    }

    suspend fun setAffinityWeight(value: Float) {
        context.ilayPreferencesDataStore.edit { it[Keys.AFFINITY_WEIGHT] = value.coerceIn(0f, 1f) }
    }

    suspend fun setBlurSensitiveMedia(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.BLUR_SENSITIVE] = enabled }
    }

    suspend fun setShowLocalRankBadges(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.SHOW_RANK_BADGES] = enabled }
    }

    suspend fun setDemoteSeenPosts(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.DEMOTE_SEEN_POSTS] = enabled }
    }

    suspend fun setBlendMultiSourceForYou(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.BLEND_MULTI_SOURCE_FOR_YOU] = enabled }
    }

    suspend fun setExcludeRiskyAndNsfw(enabled: Boolean) {
        context.ilayPreferencesDataStore.edit { it[Keys.EXCLUDE_RISKY_AND_NSFW] = enabled }
    }

    suspend fun setCustomProxyBaseUrl(url: String) {
        context.ilayPreferencesDataStore.edit { it[Keys.CUSTOM_PROXY_URL] = url.trim() }
    }

    suspend fun setPublicPreviewHandlesCsv(csv: String) {
        context.ilayPreferencesDataStore.edit { it[Keys.PUBLIC_HANDLES] = csv.trim() }
    }

    suspend fun getCachedTrendsSnapshot(): Pair<String?, Long> {
        val prefs = context.ilayPreferencesDataStore.data.first()
        val json = prefs[Keys.CACHED_TRENDS_JSON]
        val timestamp = prefs[Keys.CACHED_TRENDS_TIMESTAMP_MS] ?: 0L
        return json to timestamp
    }

    suspend fun saveCachedTrendsSnapshot(json: String, fetchedAtEpochMs: Long) {
        context.ilayPreferencesDataStore.edit { prefs ->
            prefs[Keys.CACHED_TRENDS_JSON] = json
            prefs[Keys.CACHED_TRENDS_TIMESTAMP_MS] = fetchedAtEpochMs
        }
    }

    suspend fun clearCachedTrendsSnapshot() {
        context.ilayPreferencesDataStore.edit { prefs ->
            prefs.remove(Keys.CACHED_TRENDS_JSON)
            prefs.remove(Keys.CACHED_TRENDS_TIMESTAMP_MS)
        }
    }
}
