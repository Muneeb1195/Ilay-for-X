package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Opens external web URLs inside Ilay for X using **Android Custom Tabs** powered by the
 * device's default web browser (Chrome, Firefox, Brave, Samsung Internet, etc.).
 *
 * Resolves the user's preferred non-self browser package and sets it explicitly on the launch
 * intent so unsupported `x.com` URLs (Spaces, Lists, Account Settings) open in the browser
 * instead of looping back into Ilay's own deep-link intent filter.
 */
object InAppCustomTabsLauncher {

    private const val NEUTRAL_PROBE_URL = "https://example.com"
    private const val ANDROID_SYSTEM_RESOLVER_PKG = "android"
    private val ALLOWED_WEB_SCHEMES = setOf("http", "https")
    private val ALLOWED_DIRECT_SCHEMES = setOf("mailto", "tel")

    /**
     * Normalizes a user-tapped URL to `http://` or `https://`, returning `null` for blank strings
     * or unsafe schemes (`javascript:`, `file:`, `content:`, etc.).
     */
    fun normalizeWebUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val prefix = trimmed.substringBefore(":", missingDelimiterValue = "")
        val explicitScheme = prefix
            .takeIf { trimmed.contains(":") && !prefix.contains("/") && !prefix.contains(".") }
            ?.lowercase()

        if (explicitScheme != null && explicitScheme !in ALLOWED_WEB_SCHEMES) {
            return null
        }

        val normalized = FormatUtils.normalizeExternalUrl(trimmed)
        val uri = runCatching { normalized.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in ALLOWED_WEB_SCHEMES || uri.host.isNullOrBlank()) return null
        return normalized
    }

    /**
     * Builds a Material-3-themed [CustomTabsIntent] targeting the user's default browser package
     * (when available) so external links render as an in-app overlay without redirect loops.
     */
    fun buildCustomTabsIntent(
        context: Context,
        toolbarColorArgb: Int,
        isDarkTheme: Boolean,
        targetBrowserPackage: String? = resolvePreferredCustomTabsPackage(context),
    ): CustomTabsIntent {
        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColorArgb)
            .setNavigationBarColor(toolbarColorArgb)
            .build()

        val customTabsIntent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colorSchemeParams)
            .setColorScheme(
                if (isDarkTheme) CustomTabsIntent.COLOR_SCHEME_DARK
                else CustomTabsIntent.COLOR_SCHEME_LIGHT
            )
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()

        customTabsIntent.intent.apply {
            val safePackage = sanitizeBrowserPackage(targetBrowserPackage, context.packageName)
            if (safePackage != null) setPackage(safePackage)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return customTabsIntent
    }

    /**
     * Builds a safe browsable `ACTION_VIEW` fallback intent targeting a non-self browser package
     * when Custom Tabs is unavailable.
     */
    fun buildFallbackBrowserIntent(
        context: Context,
        normalizedUrl: String,
        targetBrowserPackage: String? = null,
    ): Intent {
        return Intent(Intent.ACTION_VIEW, normalizedUrl.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            val safePackage = sanitizeBrowserPackage(targetBrowserPackage, context.packageName)
            if (safePackage != null) setPackage(safePackage)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Opens [rawUrl] in the device's default browser via Android Custom Tabs in-app overlay,
     * falling back gracefully to a browsable `ACTION_VIEW` intent if no Custom Tabs provider exists.
     */
    fun openUrl(
        context: Context,
        rawUrl: String,
        toolbarColorArgb: Int,
        isDarkTheme: Boolean,
    ): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return false

        val directScheme = trimmed.substringBefore(":", "").lowercase()
        if (directScheme in ALLOWED_DIRECT_SCHEMES) {
            return runCatching {
                val intent = Intent(Intent.ACTION_VIEW, trimmed.toUri()).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        val normalizedUrl = normalizeWebUrl(trimmed) ?: return false
        val uri = normalizedUrl.toUri()
        val defaultBrowserPkg = resolveDefaultBrowserPackage(context.packageManager, context.packageName)
        val customTabsPkg = resolvePreferredCustomTabsPackage(context, defaultBrowserPkg)

        if (customTabsPkg != null) {
            val launchedCustomTab = runCatching {
                buildCustomTabsIntent(
                    context = context,
                    toolbarColorArgb = toolbarColorArgb,
                    isDarkTheme = isDarkTheme,
                    targetBrowserPackage = customTabsPkg,
                ).launchUrl(context, uri)
                true
            }.getOrDefault(false)
            if (launchedCustomTab) return true
        }

        val launchedTargeted = runCatching {
            context.startActivity(
                buildFallbackBrowserIntent(context, normalizedUrl, targetBrowserPackage = defaultBrowserPkg)
            )
            true
        }.getOrDefault(false)
        if (launchedTargeted) return true

        return runCatching {
            context.startActivity(buildFallbackBrowserIntent(context, normalizedUrl, targetBrowserPackage = null))
            true
        }.getOrDefault(false)
    }

    private fun resolvePreferredCustomTabsPackage(
        context: Context,
        defaultBrowserPkg: String? = resolveDefaultBrowserPackage(context.packageManager, context.packageName),
    ): String? {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val installedPkgs = queryInstalledBrowserPackages(pm, selfPkg)
        val orderedCandidates = buildList {
            if (defaultBrowserPkg != null) add(defaultBrowserPkg)
            installedPkgs.forEach { pkg -> if (pkg !in this) add(pkg) }
        }
        return runCatching {
            val resolved = CustomTabsClient.getPackageName(context, orderedCandidates.ifEmpty { null }, false)
                ?: CustomTabsClient.getPackageName(context, null)
            sanitizeBrowserPackage(resolved, selfPkg)
        }.getOrNull()
    }

    private fun sanitizeBrowserPackage(pkg: String?, selfPkg: String): String? =
        pkg?.takeIf { it.isNotBlank() && it != selfPkg && it != ANDROID_SYSTEM_RESOLVER_PKG }

    private fun createProbeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, NEUTRAL_PROBE_URL.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    private fun resolveDefaultBrowserPackage(pm: PackageManager, selfPkg: String): String? {
        val probeIntent = createProbeIntent()
        val resolved: ResolveInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    probeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull()
        return sanitizeBrowserPackage(resolved?.activityInfo?.packageName, selfPkg)
    }

    private fun queryInstalledBrowserPackages(pm: PackageManager, selfPkg: String): List<String> {
        val probeIntent = createProbeIntent()
        val activities: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    probeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probeIntent, PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())

        return activities.mapNotNull { info ->
            sanitizeBrowserPackage(info.activityInfo?.packageName, selfPkg)
        }.distinct()
    }
}
