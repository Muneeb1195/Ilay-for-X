package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.XPollParser
import com.example.domain.model.Poll
import com.example.domain.model.PollChoice
import com.example.domain.model.Tweet
import com.example.ui.theme.IlayMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Raw X poll JSON overload of [PollCard].
 * Parses X GraphQL card JSON, CAPI poll responses, or public/API v2 poll payloads via [XPollParser]
 * and renders the interactive Material 3 poll card directly.
 */
@Composable
fun PollCard(
    rawPollJson: String,
    tweetId: String,
    modifier: Modifier = Modifier,
    onVote: (suspend (PollChoice) -> Result<Poll>)? = null,
    onVoteCompleted: ((Poll) -> Unit)? = null,
    onVoteError: ((String) -> Unit)? = null,
) {
    val parsedPoll = remember(rawPollJson) { XPollParser.parse(rawPollJson) } ?: return
    PollCard(
        poll = parsedPoll,
        tweetId = tweetId,
        modifier = modifier,
        onVote = onVote,
        onVoteCompleted = onVoteCompleted,
        onVoteError = onVoteError,
    )
}

/**
 * Tweet-integrated convenience overload of [PollCard] wired to [LocalTweetCardHost].
 * Optimistically applies the user's vote locally, submits through X's CAPI vote endpoint,
 * reconciles exact server counts in-place, and propagates the updated poll without re-fetching
 * the tweet.
 */
@Composable
fun PollCard(
    tweet: Tweet,
    poll: Poll,
    modifier: Modifier = Modifier,
    onVoteSubmitted: ((Tweet) -> Unit)? = null,
) {
    val host = LocalTweetCardHost.current
    val signInFallbackMsg = stringResource(R.string.poll_sign_in_prompt)

    PollCard(
        poll = poll,
        tweetId = tweet.id,
        modifier = modifier,
        onVote = { choice ->
            host.votePoll(tweet.copy(poll = poll), choice.index).map { updatedTweet ->
                updatedTweet.poll ?: poll.withLocalVote(choice.index)
            }
        },
        onVoteCompleted = { updatedPoll ->
            onVoteSubmitted?.invoke(tweet.copy(poll = updatedPoll))
        },
        onVoteError = { message ->
            host.showMessage(message.ifBlank { signInFallbackMsg })
        },
    )
}

/**
 * Interactive Material 3 Expressive Poll Card for X posts.
 *
 * State machine:
 * 1. **Unvoted (Open)**: Interactive >=50dp option rows with radio indicators and live countdown.
 * 2. **Optimistic Selection & Submitting**: Tapping a choice immediately applies a local vote
 *    transition (`Poll.withLocalVote`) so progress bars, percentages, and selected state update
 *    instantaneously without waiting for a full tweet re-fetch. Duplicate taps are locked while
 *    the vote request completes.
 * 3. **Voted (Active)**: Smoothly reconciles exact vote totals returned by X CAPI, highlights
 *    the user's pick with a filled check badge, and renders dual-layer visual progress bars.
 * 4. **Final / Closed**: Automatically transitions when `isFinal` or `endsAtEpochMs` elapses,
 *    disables voting, emphasizes winning choice(s) (including ties), and preserves user vote state.
 */
