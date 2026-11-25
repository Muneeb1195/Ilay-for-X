package com.example.data.repository

import com.example.data.local.BookmarkDao
import com.example.data.local.BookmarkEntity
import com.example.domain.model.Tweet
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Local Room-backed Repository for Bookmarks.
 * Persists full serialized Tweet snapshots (text, author, media metadata, quotes, polls)
 * in SQLite so users can save, search, and read their favorite tweets offline
 * from both the Profile view and the Bookmarks archive.
 */
class BookmarkRepository(
    private val bookmarkDao: BookmarkDao,
    private val tweetAdapter: JsonAdapter<Tweet>,
) {
    fun observeBookmarks(query: String = ""): Flow<List<Pair<BookmarkEntity, Tweet>>> {
        val trimmed = query.trim()
        val source = if (trimmed.isNotBlank()) {
            bookmarkDao.searchBookmarks(trimmed)
        } else {
            bookmarkDao.observeAll()
        }
        return source.map { rows ->
            rows.mapNotNull { row ->
                runCatching { tweetAdapter.fromJson(row.tweetJson) }.getOrNull()?.let { tweet ->
                    row to tweet.copy(viewerState = tweet.viewerState.copy(isBookmarked = true))
                }
            }
        }
    }

    suspend fun getBookmarkById(tweetId: String): Tweet? = withContext(Dispatchers.IO) {
        bookmarkDao.findById(tweetId)?.let { row ->
            runCatching { tweetAdapter.fromJson(row.tweetJson) }.getOrNull()?.let { tweet ->
                tweet.copy(viewerState = tweet.viewerState.copy(isBookmarked = true))
            }
        }
    }

    suspend fun saveBookmark(
        tweet: Tweet,
        folderTag: String = "Saved",
        isSyncedWithX: Boolean = false,
    ): Tweet = withContext(Dispatchers.IO) {
        val marked = tweet.copy(viewerState = tweet.viewerState.copy(isBookmarked = true))
        bookmarkDao.upsert(
            BookmarkEntity(
                tweetId = marked.id,
                authorHandle = marked.author.handle,
                authorName = marked.author.displayName,
                textSearchable = buildSearchableText(marked),
                tweetJson = tweetAdapter.toJson(marked),
                folderTag = folderTag,
                isSyncedWithX = isSyncedWithX,
            )
        )
        marked
    }

    /**
     * Persists a page of remote bookmarks while strictly preserving newest-to-oldest order.
     * - On Page 1 ([appendOlderPage] == false): atomically replaces previously synced X rows
     *   with strictly descending timestamps so stale/inverted rows can never corrupt ordering,
     *   while preserving offline-only local bookmarks (`isSyncedWithX = false`).
     * - On Page 2+ ([appendOlderPage] == true): skips already-stored tweet IDs to avoid
     *   cursor-boundary reordering and assigns timestamps strictly older than the oldest row.
     */
    suspend fun saveAllBookmarks(
        tweets: List<Tweet>,
        folderTag: String = "Synced from X",
        isSyncedWithX: Boolean = true,
        appendOlderPage: Boolean = false,
    ): Int = withContext(Dispatchers.IO) {
        if (tweets.isEmpty()) return@withContext 0
        val distinctTweets = tweets.distinctBy { it.id }
        val now = System.currentTimeMillis()

        if (!appendOlderPage) {
            val existingFolderById = bookmarkDao.getAllOnce().associate { it.tweetId to it.folderTag }
            val rows = distinctTweets.mapIndexed { index, t ->
                val marked = t.copy(viewerState = t.viewerState.copy(isBookmarked = true))
                val existingFolder = existingFolderById[marked.id]
                val effectiveFolder = when {
                    !existingFolder.isNullOrBlank() &&
                        existingFolder != "Synced from X" &&
                        existingFolder != "Saved" -> existingFolder
                    else -> folderTag
                }
                BookmarkEntity(
                    tweetId = marked.id,
                    authorHandle = marked.author.handle,
                    authorName = marked.author.displayName,
                    textSearchable = buildSearchableText(marked),
                    tweetJson = tweetAdapter.toJson(marked),
                    folderTag = effectiveFolder,
                    isSyncedWithX = isSyncedWithX,
                    // Strictly descending timestamps guarantee index 0 (most recently bookmarked) always renders first
                    savedAtEpochMs = now - (index.toLong() * 1_000L),
                )
            }
            if (isSyncedWithX) {
                bookmarkDao.replaceSyncedBookmarks(rows)
            } else {
                bookmarkDao.upsertAll(rows)
            }
            return@withContext rows.size
        }

        val existingIds = bookmarkDao.getBookmarkedIdsOnce().toHashSet()
        val newOlderTweets = distinctTweets.filterNot { it.id in existingIds }
        if (newOlderTweets.isEmpty()) return@withContext 0

        val oldestExisting = bookmarkDao.getOldestSavedAtEpochMs() ?: now
        val baseEpochMs = minOf(now, oldestExisting) - 10_000L
        val rows = newOlderTweets.mapIndexed { index, t ->
            val marked = t.copy(viewerState = t.viewerState.copy(isBookmarked = true))
            BookmarkEntity(
                tweetId = marked.id,
                authorHandle = marked.author.handle,
                authorName = marked.author.displayName,
                textSearchable = buildSearchableText(marked),
                tweetJson = tweetAdapter.toJson(marked),
                folderTag = folderTag,
                isSyncedWithX = isSyncedWithX,
                savedAtEpochMs = baseEpochMs - (index.toLong() * 1_000L),
            )
        }
        bookmarkDao.upsertAll(rows)
        rows.size
    }

    suspend fun updateFolderTag(tweetId: String, folderTag: String) = withContext(Dispatchers.IO) {
        val clean = folderTag.trim().ifBlank { "Saved" }
        val existing = bookmarkDao.findById(tweetId) ?: return@withContext
        bookmarkDao.upsert(existing.copy(folderTag = clean))
    }

    /**
     * Toggles a tweet's local bookmark state in Room.
     * Returns Pair(isNowBookmarked, updatedTweet).
     */
    suspend fun toggleBookmark(
        tweet: Tweet,
        folderTag: String = "Saved",
        isSyncedWithX: Boolean = false,
    ): Pair<Boolean, Tweet> = withContext(Dispatchers.IO) {
        val currentlyBookmarked = bookmarkDao.isBookmarked(tweet.id)
        val targetBookmark = !currentlyBookmarked
        val updated = tweet.copy(
            viewerState = tweet.viewerState.copy(isBookmarked = targetBookmark),
            metrics = tweet.metrics.copy(
                bookmarks = (tweet.metrics.bookmarks + if (targetBookmark) 1 else -1).coerceAtLeast(0L)
            ),
        )
        if (targetBookmark) {
            saveBookmark(updated, folderTag = folderTag, isSyncedWithX = isSyncedWithX)
        } else {
            bookmarkDao.deleteById(tweet.id)
        }
        targetBookmark to updated
    }

    suspend fun updateTweetSnapshotIfBookmarked(tweet: Tweet) = withContext(Dispatchers.IO) {
        val existing = bookmarkDao.findById(tweet.id) ?: return@withContext
        val updated = tweet.copy(viewerState = tweet.viewerState.copy(isBookmarked = true))
        bookmarkDao.upsert(
            existing.copy(
                tweetJson = tweetAdapter.toJson(updated),
                textSearchable = buildSearchableText(updated),
            )
        )
    }

    private fun buildSearchableText(tweet: Tweet): String = buildString {
        append(tweet.text)
        tweet.article?.let { art ->
            append(' ')
            append(art.title)
            append(' ')
            append(art.previewText)
        }
        tweet.quotedTweet?.let {
            append(' ')
            append(it.text)
            append(' ')
            append(it.author.handle)
        }
        tweet.linkPreview?.let {
            append(' ')
            append(it.title)
            append(' ')
            append(it.domain)
        }
    }
}
