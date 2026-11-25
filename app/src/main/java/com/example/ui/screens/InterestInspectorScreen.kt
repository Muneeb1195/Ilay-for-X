package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.TopicInterestEntity
import com.example.data.local.TopicKind
import com.example.data.local.TopicManualState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterestInspectorScreen(
    preferences: AppPreferences,
    topics: List<TopicInterestEntity>,
    creators: List<AuthorAffinityEntity>,
    seenCount: Int,
    dismissedCount: Int,
    riskySuppressedCount: Int,
    onBack: () -> Unit,
    onAddTopic: (String) -> Unit,
    onToggleBoostTopic: (TopicInterestEntity) -> Unit,
    onToggleDemoteTopic: (TopicInterestEntity) -> Unit,
    onRemoveTopic: (String) -> Unit,
    onTogglePinCreator: (AuthorAffinityEntity) -> Unit,
    onToggleShowLessCreator: (AuthorAffinityEntity) -> Unit,
    onResetCreator: (String) -> Unit,
    onSetDemoteSeenPosts: (Boolean) -> Unit,
    onSetBlendMultiSource: (Boolean) -> Unit,
    onSetExcludeRiskyAndNsfw: (Boolean) -> Unit,
    onClearSeenHistory: () -> Unit,
    onResetAllSignals: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var topicDraft by rememberSaveable { mutableStateOf("") }

    val tabs = listOf(
        stringResource(R.string.inspector_tab_topics),
        stringResource(R.string.inspector_tab_creators),
        stringResource(R.string.inspector_tab_signals),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("inspector_back_button"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.inspector_title),
                    style = MaterialTheme.typography.titleLarge,
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
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Hero Privacy & Risky/NSFW Safety Shield summary card
                item(key = "inspector_hero") {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("inspector_summary_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (preferences.excludeRiskyAndNsfw) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        imageVector = if (preferences.excludeRiskyAndNsfw) Icons.Filled.Shield else Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = if (preferences.excludeRiskyAndNsfw) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        },
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = stringResource(
                                            if (preferences.excludeRiskyAndNsfw) R.string.inspector_shield_active
                                            else R.string.inspector_shield_paused
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (preferences.excludeRiskyAndNsfw) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        },
                                    )
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                InspectorStatPill("${topics.size} topics")
                                InspectorStatPill("${creators.size} creators")
                                InspectorStatPill("$seenCount seen")
                                if (riskySuppressedCount > 0) {
                                    InspectorStatPill("$riskySuppressedCount risky/NSFW blocked")
                                }
                            }
                        }
                    }
                }

                // Segmented section switcher
                item(key = "inspector_tabs") {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        tabs.forEachIndexed { idx, label ->
                            SegmentedButton(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("inspector_tab_$idx"),
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                when (selectedTab) {
                    0 -> {
                        // Add custom safe topic bar
                        item(key = "add_topic_bar") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = topicDraft,
                                    onValueChange = { topicDraft = it },
                                    placeholder = { Text(stringResource(R.string.inspector_add_topic_hint)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (topicDraft.isNotBlank()) {
                                                onAddTopic(topicDraft)
                                                topicDraft = ""
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("inspector_topic_input"),
                                )
                                FilledTonalIconButton(
                                    onClick = {
                                        if (topicDraft.isNotBlank()) {
                                            onAddTopic(topicDraft)
                                            topicDraft = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .testTag("inspector_add_topic_button"),
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.cd_inspector_add_topic),
                                    )
                                }
                            }
                        }

                        if (topics.isEmpty()) {
                            item(key = "topics_empty") {
                                InspectorEmptyCard(
                                    title = "No learned topics yet",
                                    body = "Read posts, open threads, bookmark tweets, or add a safe topic above to train your on-device For You graph. Risky and NSFW terms are automatically excluded.",
                                )
                            }
                        } else {
                            items(items = topics, key = { "topic_${it.tokenLower}" }) { topic ->
                                TopicInterestCard(
                                    topic = topic,
                                    onToggleBoost = { onToggleBoostTopic(topic) },
                                    onToggleDemote = { onToggleDemoteTopic(topic) },
                                    onRemove = { onRemoveTopic(topic.tokenLower) },
                                )
                            }
                        }
                    }

                    1 -> {
                        if (creators.isEmpty()) {
                            item(key = "creators_empty") {
                                InspectorEmptyCard(
                                    title = "No creator signals yet",
                                    body = "Liking, bookmarking, opening threads, or lingering on posts from safe accounts builds local creator affinity here. Risky and NSFW accounts are never added.",
                                )
                            }
                        } else {
                            items(items = creators, key = { "creator_${it.handleLower}" }) { creator ->
                                CreatorAffinityCard(
                                    creator = creator,
                                    onTogglePin = { onTogglePinCreator(creator) },
                                    onToggleShowLess = { onToggleShowLessCreator(creator) },
                                    onReset = { onResetCreator(creator.handleLower) },
                                )
                            }
                        }
                    }

                    else -> {
                        item(key = "signals_controls") {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    InspectorToggleRow(
                                        title = "Risky & NSFW Timeline & Learning Firewall",
                                        subtitle = "Exclude risky/NSFW posts & creators from For You and Following, and block them from learning even if you view or open their profiles.",
                                        checked = preferences.excludeRiskyAndNsfw,
                                        onCheckedChange = onSetExcludeRiskyAndNsfw,
                                        testTag = "inspector_toggle_nsfw_firewall",
                                    )
                                    InspectorToggleRow(
                                        title = "Demote Seen Posts Below Unseen",
                                        subtitle = "Track posts you view in For You locally and place fresh unseen posts ahead of them on refresh.",
                                        checked = preferences.demoteSeenPosts,
                                        onCheckedChange = onSetDemoteSeenPosts,
                                        testTag = "inspector_toggle_demote_seen",
                                    )
                                    InspectorToggleRow(
                                        title = "Blend Following & Top Topics",
                                        subtitle = "Mix fresh Following posts and a rotating top safe topic into For You within a fast rate-limit-safe budget.",
                                        checked = preferences.blendMultiSourceForYou,
                                        onCheckedChange = onSetBlendMultiSource,
                                        testTag = "inspector_toggle_blend_sources",
                                    )

                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Tracked on device: $seenCount seen posts · $dismissedCount hidden posts · $riskySuppressedCount risky/NSFW creators suppressed.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    FilledTonalButton(
                                        onClick = onClearSeenHistory,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("inspector_clear_seen_button"),
                                    ) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Clear Seen Post History")
                                    }

                                    OutlinedButton(
                                        onClick = onResetAllSignals,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("inspector_reset_all_button"),
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Reset All Learned Topics & Affinities")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorStatPill(label: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun TopicInterestCard(
    topic: TopicInterestEntity,
    onToggleBoost: () -> Unit,
    onToggleDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    val isBoosted = topic.manualState == TopicManualState.BOOSTED
    val isDemoted = topic.manualState == TopicManualState.DEMOTED
    val net = (topic.positiveScore - topic.negativeScore)
    val meterFraction = ((net.coerceIn(-10.0, 20.0) + 10.0) / 30.0).toFloat().coerceIn(0.05f, 1f)
    val kindLabel = when (topic.kind) {
        TopicKind.HASHTAG -> "Hashtag"
        TopicKind.DOMAIN -> "Source"
        else -> "Topic"
    }
    val statusLabel = when {
        isBoosted -> "Boosted"
        isDemoted -> "Demoted"
        net < -0.5 -> "Cooling off"
        else -> "Learned"
    }
    val statusBg = when {
        isBoosted -> MaterialTheme.colorScheme.primaryContainer
        isDemoted || net < -0.5 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val statusFg = when {
        isBoosted -> MaterialTheme.colorScheme.onPrimaryContainer
        isDemoted || net < -0.5 -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("topic_card_${topic.tokenLower}"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topic.displayLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = kindLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(shape = CircleShape, color = statusBg) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onToggleBoost,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topic_boost_${topic.tokenLower}"),
                ) {
                    Icon(
                        imageVector = if (isBoosted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUpOffAlt,
                        contentDescription = stringResource(R.string.cd_inspector_boost_topic),
                        tint = if (isBoosted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onToggleDemote,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topic_demote_${topic.tokenLower}"),
                ) {
                    Icon(
                        imageVector = if (isDemoted) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDownOffAlt,
                        contentDescription = stringResource(R.string.cd_inspector_demote_topic),
                        tint = if (isDemoted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topic_remove_${topic.tokenLower}"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_inspector_remove_topic),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { meterFraction },
                color = if (isDemoted || net < -0.5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatorAffinityCard(
    creator: AuthorAffinityEntity,
    onTogglePin: () -> Unit,
    onToggleShowLess: () -> Unit,
    onReset: () -> Unit,
) {
    val label = creator.displayName.ifBlank { "@${creator.handleLower}" }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("creator_card_${creator.handleLower}"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "@${creator.handleLower}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("creator_pin_${creator.handleLower}"),
                ) {
                    Icon(
                        imageVector = if (creator.isPinnedBoost) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.cd_inspector_pin_creator),
                        tint = if (creator.isPinnedBoost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onToggleShowLess,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("creator_show_less_${creator.handleLower}"),
                ) {
                    Icon(
                        imageVector = if (creator.isSoftDemoted) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDownOffAlt,
                        contentDescription = stringResource(R.string.cd_inspector_show_less_creator),
                        tint = if (creator.isSoftDemoted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onReset,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("creator_reset_${creator.handleLower}"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_inspector_reset_creator),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (creator.isPinnedBoost) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "Pinned boost",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                if (creator.isSoftDemoted) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            text = "Showing less",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                val totalLikes = creator.likesGiven + creator.bookmarksGiven
                if (totalLikes > 0) InspectorStatPill("$totalLikes likes/saves")
                val opens = creator.profileOpens + creator.threadOpens
                if (opens > 0) InspectorStatPill("$opens opens")
                if (creator.totalDwellSeconds > 0) InspectorStatPill("${creator.totalDwellSeconds}s read")
                if (creator.skipsCount > 0) InspectorStatPill("${creator.skipsCount} skips")
            }
        }
    }
}

@Composable
private fun InspectorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun InspectorEmptyCard(
    title: String,
    body: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
