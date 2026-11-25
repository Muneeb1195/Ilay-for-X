package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.example.data.remote.IlayImageCache
import com.example.data.remote.LocalImageQualityMode
import com.example.domain.model.ArticleBlock
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.Tweet
import com.example.domain.model.XArticle
import com.example.ui.components.ArticleRichBlock
import com.example.ui.components.FormatUtils
import com.example.ui.components.UserAvatar
import com.example.ui.theme.BookmarkAmber
import com.example.ui.theme.LikeRose
import com.example.ui.theme.RepostEmerald
import com.example.ui.theme.VerifiedBlue

/**
 * Full-screen native Jetpack Compose X Articles Reader with reading progress indicator,
 * dynamic text scaling (A-/A+), hero cover artwork, formatted Draft.js headings/quotes/lists/code,
 * tappable inline media integrated with the swipeable media lightbox, and engagement bar.
 */
@Composable
fun ArticleReaderScreen(
    tweet: Tweet,
    article: XArticle,
    isHydrating: Boolean,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val imageQualityMode = LocalImageQualityMode.current
    val listState = rememberLazyListState()
    var textScale by rememberSaveable(article.id) { mutableFloatStateOf(1.0f) }

    val timestampEpoch = if (article.publishedAtEpochMs > 0L) article.publishedAtEpochMs else tweet.createdAtEpochMs
    val fullTimestamp = remember(timestampEpoch, context) {
        FormatUtils.formatFullDeviceTimestamp(context, timestampEpoch)
    }

    // Collect cover image + all inline article photos for swipeable full-screen lightbox viewing
    val articleMediaGallery: List<MediaAttachment> = remember(article.coverImageUrl, article.bodyBlocks) {
        buildList {
            if (article.coverImageUrl.isNotBlank()) {
                add(
                    MediaAttachment(
                        id = "${article.id}_cover",
                        type = MediaType.PHOTO,
                        url = article.coverImageUrl,
                        thumbnailUrl = article.coverImageUrl,
                        altText = article.title,
                    )
                )
            }
            article.bodyBlocks.forEachIndexed { idx, block ->
                if (block.type == "media" && block.imageUrl.isNotBlank() && none { it.url == block.imageUrl }) {
                    add(
                        MediaAttachment(
                            id = "${article.id}_media_$idx",
                            type = MediaType.PHOTO,
                            url = block.imageUrl,
                            thumbnailUrl = block.imageUrl,
                            altText = block.caption.ifBlank { article.title },
                        )
                    )
                }
            }
        }
    }

    // Precompute ordered-list numbering for each block index
    val indexedBlocks: List<Pair<ArticleBlock, Int>> = remember(article.bodyBlocks) {
        var orderCounter = 0
        article.bodyBlocks.map { block ->
            if (block.type == "ordered-list-item") {
                orderCounter++
            } else {
                orderCounter = 0
            }
            block to orderCounter
        }
    }

    val readProgress by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) {
                0f
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                ((lastVisible + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val shareUrl = article.articleUrl.ifBlank {
        if (tweet.id.isNotBlank()) "https://x.com/${tweet.author.handle}/status/${tweet.id}" else "https://x.com"
    }

    fun openImageUrl(url: String) {
        val idx = articleMediaGallery.indexOfFirst { it.url == url }.coerceAtLeast(0)
        if (articleMediaGallery.isNotEmpty()) {
            onOpenMedia(articleMediaGallery, idx)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("article_screen_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }

                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Article · ${article.readTimeMinutes} min read",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        IconButton(
                            onClick = { textScale = (textScale - 0.1f).coerceAtLeast(0.85f) },
                            enabled = textScale > 0.86f,
                            modifier = Modifier.testTag("article_text_decrease_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.TextDecrease,
                                contentDescription = "Decrease text size",
                            )
                        }

                        IconButton(
                            onClick = { textScale = (textScale + 0.1f).coerceAtMost(1.35f) },
                            enabled = textScale < 1.34f,
                            modifier = Modifier.testTag("article_text_increase_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.TextIncrease,
                                contentDescription = "Increase text size",
                            )
                        }

                        IconButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, article.title)
                                    putExtra(Intent.EXTRA_TEXT, "${article.title}\n$shareUrl")
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(sendIntent, "Share article"))
                                }
                            },
                            modifier = Modifier.testTag("article_share_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = "Share article",
                            )
                        }
                    }

                    if (isHydrating) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .testTag("article_hydrating_indicator"),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { readProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            IconButton(
                                onClick = { if (tweet.id.isNotBlank()) onOpenThread(tweet) },
                                enabled = tweet.id.isNotBlank(),
                                modifier = Modifier.testTag("article_bottom_replies_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = "View discussion replies",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { if (tweet.id.isNotBlank()) onRepost(tweet) },
                                enabled = tweet.id.isNotBlank(),
                                modifier = Modifier.testTag("article_bottom_repost_button"),
                            ) {
                                Icon(
                                    imageVector = if (tweet.viewerState.isReposted) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                                    contentDescription = "Repost article",
                                    tint = if (tweet.viewerState.isReposted) RepostEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { if (tweet.id.isNotBlank()) onLike(tweet) },
                                enabled = tweet.id.isNotBlank(),
                                modifier = Modifier.testTag("article_bottom_like_button"),
                            ) {
                                Icon(
                                    imageVector = if (tweet.viewerState.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Like article",
                                    tint = if (tweet.viewerState.isLiked) LikeRose else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { if (tweet.id.isNotBlank()) onBookmark(tweet) },
                                enabled = tweet.id.isNotBlank(),
                                modifier = Modifier.testTag("article_bottom_bookmark_button"),
                            ) {
                                Icon(
                                    imageVector = if (tweet.viewerState.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = "Bookmark article",
                                    tint = if (tweet.viewerState.isBookmarked) BookmarkAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, shareUrl.toUri()))
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("article_open_on_x_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Open on X",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .testTag("article_reader_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (article.coverImageUrl.isNotBlank()) {
                    item(key = "article_cover") {
                        val coverRequest = remember(article.coverImageUrl, context, imageQualityMode) {
                            IlayImageCache.cardMediaRequest(
                                context = context,
                                rawUrl = article.coverImageUrl,
                                compact = false,
                                mode = imageQualityMode,
                            )
                        }
                        AsyncImage(
                            model = coverRequest,
                            contentDescription = article.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(228.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { openImageUrl(article.coverImageUrl) }
                                .testTag("article_cover_image"),
                        )
                    }
                }

                item(key = "article_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = article.title.ifBlank { "X Article" },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = (28f * textScale).sp,
                                lineHeight = (35f * textScale).sp,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("article_title_text"),
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (tweet.author.handle.isNotBlank()) {
                                        onOpenProfile(tweet.author.handle)
                                    }
                                }
                                .padding(vertical = 4.dp)
                                .testTag("article_author_row"),
                        ) {
                            UserAvatar(
                                url = tweet.author.avatarUrl,
                                displayName = tweet.author.displayName,
                                size = 44.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = tweet.author.displayName.ifBlank { "@${tweet.author.handle}" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (tweet.author.isVerified) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Filled.Verified,
                                            contentDescription = "Verified account",
                                            tint = VerifiedBlue,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = buildString {
                                        if (tweet.author.handle.isNotBlank()) append("@${tweet.author.handle}")
                                        if (fullTimestamp.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(fullTimestamp)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (indexedBlocks.isNotEmpty()) {
                    itemsIndexed(
                        items = indexedBlocks,
                        key = { idx, (block, _) -> "block_${idx}_${block.type}_${block.text.hashCode()}_${block.imageUrl.hashCode()}" },
                    ) { _, (block, orderIdx) ->
                        ArticleRichBlock(
                            block = block,
                            orderedIndex = orderIdx,
                            textScale = textScale,
                            onOpenMedia = ::openImageUrl,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                } else {
                    item(key = "article_preview_fallback") {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (article.previewText.isNotBlank()) {
                                Text(
                                    text = article.previewText,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = (16.5f * textScale).sp,
                                        lineHeight = (27f * textScale).sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (!isHydrating) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            text = "Only the article summary was returned by X.",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "You can open the full article directly on X or view the post discussion.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            if (tweet.id.isNotBlank()) {
                                                FilledTonalButton(
                                                    onClick = { onOpenThread(tweet) },
                                                    shape = CircleShape,
                                                ) {
                                                    Text("Discussion")
                                                }
                                            }
                                            Button(
                                                onClick = {
                                                    runCatching {
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, shareUrl.toUri()))
                                                    }
                                                },
                                                shape = CircleShape,
                                            ) {
                                                Text("Read on X")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (tweet.id.isNotBlank()) {
                    item(key = "article_footer_discussion") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            FilledTonalButton(
                                onClick = { onOpenThread(tweet) },
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("article_open_discussion_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                val repliesLabel = FormatUtils.compactCount(tweet.metrics.replies)
                                Text(
                                    text = if (repliesLabel.isNotBlank()) "View Discussion ($repliesLabel replies)" else "View Discussion & Replies",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
