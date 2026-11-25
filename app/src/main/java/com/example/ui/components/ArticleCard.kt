package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.example.data.remote.IlayImageCache
import com.example.data.remote.LocalImageQualityMode
import com.example.domain.model.ArticleBlock
import com.example.domain.model.Tweet
import com.example.domain.model.XArticle
import com.example.ui.theme.BookmarkAmber
import com.example.ui.theme.LikeRose
import com.example.ui.theme.RepostEmerald
import com.example.ui.theme.VerifiedBlue

private val ARTICLE_PARAGRAPH_REGEX = Regex("\\n{2,}")
private val ARTICLE_WORD_REGEX = Regex("\\s+")

/**
 * Returns true if the tweet contains a native X Article or links directly to an `/article/` page.
 */
fun Tweet.hasReadableArticle(): Boolean =
    article != null || linkPreview?.url?.contains("/article/", ignoreCase = true) == true

/**
 * Resolves a Tweet into a displayable XArticle (either native parsed `tweet.article` or a
 * synthesized fallback when an article tweet only carries a `/article/` link preview).
 */
fun Tweet.resolveArticleOrFallback(): XArticle {
    article?.let { return it }
    val preview = linkPreview
    val fallbackTitle = preview?.title?.takeIf { it.isNotBlank() }
        ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.take(100)
        ?: "Article by @${author.handle}"
    val fallbackExcerpt = preview?.description?.takeIf { it.isNotBlank() }
        ?: text
    return XArticle(
        id = id,
        title = fallbackTitle,
        previewText = fallbackExcerpt,
        bodyBlocks = text.split(ARTICLE_PARAGRAPH_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { ArticleBlock(type = "unstyled", text = it) },
        coverImageUrl = preview?.imageUrl.orEmpty()
            .ifBlank { media.firstOrNull()?.thumbnailUrl.orEmpty() },
        readTimeMinutes = (text.split(ARTICLE_WORD_REGEX).size / 200).coerceAtLeast(1),
        publishedAtEpochMs = createdAtEpochMs,
        articleUrl = preview?.url?.takeIf { it.isNotBlank() }
            ?: "https://x.com/${author.handle}/status/$id",
    )
}

/**
 * Rich embedded X Article preview card displayed inside regular timeline TweetCards
 * and ThreadDetail focal cards when a post publishes a long-form X Article.
 */
@Composable
fun EmbeddedArticlePreviewCard(
    article: XArticle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cardShape = RoundedCornerShape(18.dp)
    Surface(
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(onClick = onClick)
            .testTag("embedded_article_card_${article.id}"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (article.coverImageUrl.isNotBlank()) {
                val context = LocalContext.current
                val imageQualityMode = LocalImageQualityMode.current
                val coverRequest = remember(article.coverImageUrl, context, compact, imageQualityMode) {
                    IlayImageCache.cardMediaRequest(
                        context = context,
                        rawUrl = article.coverImageUrl,
                        compact = compact,
                        mode = imageQualityMode,
                    )
                }
                AsyncImage(
                    model = coverRequest,
                    contentDescription = article.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 118.dp else 168.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "Article",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        text = "${article.readTimeMinutes} min read",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = article.title,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (article.previewText.isNotBlank()) {
                    Text(
                        text = article.previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Editorial MD3E card used inside the Profile -> Articles tab for browsing a creator's
 * published long-form X Articles.
 */
@Composable
fun ProfileArticleCard(
    tweet: Tweet,
    onOpenArticle: (Tweet, XArticle) -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val article = remember(tweet) { tweet.resolveArticleOrFallback() }
    val timestampEpoch = if (article.publishedAtEpochMs > 0L) article.publishedAtEpochMs else tweet.createdAtEpochMs
    val formattedTime = remember(timestampEpoch, context) {
        FormatUtils.formatDeviceTime(context, timestampEpoch)
    }

    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .clickable { onOpenArticle(tweet, article) }
            .testTag("profile_article_card_${article.id}"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (article.coverImageUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    AsyncImage(
                        model = article.coverImageUrl,
                        contentDescription = article.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Article · ${article.readTimeMinutes} min read",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (article.coverImageUrl.isBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Article,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Article",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "${article.readTimeMinutes} min read",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                if (article.previewText.isNotBlank()) {
                    Text(
                        text = article.previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Author byline + device default timestamp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenProfile(tweet.author.handle) }
                        .padding(vertical = 2.dp),
                ) {
                    UserAvatar(
                        url = tweet.author.avatarUrl,
                        displayName = tweet.author.displayName,
                        size = 24.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tweet.author.displayName.ifBlank { "@${tweet.author.handle}" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tweet.author.isVerified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Verified account",
                            tint = VerifiedBlue,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (formattedTime.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· $formattedTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArticleMetricButton(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        count = tweet.metrics.replies,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "Open replies",
                        onClick = { onOpenThread(tweet) },
                    )
                    ArticleMetricButton(
                        icon = if (tweet.viewerState.isReposted) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                        count = tweet.metrics.reposts,
                        tint = if (tweet.viewerState.isReposted) RepostEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "Repost article",
                        onClick = { onRepost(tweet) },
                    )
                    ArticleMetricButton(
                        icon = if (tweet.viewerState.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        count = tweet.metrics.likes,
                        tint = if (tweet.viewerState.isLiked) LikeRose else MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "Like article",
                        onClick = { onLike(tweet) },
                    )
                    ArticleMetricButton(
                        icon = if (tweet.viewerState.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        count = tweet.metrics.bookmarks,
                        tint = if (tweet.viewerState.isBookmarked) BookmarkAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "Bookmark article",
                        onClick = { onBookmark(tweet) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleMetricButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Long,
    tint: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        val label = FormatUtils.compactCount(count)
        if (label.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

/**
 * Native MD3E in-app Article Reader sheet rendering X Article Draft.js rich blocks
 * (`header-one`, `header-two`, `header-three`, `blockquote`, list items, `code-block`, body paragraphs)
 * with cover art, byline, device-formatted timestamp, and links to replies or x.com.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleReaderSheet(
    tweet: Tweet,
    article: XArticle,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val timestampEpoch = if (article.publishedAtEpochMs > 0L) article.publishedAtEpochMs else tweet.createdAtEpochMs
    val fullTimestamp = remember(timestampEpoch, context) {
        FormatUtils.formatFullDeviceTimestamp(context, timestampEpoch)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Article,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "X Article · ${article.readTimeMinutes} min read",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("article_reader_close_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close article reader",
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Scrollable editorial article body
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (article.coverImageUrl.isNotBlank()) {
                    AsyncImage(
                        model = article.coverImageUrl,
                        contentDescription = article.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }

                Text(
                    text = article.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Author + device timestamp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onDismiss()
                            onOpenProfile(tweet.author.handle)
                        }
                        .padding(vertical = 4.dp),
                ) {
                    UserAvatar(
                        url = tweet.author.avatarUrl,
                        displayName = tweet.author.displayName,
                        size = 40.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tweet.author.displayName.ifBlank { "@${tweet.author.handle}" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (tweet.author.isVerified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.Verified,
                                    contentDescription = "Verified account",
                                    tint = VerifiedBlue,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Text(
                            text = "@${tweet.author.handle}" + if (fullTimestamp.isNotBlank()) " · $fullTimestamp" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Rich Draft.js content blocks
                if (article.bodyBlocks.isNotEmpty()) {
                    var orderedIndex = 0
                    for (block in article.bodyBlocks) {
                        if (block.type == "ordered-list-item") {
                            orderedIndex++
                        } else {
                            orderedIndex = 0
                        }
                        ArticleRichBlock(block = block, orderedIndex = orderedIndex)
                    }
                } else if (article.previewText.isNotBlank()) {
                    Text(
                        text = article.previewText,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Bottom actions: View replies & Open on X
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            onDismiss()
                            onOpenThread(tweet)
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("article_open_discussion_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Replies")
                    }

                    if (article.articleUrl.isNotBlank()) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, article.articleUrl.toUri()))
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("article_open_on_x_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open on X")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun ArticleRichBlock(
    block: ArticleBlock,
    orderedIndex: Int,
    textScale: Float = 1f,
    onOpenMedia: ((String) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val safeScale = textScale.coerceIn(0.8f, 1.5f)
    when (block.type) {
        "media" -> {
            if (block.imageUrl.isNotBlank()) {
                val context = LocalContext.current
                val imageQualityMode = LocalImageQualityMode.current
                val mediaReq = remember(block.imageUrl, context, imageQualityMode) {
                    IlayImageCache.cardMediaRequest(
                        context = context,
                        rawUrl = block.imageUrl,
                        compact = false,
                        mode = imageQualityMode,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AsyncImage(
                        model = mediaReq,
                        contentDescription = block.caption.ifBlank { "Article image" },
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 440.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .then(
                                if (onOpenMedia != null) {
                                    Modifier.clickable { onOpenMedia(block.imageUrl) }
                                } else {
                                    Modifier
                                }
                            )
                            .testTag("article_inline_media"),
                    )
                    if (block.caption.isNotBlank()) {
                        Text(
                            text = block.caption,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = FontStyle.Italic,
                                fontSize = (13f * safeScale).sp,
                                lineHeight = (19f * safeScale).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        "divider" -> {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        "header-one" -> Text(
            text = block.text,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = (24f * safeScale).sp,
                lineHeight = (31f * safeScale).sp,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        "header-two" -> Text(
            text = block.text,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = (21f * safeScale).sp,
                lineHeight = (28f * safeScale).sp,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        "header-three" -> Text(
            text = block.text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (18f * safeScale).sp,
                lineHeight = (25f * safeScale).sp,
            ),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        "blockquote" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontStyle = FontStyle.Italic,
                        fontSize = (16f * safeScale).sp,
                        lineHeight = (25f * safeScale).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        "unordered-list-item" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = (16f * safeScale).sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                IlayLinkMovementText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = (16f * safeScale).sp,
                        lineHeight = (26f * safeScale).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    onUrlClick = onOpenUrl,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        "ordered-list-item" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${orderedIndex.coerceAtLeast(1)}.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = (16f * safeScale).sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(26.dp),
                )
                IlayLinkMovementText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = (16f * safeScale).sp,
                        lineHeight = (26f * safeScale).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    onUrlClick = onOpenUrl,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        "code-block" -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (13.5f * safeScale).sp,
                        lineHeight = (21f * safeScale).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        else -> {
            if (block.linkUrl.isNotBlank() && block.text == block.linkUrl) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenUrl?.invoke(block.linkUrl) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = FormatUtils.formatDisplayUrl(block.linkUrl),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (block.text.isNotBlank()) {
                IlayLinkMovementText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = (16.5f * safeScale).sp,
                        lineHeight = (27f * safeScale).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    onUrlClick = onOpenUrl,
                )
            }
        }
    }
}
