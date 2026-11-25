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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountEntity
import com.example.domain.model.NotificationItem
import com.example.domain.model.NotificationType
import com.example.domain.model.Tweet
import com.example.ui.components.FormatUtils
import com.example.ui.components.IlayCollapsingTopBar
import com.example.ui.components.IlayPullToRefreshIndicator
import com.example.ui.components.LocalIlayChromeState
import com.example.ui.components.LowPolyEmptyStateArt
import com.example.ui.components.LowPolyScene
import com.example.ui.components.UserAvatar
import com.example.ui.components.ilayVerticalScrollIndicator
import com.example.ui.theme.LikeRose
import com.example.ui.theme.RepostEmerald
import com.example.ui.theme.SoftIndigo
import com.example.ui.viewmodel.ActivityUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    uiState: ActivityUiState,
    activeAccount: AccountEntity?,
    onSetMentionsOnly: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (Tweet) -> Unit,
) {
    val chromeState = LocalIlayChromeState.current

    Column(modifier = Modifier.fillMaxSize()) {
        IlayCollapsingTopBar(
            title = "Activity",
            subtitle = "On-demand direct polling · Zero push trackers",
            collapseProgress = chromeState.collapseProgress,
            bottomExpandedContent = if (activeAccount != null) {
                {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        SegmentedButton(
                            selected = !uiState.mentionsOnly,
                            onClick = { onSetMentionsOnly(false) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            modifier = Modifier.testTag("activity_tab_all")
                        ) {
                            Text("All Activity")
                        }
                        SegmentedButton(
                            selected = uiState.mentionsOnly,
                            onClick = { onSetMentionsOnly(true) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            modifier = Modifier.testTag("activity_tab_mentions")
                        ) {
                            Text("Mentions")
                        }
                    }
                }
            } else {
                null
            },
        )

        if (activeAccount == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LowPolyEmptyStateArt(
                    scene = LowPolyScene.ACTIVITY,
                    contentDescriptionText = "Sign in for X activity low-poly illustration",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
                Spacer(Modifier.height(20.dp))
                Text("Sign in for Notifications", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Connect your X session to see mentions, replies, likes, reposts, and new followers directly from X.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onSignIn,
                    shape = CircleShape,
                    modifier = Modifier.testTag("activity_signin_button"),
                ) {
                    Text("Sign in to X")
                }
            }
            return
        }

        val pullRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = onRefresh,
            state = pullRefreshState,
            indicator = {
                IlayPullToRefreshIndicator(
                    state = pullRefreshState,
                    isRefreshing = uiState.isLoading,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (uiState.items.isEmpty() && !uiState.isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "All caught up — no recent notifications.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 640.dp)
                            .ilayVerticalScrollIndicator(state = listState),
                        contentPadding = PaddingValues(12.dp, 6.dp, 12.dp, 112.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.items, key = { it.id }) { item ->
                            NotificationRowCard(
                                item = item,
                                onOpenProfile = onOpenProfile,
                                onOpenThread = onOpenThread,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRowCard(
    item: NotificationItem,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (Tweet) -> Unit,
) {
    val (icon, tint) = when (item.type) {
        NotificationType.LIKE -> Icons.Filled.Favorite to LikeRose
        NotificationType.REPOST -> Icons.Filled.Repeat to RepostEmerald
        NotificationType.FOLLOW -> Icons.Filled.PersonAdd to SoftIndigo
        NotificationType.QUOTE -> Icons.Filled.FormatQuote to MaterialTheme.colorScheme.tertiary
        NotificationType.REPLY -> Icons.Outlined.ChatBubbleOutline to MaterialTheme.colorScheme.primary
        NotificationType.MENTION -> Icons.Filled.AlternateEmail to MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                item.targetTweet?.let(onOpenThread) ?: onOpenProfile(item.actor.handle)
            }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.16f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = item.type.label, tint = tint, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onOpenProfile(item.actor.handle) }
                    ) {
                        UserAvatar(item.actor.avatarUrl, item.actor.displayName, size = 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.actor.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val context = LocalContext.current
                    val formattedTime = remember(item.timestampEpochMs, context) {
                        FormatUtils.formatDeviceTime(context, item.timestampEpochMs)
                    }
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                item.targetTweet?.let { target ->
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = target.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}