@Composable
fun PollCard(
    poll: Poll,
    tweetId: String,
    modifier: Modifier = Modifier,
    onVote: (suspend (PollChoice) -> Result<Poll>)? = null,
    onVoteCompleted: ((Poll) -> Unit)? = null,
    onVoteError: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var localPoll by remember(tweetId) { mutableStateOf(poll) }
    var submittingChoiceIndex by remember(tweetId) { mutableStateOf<Int?>(null) }
    var nowEpochMs by remember(tweetId) { mutableLongStateOf(System.currentTimeMillis()) }

    // Sync upstream updates (e.g., repository pollUpdates or Room cache refresh) unless mid-submit
    LaunchedEffect(poll) {
        if (submittingChoiceIndex == null) {
            localPoll = poll
        }
    }

    // Automatically transition an expiring poll to "Final" locally without re-fetching the tweet
    LaunchedEffect(localPoll.endsAtEpochMs, localPoll.isFinal) {
        if (localPoll.isFinal || localPoll.endsAtEpochMs <= 0L) return@LaunchedEffect
        val remainingMs = localPoll.endsAtEpochMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            nowEpochMs = System.currentTimeMillis()
        } else if (remainingMs <= 60 * 60_000L) {
            delay(remainingMs.coerceAtMost(60_000L))
            nowEpochMs = System.currentTimeMillis()
        }
    }

    val activePoll = localPoll
    val isSubmitting = submittingChoiceIndex != null
    val isClosed = activePoll.isClosedAt(nowEpochMs)
    val showResults = activePoll.showResultsAt(nowEpochMs)
    val maxVotes = remember(activePoll.choices) { activePoll.choices.maxOfOrNull { it.votes } ?: 0L }

    val statusBadge = when {
        isSubmitting -> stringResource(R.string.poll_status_recording)
        isClosed -> stringResource(R.string.poll_status_final)
        activePoll.hasVoted -> stringResource(R.string.poll_status_voted)
        else -> formatPollRemaining(activePoll.endsAtEpochMs, nowEpochMs)
            ?: stringResource(R.string.poll_status_live)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag("poll_card_$tweetId"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Header row: Poll title + live state chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.poll_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = when {
                        isSubmitting -> MaterialTheme.colorScheme.tertiaryContainer
                        showResults -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                ) {
                    Text(
                        text = statusBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isSubmitting -> MaterialTheme.colorScheme.onTertiaryContainer
                            showResults -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }

            // Choice rows with dual-layer animated progress bars
            activePoll.choices.forEach { choice ->
                val rawFraction = activePoll.fractionFor(choice)
                val percentInt = activePoll.percentageFor(choice)
                val isSelected = activePoll.votedChoiceIndex == choice.index
                val isWinning = showResults && maxVotes > 0L && choice.votes == maxVotes
                val isChoiceSubmitting = submittingChoiceIndex == choice.index

                PollChoiceRow(
                    tweetId = tweetId,
                    choice = choice,
                    fraction = rawFraction,
                    percentInt = percentInt,
                    showResults = showResults,
                    isSelected = isSelected,
                    isWinning = isWinning,
                    isSubmittingThis = isChoiceSubmitting,
                    enabled = !showResults && !isSubmitting,
                    onClick = {
                        if (showResults || isSubmitting) return@PollChoiceRow
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                        val previousPoll = activePoll
                        val optimisticPoll = previousPoll.withLocalVote(choice.index)

                        // Immediately reflect user selection & animated results locally (zero re-fetch)
                        localPoll = optimisticPoll

                        if (onVote == null) {
                            onVoteCompleted?.invoke(optimisticPoll)
                            return@PollChoiceRow
                        }

                        submittingChoiceIndex = choice.index
                        scope.launch {
                            val result = onVote(choice)
                            submittingChoiceIndex = null
                            result.onSuccess { serverPoll ->
                                localPoll = serverPoll
                                onVoteCompleted?.invoke(serverPoll)
                            }.onFailure { err ->
                                // Roll back cleanly if sign-in is required or request fails
                                localPoll = previousPoll
                                onVoteError?.invoke(err.message.orEmpty())
                            }
                        }
                    },
                )
            }

            // Footer: total votes + remaining time / state
            val voteWord = if (activePoll.totalVotes == 1L) "vote" else "votes"
            val countStr = FormatUtils.compactCount(activePoll.totalVotes).ifBlank { "0" }
            val footerStatus = when {
                isSubmitting -> stringResource(R.string.poll_footer_recording)
                isClosed -> stringResource(R.string.poll_footer_final)
                activePoll.hasVoted -> {
                    val rem = formatPollRemaining(activePoll.endsAtEpochMs, nowEpochMs)
                    val recorded = stringResource(R.string.poll_footer_recorded)
                    if (rem != null) "$recorded · $rem" else recorded
                }
                else -> {
                    val rem = formatPollRemaining(activePoll.endsAtEpochMs, nowEpochMs)
                    val tapHint = stringResource(R.string.poll_footer_tap_to_vote)
                    if (rem != null) "$rem · $tapHint" else tapHint
                }
            }
            Text(
                text = "$countStr $voteWord · $footerStatus",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("poll_footer_$tweetId"),
            )
        }
    }
}

