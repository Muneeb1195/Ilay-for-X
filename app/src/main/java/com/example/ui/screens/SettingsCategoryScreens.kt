package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.EndpointHashEntity
import com.example.data.local.MutedRuleEntity
import com.example.data.local.UserModerationEntity
import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.domain.model.FeedDensity
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.SwipeAction
import com.example.ui.components.DefaultLinkHandlerHelper
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode

/**
 * Focused category subpage shell hosting the 6 dedicated Settings pages:
 * Appearance & Display, Feed & "For You" Ranking, Safety/Muted/Blocked, Media & Data,
 * X Links, and Accounts & Advanced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    preferences: AppPreferences,
    activeAccount: AccountEntity?,
    accountCount: Int,
    mutedRules: List<MutedRuleEntity>,
    userModerations: List<UserModerationEntity>,
    affinities: List<AuthorAffinityEntity>,
    endpointDiagnostics: List<EndpointHashEntity>,
    isRefreshingRegistry: Boolean,
    onBack: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAmoledDark: (Boolean) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAccentPreset: (AccentPreset) -> Unit,
    onSetFeedDensity: (FeedDensity) -> Unit,
    onSetImageQualityMode: (ImageQualityMode) -> Unit,
    onSetSwipeRight: (SwipeAction) -> Unit,
    onSetSwipeLeft: (SwipeAction) -> Unit,
    onSetRecencyWeight: (Float) -> Unit,
    onSetAffinityWeight: (Float) -> Unit,
    onSetBlurSensitive: (Boolean) -> Unit,
    onSetExcludeRiskyAndNsfw: (Boolean) -> Unit,
    onSetShowRankBadges: (Boolean) -> Unit,
    onAddMutedRule: (String, Boolean) -> Unit,
    onRemoveMutedRule: (Long) -> Unit,
    onUnmuteUser: (UserModerationEntity) -> Unit,
    onUnblockUser: (UserModerationEntity) -> Unit,
    onResetForYou: () -> Unit,
    onSaveProxyUrl: (String) -> Unit,
    onSavePublicHandles: (String) -> Unit,
    onRefreshRegistry: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onOpenInterestInspector: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_subpage_${category.routeArg}"),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_category_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .navigationBarsPadding()
                    .testTag("settings_subpage_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (category) {
                    SettingsCategory.APPEARANCE -> {
                        item(key = "cat_appearance") {
                            AppearanceCategoryContent(
                                preferences = preferences,
                                onSetThemeMode = onSetThemeMode,
                                onSetAmoledDark = onSetAmoledDark,
                                onSetDynamicColor = onSetDynamicColor,
                                onSetAccentPreset = onSetAccentPreset,
                                onSetFeedDensity = onSetFeedDensity,
                            )
                        }
                    }

                    SettingsCategory.FEED_RANKING -> {
                        item(key = "cat_swipes") {
                            SwipeGesturesCard(
                                preferences = preferences,
                                onSetSwipeRight = onSetSwipeRight,
                                onSetSwipeLeft = onSetSwipeLeft,
                            )
                        }
                        item(key = "cat_foryou") {
                            ForYouRankingCard(
                                preferences = preferences,
                                affinities = affinities,
                                onSetShowRankBadges = onSetShowRankBadges,
                                onSetRecencyWeight = onSetRecencyWeight,
                                onSetAffinityWeight = onSetAffinityWeight,
                                onOpenInterestInspector = onOpenInterestInspector,
                                onResetForYou = onResetForYou,
                            )
                        }
                    }

                    SettingsCategory.SAFETY -> {
                        item(key = "cat_safety_firewall") {
                            SafetyFirewallCard(
                                preferences = preferences,
                                onSetExcludeRiskyAndNsfw = onSetExcludeRiskyAndNsfw,
                                onSetBlurSensitive = onSetBlurSensitive,
                                onOpenInterestInspector = onOpenInterestInspector,
                            )
                        }
                        item(key = "cat_safety_muted") {
                            MutedAndBlockedCard(
                                mutedRules = mutedRules,
                                userModerations = userModerations,
                                onAddMutedRule = onAddMutedRule,
                                onRemoveMutedRule = onRemoveMutedRule,
                                onUnmuteUser = onUnmuteUser,
                                onUnblockUser = onUnblockUser,
                            )
                        }
                    }

                    SettingsCategory.MEDIA -> {
                        item(key = "cat_media") {
                            MediaAndDataCard(
                                preferences = preferences,
                                onSetImageQualityMode = onSetImageQualityMode,
                            )
                        }
                    }

                    SettingsCategory.X_LINKS -> {
                        item(key = "cat_default_links") {
                            DefaultXLinksCard()
                        }
                    }

                    SettingsCategory.ADVANCED -> {
                        item(key = "cat_accounts") {
                            AccountsCard(
                                activeAccount = activeAccount,
                                accountCount = accountCount,
                                onOpenAccountSwitcher = onOpenAccountSwitcher,
                            )
                        }
                        item(key = "cat_network") {
                            EndpointAndProxyCard(
                                preferences = preferences,
                                endpointDiagnostics = endpointDiagnostics,
                                isRefreshingRegistry = isRefreshingRegistry,
                                onSaveProxyUrl = onSaveProxyUrl,
                                onSavePublicHandles = onSavePublicHandles,
                                onRefreshRegistry = onRefreshRegistry,
                            )
                        }
                    }
                }

                item(key = "cat_bottom_spacer") {
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCategoryContent(
    preferences: AppPreferences,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAmoledDark: (Boolean) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAccentPreset: (AccentPreset) -> Unit,
    onSetFeedDensity: (FeedDensity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSectionCard(title = "Theme & Color", icon = Icons.Outlined.Palette) {
            Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preferences.themeMode == mode,
                        onClick = { onSetThemeMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}"),
                    )
                }
            }

            ToggleSettingRow(
                title = "AMOLED Pure Black",
                subtitle = "Use true #000000 black surfaces whenever Dark or System Dark theme is active",
                checked = preferences.amoledDark,
                onCheckedChange = onSetAmoledDark,
                testTag = "amoled_dark_toggle",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ToggleSettingRow(
                    title = "Material You Dynamic Color",
                    subtitle = "Adapt primary, secondary & tertiary Monet accents to your wallpaper",
                    checked = preferences.useDynamicColor,
                    onCheckedChange = onSetDynamicColor,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "Primary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            text = "Secondary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            text = "Tertiary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Text("Accent Color", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AccentPreset.entries.forEach { preset ->
                    val selected = preferences.accentPreset == preset && !preferences.useDynamicColor
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onSetAccentPreset(preset) }
                            .testTag("accent_swatch_${preset.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(preset.darkColor)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) preset.darkTertiary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = preset.label,
                                    tint = preset.onDarkPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        SettingsSectionCard(
            title = stringResource(R.string.settings_card_view_title),
            icon = Icons.Outlined.Palette,
        ) {
            Text(
                text = stringResource(R.string.settings_card_view_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedDensity.entries.forEach { density ->
                    FilterChip(
                        selected = preferences.feedDensity == density,
                        onClick = { onSetFeedDensity(density) },
                        label = { Text(density.label) },
                        modifier = Modifier.testTag("feed_density_chip_${density.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwipeGesturesCard(
    preferences: AppPreferences,
    onSetSwipeRight: (SwipeAction) -> Unit,
    onSetSwipeLeft: (SwipeAction) -> Unit,
) {
    SettingsSectionCard(title = "Swipe Gestures", icon = Icons.Outlined.Swipe) {
        Text("Swipe Right Action", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SwipeAction.entries.forEach { action ->
                FilterChip(
                    selected = preferences.swipeRightAction == action,
                    onClick = { onSetSwipeRight(action) },
                    label = { Text(action.label) },
                )
            }
        }
        Text("Swipe Left Action", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SwipeAction.entries.forEach { action ->
                FilterChip(
                    selected = preferences.swipeLeftAction == action,
                    onClick = { onSetSwipeLeft(action) },
                    label = { Text(action.label) },
                )
            }
        }
    }
}

@Composable
private fun ForYouRankingCard(
    preferences: AppPreferences,
    affinities: List<AuthorAffinityEntity>,
    onSetShowRankBadges: (Boolean) -> Unit,
    onSetRecencyWeight: (Float) -> Unit,
    onSetAffinityWeight: (Float) -> Unit,
    onOpenInterestInspector: () -> Unit,
    onResetForYou: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.settings_reset_foryou_dialog_title)) },
            text = { Text(stringResource(R.string.settings_reset_foryou_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetForYou()
                    },
                    modifier = Modifier.testTag("reset_foryou_confirm_button"),
                ) {
                    Text(stringResource(R.string.settings_reset_foryou_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.settings_reset_foryou_cancel))
                }
            },
        )
    }

    SettingsSectionCard(title = "\"For You\" Ranking", icon = Icons.Outlined.AutoAwesome) {
        ToggleSettingRow(
            title = "Show Ranking Reason Pills",
            subtitle = "Display local ranking badges on For You posts",
            checked = preferences.showLocalRankBadges,
            onCheckedChange = onSetShowRankBadges,
        )
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recency Priority", style = MaterialTheme.typography.labelLarge)
                Text("${(preferences.recencyWeight * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Slider(value = preferences.recencyWeight, onValueChange = onSetRecencyWeight)
        }
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Author Affinity Priority", style = MaterialTheme.typography.labelLarge)
                Text("${(preferences.affinityWeight * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Slider(value = preferences.affinityWeight, onValueChange = onSetAffinityWeight)
        }
        if (affinities.isNotEmpty()) {
            Text(
                text = "Tracked local creator signals: ${affinities.size} safe accounts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            text = stringResource(R.string.settings_open_inspector_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onOpenInterestInspector,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_open_inspector_button"),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_open_inspector_cta))
        }
        OutlinedButton(
            onClick = { showResetDialog = true },
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("reset_foryou_button"),
        ) {
            Text("Reset On-Device Affinity Model")
        }
    }
}

@Composable
private fun SafetyFirewallCard(
    preferences: AppPreferences,
    onSetExcludeRiskyAndNsfw: (Boolean) -> Unit,
    onSetBlurSensitive: (Boolean) -> Unit,
    onOpenInterestInspector: () -> Unit,
) {
    SettingsSectionCard(
        title = stringResource(R.string.settings_safety_firewall_title),
        icon = Icons.Outlined.Shield,
    ) {
        ToggleSettingRow(
            title = stringResource(R.string.settings_safety_firewall_title),
            subtitle = stringResource(R.string.settings_safety_firewall_subtitle),
            checked = preferences.excludeRiskyAndNsfw,
            onCheckedChange = onSetExcludeRiskyAndNsfw,
            testTag = "safety_nsfw_firewall_toggle",
        )
        ToggleSettingRow(
            title = "Blur Sensitive Media",
            subtitle = "Hide potentially sensitive images until tapped",
            checked = preferences.blurSensitiveMedia,
            onCheckedChange = onSetBlurSensitive,
            testTag = "safety_blur_sensitive_toggle",
        )
        FilledTonalButton(
            onClick = onOpenInterestInspector,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("safety_open_inspector_button"),
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_safety_inspect_cta))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MutedAndBlockedCard(
    mutedRules: List<MutedRuleEntity>,
    userModerations: List<UserModerationEntity>,
    onAddMutedRule: (String, Boolean) -> Unit,
    onRemoveMutedRule: (Long) -> Unit,
    onUnmuteUser: (UserModerationEntity) -> Unit,
    onUnblockUser: (UserModerationEntity) -> Unit,
) {
    var muteInput by remember { mutableStateOf("") }
    var muteAsHandle by remember { mutableStateOf(false) }
    val mutedAccounts = remember(userModerations) { userModerations.filter { it.isMuted } }
    val blockedAccounts = remember(userModerations) { userModerations.filter { it.isBlocked } }

    SettingsSectionCard(title = "Muted & Blocked Filters", icon = Icons.Outlined.Block) {
        Text(
            text = "Muted and blocked accounts are synced locally and excluded from your For You and Following feeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = muteInput,
                onValueChange = { muteInput = it },
                placeholder = { Text(if (muteAsHandle) "@handle" else "Keyword to hide") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (muteInput.isNotBlank()) {
                        onAddMutedRule(muteInput, muteAsHandle)
                        muteInput = ""
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add mute rule")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !muteAsHandle, onClick = { muteAsHandle = false }, label = { Text("Keyword") })
            FilterChip(selected = muteAsHandle, onClick = { muteAsHandle = true }, label = { Text("@Handle") })
        }

        if (mutedAccounts.isNotEmpty()) {
            Text(
                text = "Muted Accounts (${mutedAccounts.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("settings_muted_accounts_section"),
            ) {
                mutedAccounts.forEach { mod ->
                    InputChip(
                        selected = false,
                        onClick = { onUnmuteUser(mod) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        label = { Text("@${mod.handleLower}") },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Unmute @${mod.handleLower}", modifier = Modifier.size(15.dp))
                        },
                    )
                }
            }
        }

        if (blockedAccounts.isNotEmpty()) {
            Text(
                text = "Blocked Accounts (${blockedAccounts.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("settings_blocked_accounts_section"),
            ) {
                blockedAccounts.forEach { mod ->
                    InputChip(
                        selected = false,
                        onClick = { onUnblockUser(mod) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp),
                            )
                        },
                        label = { Text("@${mod.handleLower}") },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Unblock @${mod.handleLower}", modifier = Modifier.size(15.dp))
                        },
                    )
                }
            }
        }

        if (mutedRules.isNotEmpty()) {
            Text(
                text = "Muted Keywords (${mutedRules.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mutedRules.forEach { rule ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveMutedRule(rule.id) },
                        label = { Text(if (rule.isHandle) "@${rule.value}" else rule.value) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(15.dp)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaAndDataCard(
    preferences: AppPreferences,
    onSetImageQualityMode: (ImageQualityMode) -> Unit,
) {
    val context = LocalContext.current
    val activeNetworkTier = remember(context, preferences.imageQualityMode) {
        AdaptiveImageQualitySelector.detectNetworkTier(context)
    }
    val activePolicySummary = remember(preferences.imageQualityMode, activeNetworkTier) {
        AdaptiveImageQualitySelector.describeActivePolicy(
            mode = preferences.imageQualityMode,
            networkTier = activeNetworkTier,
        )
    }
    SettingsSectionCard(
        title = stringResource(R.string.settings_media_section_title),
        icon = Icons.Outlined.PhotoSizeSelectLarge,
    ) {
        Text(
            text = stringResource(R.string.settings_image_quality_title),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageQualityMode.entries.forEach { mode ->
                FilterChip(
                    selected = preferences.imageQualityMode == mode,
                    onClick = { onSetImageQualityMode(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.testTag("image_quality_chip_${mode.name.lowercase()}"),
                )
            }
        }
        Text(
            text = preferences.imageQualityMode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("image_quality_policy_summary"),
        ) {
            Text(
                text = activePolicySummary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultXLinksCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var linkStatus by remember(context) {
        mutableStateOf(DefaultLinkHandlerHelper.queryStatus(context))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                linkStatus = DefaultLinkHandlerHelper.queryStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isActive = !linkStatus.requiresSystemApproval || linkStatus.enabledCount > 0
    val statusLabel = when {
        !linkStatus.requiresSystemApproval ->
            stringResource(R.string.settings_default_links_status_ready)
        linkStatus.enabledCount > 0 ->
            pluralStringResource(
                R.plurals.settings_default_links_status_enabled,
                linkStatus.enabledCount,
                linkStatus.enabledCount,
                linkStatus.totalCount,
            )
        else -> stringResource(R.string.settings_default_links_status_pending)
    }

    SettingsSectionCard(
        title = stringResource(R.string.settings_default_links_title),
        icon = Icons.Outlined.Link,
    ) {
        Surface(
            shape = CircleShape,
            color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.testTag("settings_default_links_status"),
        ) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_default_links_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DefaultLinkHandlerHelper.DISPLAY_DOMAIN_CHIPS.forEach { domain ->
                val domainEnabled = !linkStatus.requiresSystemApproval || domain in linkStatus.enabledDomains
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (domainEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (domainEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        if (linkStatus.requiresSystemApproval) {
            Text(
                text = stringResource(R.string.settings_default_links_android12_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = { DefaultLinkHandlerHelper.openDefaultLinkSettings(context) },
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_open_default_links_button"),
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_default_links_cta))
        }
    }
}

@Composable
private fun AccountsCard(
    activeAccount: AccountEntity?,
    accountCount: Int,
    onOpenAccountSwitcher: () -> Unit,
) {
    SettingsSectionCard(title = "Accounts", icon = Icons.Outlined.AccountCircle) {
        Text(
            text = if (activeAccount != null) {
                "Signed in as @${activeAccount.handle} ($accountCount saved ${if (accountCount == 1) "account" else "accounts"})"
            } else {
                "Currently browsing in Public Preview Mode. Sign in to post, reply, and view your personalized Home timeline."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onOpenAccountSwitcher,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_manage_accounts_button"),
        ) {
            Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Manage Accounts")
        }
    }
}

@Composable
private fun EndpointAndProxyCard(
    preferences: AppPreferences,
    endpointDiagnostics: List<EndpointHashEntity>,
    isRefreshingRegistry: Boolean,
    onSaveProxyUrl: (String) -> Unit,
    onSavePublicHandles: (String) -> Unit,
    onRefreshRegistry: () -> Unit,
) {
    var proxyUrlDraft by remember(preferences.customProxyBaseUrl) { mutableStateOf(preferences.customProxyBaseUrl) }
    var publicHandlesDraft by remember(preferences.publicPreviewHandlesCsv) { mutableStateOf(preferences.publicPreviewHandlesCsv) }

    val activeNitterBase = com.example.data.remote.NitterHtmlParser.normalizeBaseUrl(preferences.customProxyBaseUrl)

    SettingsSectionCard(title = "Endpoint Registry & Nitter Fallback", icon = Icons.Outlined.Terminal) {
        FilledTonalButton(
            onClick = onRefreshRegistry,
            enabled = !isRefreshingRegistry,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("refresh_endpoints_button"),
        ) {
            if (isRefreshingRegistry) {
                IlayLoadingIndicator(size = 18.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Sync Latest X Hashes")
        }

        OutlinedTextField(
            value = publicHandlesDraft,
            onValueChange = { publicHandlesDraft = it },
            label = { Text("Public Preview Handles (comma-separated)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { onSavePublicHandles(publicHandlesDraft) },
            shape = CircleShape,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Save Preview Handles")
        }

        OutlinedTextField(
            value = proxyUrlDraft,
            onValueChange = { proxyUrlDraft = it },
            label = { Text("Fallback Nitter Instance URL") },
            placeholder = { Text(com.example.data.remote.NitterHtmlParser.DEFAULT_NITTER_BASE_URL) },
            supportingText = {
                Text("Active: $activeNitterBase · FxTwitter JSON safety net enabled")
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nitter_instance_url_input"),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { onSaveProxyUrl(proxyUrlDraft) },
                shape = CircleShape,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_nitter_instance_button"),
            ) {
                Text("Save Instance")
            }
            if (preferences.customProxyBaseUrl.isNotBlank()) {
                TextButton(
                    onClick = {
                        proxyUrlDraft = ""
                        onSaveProxyUrl("")
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("reset_nitter_instance_button"),
                ) {
                    Text("Reset to Default")
                }
            }
        }

        if (endpointDiagnostics.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Active GraphQL Operations (${endpointDiagnostics.size})",
                style = MaterialTheme.typography.labelLarge,
            )
            endpointDiagnostics.take(8).forEach { ep ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = ep.operationName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${ep.queryId.take(10)}… · ${ep.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        )
    }
}
