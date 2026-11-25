package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable

@Immutable
data class LinkVerificationStatus(
    val enabledCount: Int,
    val totalCount: Int,
    val requiresSystemApproval: Boolean,
    val enabledDomains: Set<String> = emptySet(),
)

/**
 * Inspects Android 12+ (`DomainVerificationManager`) per-domain link approval state for
 * Ilay for X and opens the system "Open by default" settings page with graceful fallback.
 */
object DefaultLinkHandlerHelper {
    val TRACKED_DOMAINS: List<String> = listOf(
        "x.com",
        "twitter.com",
        "mobile.x.com",
        "mobile.twitter.com",
        "fxtwitter.com",
        "vxtwitter.com",
        "fixupx.com",
        "fixvx.com",
    )

    val DISPLAY_DOMAIN_CHIPS: List<String> = listOf(
        "x.com",
        "twitter.com",
        "fxtwitter.com",
        "vxtwitter.com",
        "fixupx.com",
    )

    fun queryStatus(context: Context): LinkVerificationStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return LinkVerificationStatus(
                enabledCount = TRACKED_DOMAINS.size,
                totalCount = TRACKED_DOMAINS.size,
                requiresSystemApproval = false,
                enabledDomains = TRACKED_DOMAINS.toSet(),
            )
        }

        return runCatching {
            val manager = context.getSystemService(DomainVerificationManager::class.java)
            val userState = manager?.getDomainVerificationUserState(context.packageName)
            val hostMap = userState?.hostToStateMap.orEmpty()
            val enabled = TRACKED_DOMAINS.filterTo(LinkedHashSet()) { domain ->
                val state = hostMap[domain]
                state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED ||
                    state == DomainVerificationUserState.DOMAIN_STATE_SELECTED
            }
            val linkHandlingAllowed = userState?.isLinkHandlingAllowed != false
            val effectiveEnabled = if (linkHandlingAllowed) enabled else emptySet()
            LinkVerificationStatus(
                enabledCount = effectiveEnabled.size,
                totalCount = TRACKED_DOMAINS.size,
                requiresSystemApproval = true,
                enabledDomains = effectiveEnabled,
            )
        }.getOrElse {
            LinkVerificationStatus(
                enabledCount = 0,
                totalCount = TRACKED_DOMAINS.size,
                requiresSystemApproval = true,
                enabledDomains = emptySet(),
            )
        }
    }

    fun createOpenByDefaultSettingsIntent(context: Context): Intent {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
    }

    fun openDefaultLinkSettings(context: Context): Boolean {
        val primary = createOpenByDefaultSettingsIntent(context).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedPrimary = runCatching {
            context.startActivity(primary)
            true
        }.getOrDefault(false)
        if (launchedPrimary) return true

        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(fallback)
            true
        }.getOrDefault(false)
    }
}