@Composable
private fun PollChoiceRow(
    tweetId: String,
    choice: PollChoice,
    fraction: Float,
    percentInt: Int,
    showResults: Boolean,
    isSelected: Boolean,
    isWinning: Boolean,
    isSubmittingThis: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val animatedFill by animateFloatAsState(
        targetValue = if (showResults) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = IlayMotion.spatialDefault(),
        label = "pollBarFill",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected || isSubmittingThis -> MaterialTheme.colorScheme.primary
            isWinning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = IlayMotion.effectsDefault(),
        label = "pollBorder",
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceHighColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val backdropStartColor = when {
        isSelected -> primaryColor.copy(alpha = 0.24f)
        isWinning -> primaryColor.copy(alpha = 0.16f)
        else -> surfaceHighColor.copy(alpha = 0.78f)
    }
    val backdropEndColor = when {
        isSelected -> primaryColor.copy(alpha = 0.12f)
        isWinning -> primaryColor.copy(alpha = 0.08f)
        else -> surfaceHighColor.copy(alpha = 0.42f)
    }
    val trackIndicatorColor = when {
        isSelected -> primaryColor
        isWinning -> primaryColor.copy(alpha = 0.82f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }

    val shape = RoundedCornerShape(14.dp)
    val accessibilityState = if (showResults) {
        buildString {
            if (isSelected) append("Selected, ")
            append("$percentInt percent")
            if (choice.votes > 0L) {
                append(", ${FormatUtils.compactCount(choice.votes)} votes")
            }
        }
    } else {
        "Not selected"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(
                if (isSubmittingThis) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .drawBehind {
                if (showResults && animatedFill > 0f) {
                    val barWidth = (size.width * animatedFill.coerceIn(0.02f, 1f))
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(backdropStartColor, backdropEndColor),
                            startX = 0f,
                            endX = barWidth.coerceAtLeast(1f),
                        ),
                        size = Size(width = barWidth, height = size.height),
                    )
                }
            }
            .border(
                width = if (isSelected || isSubmittingThis) 1.5.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .semantics(mergeDescendants = true) {
                selected = isSelected
                stateDescription = accessibilityState
            }
            .clickable(
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag("poll_option_${tweetId}_${choice.index}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    when {
                        isSubmittingThis -> {
                            IlayLoadingIndicator(
                                size = 18.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        isSelected -> {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.poll_your_vote_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        !showResults -> {
                            Icon(
                                imageVector = Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected || isWinning) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (showResults) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "$percentInt%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected || isWinning) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected || isWinning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // Crisp Material 3 progress bar showing voting percentage distribution
            if (showResults) {
                LinearProgressIndicator(
                    progress = { animatedFill },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .testTag("poll_progress_${tweetId}_${choice.index}"),
                    color = trackIndicatorColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

private fun formatPollRemaining(endsAtEpochMs: Long, nowEpochMs: Long): String? {
    if (endsAtEpochMs <= 0L) return null
    val diffMs = endsAtEpochMs - nowEpochMs
    if (diffMs <= 0L) return "Final"
    val totalMins = diffMs / 60_000L
    val days = totalMins / (60L * 24L)
    val hours = (totalMins % (60L * 24L)) / 60L
    val mins = totalMins % 60L
    return when {
        days >= 1L -> "${days}d ${hours}h left"
        hours >= 1L -> "${hours}h left"
        mins >= 1L -> "${mins}m left"
        else -> "<1m left"
    }
}
