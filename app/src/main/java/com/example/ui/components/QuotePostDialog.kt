package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.local.AccountEntity
import com.example.domain.model.QuotedTweet
import com.example.domain.model.Tweet
import kotlinx.coroutines.launch

private const val QUOTE_CHAR_LIMIT = 280
private const val QUOTE_MAX_PHOTOS = 4

fun buildQuoteTweetUrl(tweet: Tweet): String {
    val handle = tweet.author.handle.ifBlank { "i" }
    return "https://x.com/$handle/status/${tweet.id}"
}

/**
 * Material 3 Expressive Quote Post composition dialog.
 *
 * Allows the user to attach commentary (plus up to 4 zero-permission Photo Picker images)
 * while maintaining a faithful, non-navigating preview of the parent tweet right inside
 * the dialog—preserving the underlying feed/thread scroll position.
 */
@Composable
fun QuotePostDialog(
    parentTweet: Tweet,
    activeAccount: AccountEntity?,
    blurSensitiveMedia: Boolean,
    onDismiss: () -> Unit,
    onRequestSignIn: () -> Unit,
    onPublish: suspend (text: String, mediaUris: List<Uri>) -> Result<Tweet?>,
    onSaveDraft: suspend (text: String, mediaUris: List<Uri>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    var commentText by remember(parentTweet.id) { mutableStateOf("") }
    var attachedPhotos by remember(parentTweet.id) { mutableStateOf<List<Uri>>(emptyList()) }
    var isPublishing by remember(parentTweet.id) { mutableStateOf(false) }
    var isSavingDraft by remember(parentTweet.id) { mutableStateOf(false) }
    var errorMessage by remember(parentTweet.id) { mutableStateOf<String?>(null) }

    val quotedPreview = remember(parentTweet) { QuotedTweet.fromTweet(parentTweet) }
    val parentHandle = parentTweet.author.handle.ifBlank { "x_user" }
    val remainingChars = QUOTE_CHAR_LIMIT - commentText.length
    val hasContent = commentText.isNotBlank() || attachedPhotos.isNotEmpty()
    val canPublish = activeAccount != null && hasContent && remainingChars >= 0 && !isPublishing && !isSavingDraft
    val canSaveDraft = hasContent && !isPublishing && !isSavingDraft

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = QUOTE_MAX_PHOTOS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            attachedPhotos = (attachedPhotos + uris).distinct().take(QUOTE_MAX_PHOTOS)
            errorMessage = null
        }
    }

    LaunchedEffect(parentTweet.id, activeAccount != null) {
        if (activeAccount != null) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = { if (!isPublishing && !isSavingDraft) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isPublishing && !isSavingDraft,
            dismissOnClickOutside = !isPublishing && !isSavingDraft,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .imePadding()
                .testTag("quote_post_dialog"),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { if (!isPublishing && !isSavingDraft) onDismiss() },
                        enabled = !isPublishing && !isSavingDraft,
                        modifier = Modifier.testTag("quote_dialog_close_btn"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.quote_post_close_cd),
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.quote_post_action),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (activeAccount != null) {
                            Text(
                                text = "@${activeAccount.handle}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            if (canSaveDraft) {
                                scope.launch {
                                    isSavingDraft = true
                                    onSaveDraft(commentText.trim(), attachedPhotos)
                                    isSavingDraft = false
                                }
                            }
                        },
                        enabled = canSaveDraft,
                        modifier = Modifier.testTag("quote_dialog_draft_btn"),
                    ) {
                        Text(stringResource(R.string.quote_post_save_draft))
                    }

                    Spacer(Modifier.width(4.dp))

                    Button(
                        onClick = {
                            if (canPublish) {
                                scope.launch {
                                    isPublishing = true
                                    errorMessage = null
                                    val result = onPublish(commentText.trim(), attachedPhotos)
                                    isPublishing = false
                                    result.onFailure { err ->
                                        errorMessage = err.message ?: "Failed to publish quote post"
                                    }
                                }
                            }
                        },
                        enabled = canPublish,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("quote_dialog_publish_btn"),
                    ) {
                        if (isPublishing) {
                            IlayLoadingIndicator(
                                size = 18.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.action_post))
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Scrollable composer body + embedded parent tweet preview
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (activeAccount == null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.quote_post_sign_in_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                FilledTonalButton(
                                    onClick = onRequestSignIn,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("quote_dialog_sign_in_btn"),
                                ) {
                                    Text(stringResource(R.string.quote_post_sign_in_cta))
                                }
                            }
                        }
                    }

                    errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quote_dialog_error_banner"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // Active user avatar + comment input field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        UserAvatar(
                            url = activeAccount?.avatarUrl.orEmpty(),
                            displayName = activeAccount?.displayName ?: "You",
                            size = 40.dp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextField(
                            value = commentText,
                            onValueChange = {
                                commentText = it
                                if (errorMessage != null) errorMessage = null
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.quote_post_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            minLines = 3,
                            maxLines = 8,
                            enabled = !isPublishing && !isSavingDraft,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .testTag("quote_dialog_text_input"),
                        )
                    }

                    // Attached photos strip
                    if (attachedPhotos.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            itemsIndexed(attachedPhotos) { index, uri ->
                                Box(
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                ) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Attached photo ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    IconButton(
                                        onClick = { attachedPhotos = attachedPhotos - uri },
                                        enabled = !isPublishing,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.68f))
                                            .testTag("quote_dialog_remove_photo_$index"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.quote_post_remove_photo_cd),
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Parent content preview card (non-navigating)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quote_dialog_parent_preview"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(start = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FormatQuote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "Quoting @$parentHandle",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        EmbeddedQuotePostCard(
                            quoted = quotedPreview,
                            onOpenParent = {},
                            compact = false,
                            blurSensitiveMedia = blurSensitiveMedia,
                            onOpenProfile = null,
                            onOpenMedia = null,
                            interactive = false,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer toolbar: Photo Picker + remaining character counter ring
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !isPublishing && !isSavingDraft && attachedPhotos.size < QUOTE_MAX_PHOTOS,
                            modifier = Modifier.testTag("quote_dialog_attach_photo_btn"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.cd_attach_photo),
                                tint = if (attachedPhotos.size < QUOTE_MAX_PHOTOS) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                            )
                        }
                        if (attachedPhotos.isNotEmpty()) {
                            Text(
                                text = "${attachedPhotos.size}/$QUOTE_MAX_PHOTOS photos",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        val counterColor = when {
                            remainingChars < 0 -> MaterialTheme.colorScheme.error
                            remainingChars <= 20 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "$remainingChars",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (remainingChars <= 20) FontWeight.SemiBold else FontWeight.Normal,
                            color = counterColor,
                            modifier = Modifier.testTag("quote_dialog_char_count"),
                        )
                        val progress = (commentText.length.toFloat() / QUOTE_CHAR_LIMIT.toFloat()).coerceIn(0f, 1f)
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = if (remainingChars < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }
        }
    }
}
