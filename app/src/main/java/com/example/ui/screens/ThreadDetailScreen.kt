package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.local.AppPreferences
import com.example.domain.model.MediaAttachment
import com.example.domain.model.Tweet
import com.example.ui.components.IlayContainedLoadingIndicator
import com.example.ui.components.ThreadedTweetActions
import com.example.ui.components.ThreadedTweetView
import com.example.ui.viewmodel.ThreadTab
import com.example.ui.viewmodel.ThreadUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailScreen(
    uiState: ThreadUiState,
    preferences: AppPreferences,
    onBack: () -> Unit,
    onSelectTab: (ThreadTab) -> Unit,
    onLoadMoreQuotes: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReplyFull: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
) {
    val threadActions = remember(
        onSelectTab,
        onLoadMoreQuotes,
        onOpenThread,
        onOpenProfile,
        onLike,
        onRepost,
        onQuote,
        onReplyFull,
        onBookmark,
        onOpenMedia,
    ) {
        ThreadedTweetActions(
            onSelectTab = onSelectTab,
            onLoadMoreQuotes = onLoadMoreQuotes,
            onOpenThread = onOpenThread,
            onOpenProfile = onOpenProfile,
            onLike = onLike,
            onRepost = onRepost,
            onQuote = onQuote,
            onReply = onReplyFull,
            onBookmark = onBookmark,
            onOpenMedia = onOpenMedia,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("thread_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            },
            title = { Text("Thread", style = MaterialTheme.typography.titleLarge) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                uiState.isLoading && uiState.conversation == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        IlayContainedLoadingIndicator()
                    }
                }
                uiState.conversation == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = uiState.errorMessage ?: "Unable to load this conversation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    ThreadedTweetView(
                        conversation = uiState.conversation,
                        selectedTab = uiState.selectedTab,
                        quotes = uiState.quotes,
                        quotesCursor = uiState.quotesCursor,
                        isLoadingReplies = uiState.isLoadingReplies,
                        isLoadingQuotes = uiState.isLoadingQuotes,
                        isLoadingMoreQuotes = uiState.isLoadingMoreQuotes,
                        quotesError = uiState.quotesError,
                        preferences = preferences,
                        actions = threadActions,
                    )
                }
            }
        }
    }
}
