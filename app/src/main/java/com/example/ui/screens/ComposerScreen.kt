package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.local.AccountEntity
import com.example.data.local.DraftTweetEntity
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.components.UserAvatar
import com.example.ui.theme.BookmarkAmber
import com.example.ui.theme.ErrorCrimson
import com.example.ui.viewmodel.ComposerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(
    uiState: ComposerUiState,
    activeAccount: AccountEntity?,
    drafts: List<DraftTweetEntity>,
    onTextChange: (String) -> Unit,
    onAddMedia: (List<Uri>) -> Unit,
    onRemoveMedia: (Uri) -> Unit,
    onSaveDraft: () -> Unit,
    onLoadDraft: (DraftTweetEntity) -> Unit,
    onDeleteDraft: (Long) -> Unit,
    onPublish: () -> Unit,
    onClose: () -> Unit,
) {
    // Google Play compliant zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) onAddMedia(uris)
    }

    val charCount = uiState.text.length
    val maxStandard = 280
    val progress = (charCount.toFloat() / maxStandard.toFloat()).coerceIn(0f, 1f)
    val ringColor = when {
        charCount > maxStandard -> ErrorCrimson
        charCount > 240 -> BookmarkAmber
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(onClick = onClose, modifier = Modifier.testTag("composer_close_button")) {
                    Icon(Icons.Filled.Close, contentDescription = "Close composer")
                }
            },
            title = {
                Text(
                    text = when {
                        uiState.replyToTweetId != null -> "Reply"
                        uiState.quoteTweetId != null -> "Quote Post"
                        else -> "New Post"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            actions = {
                if (uiState.text.isNotBlank() || uiState.selectedMediaUris.isNotEmpty()) {
                    TextButton(
                        onClick = onSaveDraft,
                        modifier = Modifier.testTag("composer_save_draft_button")
                    ) {
                        Text("Draft")
                    }
                }
                Button(
                    onClick = onPublish,
                    enabled = !uiState.isPosting && (uiState.text.isNotBlank() || uiState.selectedMediaUris.isNotEmpty()),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .testTag("composer_publish_button"),
                ) {
                    if (uiState.isPosting) {
                        IlayLoadingIndicator(size = 18.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.action_post), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (!uiState.replyToHandle.isNullOrBlank()) {
                Text(
                    text = "Replying to @${uiState.replyToHandle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (uiState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.Top) {
                UserAvatar(
                    url = activeAccount?.avatarUrl.orEmpty(),
                    displayName = activeAccount?.displayName ?: "X",
                    size = 42.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = uiState.text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            text = if (uiState.replyToTweetId != null) stringResource(R.string.composer_reply_placeholder)
                            else stringResource(R.string.composer_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(180.dp)
                        .testTag("composer_text_input"),
                )
            }

            // Attached Photos Strip
            if (uiState.selectedMediaUris.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.selectedMediaUris) { uri ->
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Selected photo attachment",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(26.dp)
                                    .clickable { onRemoveMedia(uri) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Quote Post Preview Card
            if (!uiState.quoteTweetId.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Quoting @${uiState.quoteTweetAuthor.orEmpty()}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = uiState.quoteTweetSnippet.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Saved Local Drafts
            if (drafts.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Drafts, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saved Drafts (${drafts.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                drafts.forEach { draft ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onLoadDraft(draft) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = draft.text.ifBlank { "[Media Draft]" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onDeleteDraft(draft.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete draft", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // Bottom Toolbar (Photo Picker + Character Progress Ring)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = uiState.selectedMediaUris.size < 4,
                    modifier = Modifier.testTag("composer_attach_photo_button"),
                ) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = stringResource(R.string.cd_attach_photo),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "${uiState.selectedMediaUris.size}/4 photos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${maxStandard - charCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (charCount > 240) ringColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 3.dp,
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }
        }
    }
}
