package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.components.DefaultLinkHandlerHelper

private data class SettingsHubRowModel(
    val category: SettingsCategory,
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconTint: Color,
    val summary: String,
)

/**
 * Clean, categorized Material 3 Expressive **Settings Hub** landing screen.
 * Displays 6 focused category rows with live current-setting summaries organized into two
 * calm surface groups, plus a compact active-account header pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    activeAccount: AccountEntity?,
    accountCount: Int,
    mutedRules: List<MutedRuleEntity>,
    userModerations: List<UserModerationEntity>,
    affinities: List<AuthorAffinityEntity>,
    endpointDiagnostics: List<EndpointHashEntity>,
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAccountSwitcher: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var linkStatus by remember(context) {
        mutableStateOf(DefaultLinkHandlerHelper.queryStatus(context))
    }
    var networkTier by remember(context) {
        mutableStateOf(AdaptiveImageQualitySelector.detectNetworkTier(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                linkStatus = DefaultLinkHandlerHelper.queryStatus(context)
                networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mutedAccountCount = remember(userModerations) { userModerations.count { it.isMuted } }
    val blockedAccountCount = remember(userModerations) { userModerations.count { it.isBlocked } }

    val colorScheme = MaterialTheme.colorScheme
    val personalizationRows = listOf(
        SettingsHubRowModel(
            category = SettingsCategory.APPEARANCE,
            icon = Icons.Outlined.Palette,
            iconContainerColor = colorScheme.primaryContainer,
            iconTint = colorScheme.onPrimaryContainer,
            summary = SettingsHubSummaryFormatter.appearanceSummary(preferences),
        ),
        SettingsHubRowModel(
            category = SettingsCategory.FEED_RANKING,
            icon = Icons.Outlined.AutoAwesome,
            iconContainerColor = colorScheme.tertiaryContainer,
            iconTint = colorScheme.onTertiaryContainer,
            summary = SettingsHubSummaryFormatter.feedRankingSummary(
                preferences = preferences,
                trackedSafeCreatorCount = affinities.size,
            ),
        ),
        SettingsHubRowModel(
            category = SettingsCategory.SAFETY,
            icon = Icons.Outlined.Shield,
            iconContainerColor = if (preferences.excludeRiskyAndNsfw) {
                colorScheme.secondaryContainer
            } else {
                colorScheme.errorContainer
            },
            iconTint = if (preferences.excludeRiskyAndNsfw) {
                colorScheme.onSecondaryContainer
            } else {
                colorScheme.onErrorContainer
            },
            summary = SettingsHubSummaryFormatter.safetySummary(
                preferences = preferences,
                mutedKeywordCount = mutedRules.size,
                mutedAccountCount = mutedAccountCount,
                blockedAccountCount = blockedAccountCount,
            ),
        ),
        SettingsHubRowModel(
            category = SettingsCategory.MEDIA,
            icon = Icons.Outlined.PhotoSizeSelectLarge,
            iconContainerColor = colorScheme.secondaryContainer,
            iconTint = colorScheme.onSecondaryContainer,
            summary = SettingsHubSummaryFormatter.mediaSummary(
                preferences = preferences,
                networkTier = networkTier,
            ),
        ),
    )

    val systemRows = listOf(
        SettingsHubRowModel(
            category = SettingsCategory.X_LINKS,
            icon = Icons.Outlined.Link,
            iconContainerColor = colorScheme.primaryContainer,
            iconTint = colorScheme.onPrimaryContainer,
            summary = SettingsHubSummaryFormatter.linksSummary(linkStatus = linkStatus),
        ),
        SettingsHubRowModel(
            category = SettingsCategory.ADVANCED,
            icon = Icons.Outlined.Tune,
            iconContainerColor = colorScheme.surfaceContainerHighest,
            iconTint = colorScheme.onSurfaceVariant,
            summary = SettingsHubSummaryFormatter.advancedSummary(
                activeAccount = activeAccount,
                accountCount = accountCount,
                endpointCount = endpointDiagnostics.size,
                hasCustomProxy = preferences.customProxyBaseUrl.isNotBlank(),
                fallbackHost = com.example.data.remote.NitterHtmlParser.displayHost(preferences.customProxyBaseUrl),
            ),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_hub_screen"),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.settings_title),
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
                    .testTag("settings_hub_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "hub_account_banner") {
                    ActiveAccountHeaderCard(
                        activeAccount = activeAccount,
                        accountCount = accountCount,
                        onOpenAccountSwitcher = onOpenAccountSwitcher,
                    )
                }

                item(key = "hub_group_personalization") {
                    SettingsHubGroup(
                        header = stringResource(R.string.settings_hub_group_personalization),
                        rows = personalizationRows,
                        onOpenCategory = onOpenCategory,
                    )
                }

                item(key = "hub_group_system") {
                    SettingsHubGroup(
                        header = stringResource(R.string.settings_hub_group_system),
                        rows = systemRows,
                        onOpenCategory = onOpenCategory,
                    )
                }

                item(key = "hub_bottom_spacer") {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ActiveAccountHeaderCard(
    activeAccount: AccountEntity?,
    accountCount: Int,
    onOpenAccountSwitcher: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_hub_account_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAccountSwitcher)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeAccount?.displayName?.ifBlank { "@${activeAccount.handle}" }
                        ?: stringResource(R.string.settings_hub_guest_account),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (activeAccount != null) {
                        val extra = if (accountCount > 1) " · $accountCount accounts" else ""
                        "@${activeAccount.handle}$extra"
                    } else {
                        "Tap to sign in or add an X account"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onOpenAccountSwitcher,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("settings_hub_switch_account_button"),
            ) {
                Text(
                    text = stringResource(R.string.settings_hub_manage_accounts_cta),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SettingsHubGroup(
    header: String,
    rows: List<SettingsHubRowModel>,
    onOpenCategory: (SettingsCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    SettingsHubRow(
                        row = row,
                        onClick = { onOpenCategory(row.category) },
                    )
                    if (index < rows.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubRow(
    row: SettingsHubRowModel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("settings_category_${row.category.routeArg}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = row.iconContainerColor,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = row.icon,
                    contentDescription = null,
                    tint = row.iconTint,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(row.category.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("settings_summary_${row.category.routeArg}"),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
