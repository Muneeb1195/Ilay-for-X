package com.example.domain.ranking

import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.IlayDatabase
import com.example.data.local.SeenTweetEntity
import com.example.data.local.TopicInterestEntity
import com.example.data.local.TopicKind
import com.example.data.local.TopicManualState
import com.example.domain.model.Tweet
import com.example.domain.model.XUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class TweetImpressionEvent(
    val tweet: Tweet,
    val dwellMs: Long,
    val wasInteracted: Boolean = false,
    val wasSkipped: Boolean = false,
)

data class FeedbackUndoToken(
    val isNotInterestedPost: Boolean,
    val tweet: Tweet,
    val previousSeen: SeenTweetEntity? = null,
    val affectedTopics: List<String> = emptyList(),
    val previousAffinity: AuthorAffinityEntity? = null,
)

/**
 * Central on-device behavioral signal store with a strict **Risky & NSFW Firewall**.
 *
 * Guarantees:
 * - Risky/NSFW posts, profiles, domains, and tokens NEVER earn positive creator affinity or
 *   topic weights — even if the user visits those profiles, opens those tweets, likes/bookmarks
 *   them, searches for them, or spends time reading them.
 * - Any creator detected as risky/NSFW is flagged locally (`isRiskyOrNsfw = true`) with zeroed
 *   scores so even their plain-text posts are excluded from timelines and never enter Interests.
 */
class RankingSignalStore(
    private val db: IlayDatabase,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val startupInitialized = AtomicBoolean(false)
    @Volatile
    private var lastPruneEpochMs: Long = 0L

    val topicInterestsFlow: Flow<List<TopicInterestEntity>> = db.topicInterestDao().observeAll()
    val seenCountFlow: Flow<Int> = db.seenTweetDao().observeSeenCount()
    val dismissedCountFlow: Flow<Int> = db.seenTweetDao().observeDismissedCount()
    val riskySuppressedCountFlow: Flow<Int> = db.affinityDao().observeRiskySuppressedCount()

    /**
     * One-time startup/upgrade hygiene:
     * 1) Scrubs any pre-existing risky/NSFW rows in `author_affinities` and `topic_interests`.
     * 2) Seeds `topic_interests` from existing safe bookmarks & safe recent searches if empty.
     */
    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (!startupInitialized.compareAndSet(false, true)) return@withContext
        scrubRiskyStoredSignals()
        seedTopicsFromBookmarksAndSearchesIfEmpty()
    }

    suspend fun scrubRiskyStoredSignals() = withContext(Dispatchers.IO) {
        val now = nowProvider()
        val allAffinities = db.affinityDao().getAllOnce()
        allAffinities.forEach { entity ->
            if (!entity.isRiskyOrNsfw &&
                LocalForYouRanker.isRiskyOrNsfwHandleOrText(entity.handleLower, entity.displayName)
            ) {
                db.affinityDao().upsert(zeroOutAsRisky(entity, now))
            }
        }
        val allTopics = db.topicInterestDao().getAllOnce()
        allTopics.forEach { topic ->
            if (LocalForYouRanker.isRiskyOrNsfwToken(topic.tokenLower) ||
                LocalForYouRanker.isRiskyOrNsfwHandleOrText("", topic.displayLabel)
            ) {
                db.topicInterestDao().deleteByToken(topic.tokenLower)
            }
        }
    }

    suspend fun seedTopicsFromBookmarksAndSearchesIfEmpty() = withContext(Dispatchers.IO) {
        if (db.topicInterestDao().getAllOnce().isNotEmpty()) return@withContext
        val now = nowProvider()
        val bookmarks = db.bookmarkDao().getRecentSearchableTexts()
        val searches = db.recentSearchDao().getRecentQueriesOnce()
        if (bookmarks.isEmpty() && searches.isEmpty()) return@withContext

        val aggregated = LinkedHashMap<String, TopicInterestEntity>()
        fun accumulate(tokens: List<ExtractedTopicToken>, baseDelta: Double) {
            tokens.forEach { tok ->
                if (LocalForYouRanker.isRiskyOrNsfwToken(tok.tokenLower)) return@forEach
                val current = aggregated[tok.tokenLower]
                val added = baseDelta * tok.weightMultiplier
                aggregated[tok.tokenLower] = if (current == null) {
                    TopicInterestEntity(
                        tokenLower = tok.tokenLower,
                        displayLabel = tok.displayLabel,
                        kind = tok.kind,
                        positiveScore = added,
                        negativeScore = 0.0,
                        manualState = TopicManualState.AUTO,
                        lastUpdatedEpochMs = now,
                    )
                } else {
                    current.copy(positiveScore = (current.positiveScore + added).coerceAtMost(MAX_TOPIC_SCORE))
                }
            }
        }

        bookmarks.forEachIndexed { idx, txt ->
            if (!LocalForYouRanker.isRiskyOrNsfwHandleOrText("", "", txt)) {
                val weight = 1.4 / (1.0 + idx * 0.05)
                accumulate(LocalForYouRanker.extractSafeTokensFromText(txt), weight)
            }
        }
        searches.forEachIndexed { idx, q ->
            if (!q.startsWith("quoted_tweet_id:", ignoreCase = true) &&
                !q.contains("://") &&
                !LocalForYouRanker.isRiskyOrNsfwHandleOrText("", "", q)
            ) {
                val weight = 2.0 / (1.0 + idx * 0.08)
                accumulate(LocalForYouRanker.extractSafeTokensFromText(q), weight)
            }
        }

        val topSeed = aggregated.values
            .filter { it.positiveScore >= 1.2 }
            .sortedByDescending { it.positiveScore }
            .take(50)
        if (topSeed.isNotEmpty()) {
            db.topicInterestDao().upsertAll(topSeed)
        }
    }

    suspend fun getSuppressedRiskyHandles(): Set<String> = withContext(Dispatchers.IO) {
        db.affinityDao().getRiskySuppressedHandlesOnce().toHashSet()
    }

    suspend fun flagCreatorAsRiskyOrNsfw(handle: String, displayName: String = "") = withContext(Dispatchers.IO) {
        val lower = handle.trim().removePrefix("@").lowercase()
        if (lower.isBlank()) return@withContext
        val existing = db.affinityDao().getByHandle(lower)
            ?: AuthorAffinityEntity(handleLower = lower, displayName = displayName)
        db.affinityDao().upsert(zeroOutAsRisky(existing.copy(displayName = displayName.ifBlank { existing.displayName }), nowProvider()))
    }

    suspend fun recordProfileVisit(user: XUser) = withContext(Dispatchers.IO) {
        val lower = user.handle.trim().removePrefix("@").lowercase()
        if (lower.isBlank()) return@withContext
        val suppressed = getSuppressedRiskyHandles()
        if (LocalForYouRanker.isRiskyOrNsfwUser(user, suppressed)) {
            flagCreatorAsRiskyOrNsfw(lower, user.displayName)
            return@withContext
        }
        if (user.isMuted || user.isBlocked) return@withContext
        val current = db.affinityDao().getByHandle(lower)
            ?: AuthorAffinityEntity(handleLower = lower, displayName = user.displayName)
        if (current.isRiskyOrNsfw) return@withContext
        db.affinityDao().upsert(
            current.copy(
                displayName = user.displayName.ifBlank { current.displayName },
                profileOpens = current.profileOpens + 1,
                lastInteractedEpochMs = nowProvider(),
            )
        )
    }

    suspend fun recordTweetInteraction(
        tweet: Tweet,
        like: Boolean = false,
        repost: Boolean = false,
        reply: Boolean = false,
        bookmark: Boolean = false,
        threadOpen: Boolean = false,
        mediaOpen: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val suppressed = getSuppressedRiskyHandles()
        val now = nowProvider()

        // Hard Risky & NSFW Firewall: even if the user interacts with or opens a risky/NSFW tweet,
        // write ZERO creator affinity and ZERO topic weights, and flag risky creators locally.
        if (LocalForYouRanker.isRiskyOrNsfwTweet(tweet, suppressed)) {
            if (tweet.isSensitive || LocalForYouRanker.isRiskyOrNsfwUser(tweet.author, suppressed) ||
                LocalForYouRanker.isRiskyOrNsfwHandleOrText("", "", tweet.text)
            ) {
                flagCreatorAsRiskyOrNsfw(tweet.author.handle, tweet.author.displayName)
            }
            return@withContext
        }

        val lower = tweet.author.handle.trim().removePrefix("@").lowercase()
        if (lower.isNotBlank()) {
            val current = db.affinityDao().getByHandle(lower)
                ?: AuthorAffinityEntity(handleLower = lower, displayName = tweet.author.displayName)
            if (!current.isRiskyOrNsfw) {
                db.affinityDao().upsert(
                    current.copy(
                        displayName = tweet.author.displayName.ifBlank { current.displayName },
                        likesGiven = current.likesGiven + if (like) 1 else 0,
                        repostsGiven = current.repostsGiven + if (repost) 1 else 0,
                        repliesGiven = current.repliesGiven + if (reply) 1 else 0,
                        bookmarksGiven = current.bookmarksGiven + if (bookmark) 1 else 0,
                        threadOpens = current.threadOpens + if (threadOpen || mediaOpen) 1 else 0,
                        lastInteractedEpochMs = now,
                    )
                )
            }
        }

        // Mark tweet as seen + interacted so next refresh knows you engaged with it
        if (tweet.id.isNotBlank()) {
            val prevSeen = db.seenTweetDao().getById(tweet.id)
            db.seenTweetDao().upsert(
                SeenTweetEntity(
                    tweetId = tweet.id,
                    authorHandleLower = lower,
                    firstSeenEpochMs = prevSeen?.firstSeenEpochMs ?: now,
                    lastSeenEpochMs = now,
                    viewCount = maxOf(1, prevSeen?.viewCount ?: 0),
                    totalDwellMs = prevSeen?.totalDwellMs ?: 0L,
                    wasInteracted = true,
                    wasSkipped = false,
                    isDismissed = prevSeen?.isDismissed ?: false,
                )
            )
        }

        val topicDelta = (if (like) 1.8 else 0.0) +
            (if (repost) 2.4 else 0.0) +
            (if (reply) 3.0 else 0.0) +
            (if (bookmark) 3.5 else 0.0) +
            (if (threadOpen) 1.2 else 0.0) +
            (if (mediaOpen) 1.0 else 0.0)

        if (topicDelta > 0.0) {
            val safeTokens = LocalForYouRanker.extractSafeTopicTokens(tweet, suppressed)
            reinforceSafeTopics(safeTokens, positiveDelta = topicDelta, negativeDelta = 0.0, now = now)
        }
    }

    suspend fun recordSearchInterest(query: String) = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank() || clean.startsWith("quoted_tweet_id:", ignoreCase = true) || clean.contains("://")) {
            return@withContext
        }
        // Risky/NSFW search queries never enter topic_interests
        if (LocalForYouRanker.isRiskyOrNsfwHandleOrText("", "", clean)) return@withContext
        val tokens = LocalForYouRanker.extractSafeTokensFromText(clean)
        reinforceSafeTopics(tokens, positiveDelta = 2.2, negativeDelta = 0.0, now = nowProvider())
    }

    suspend fun recordViewportImpressions(events: List<TweetImpressionEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        val now = nowProvider()
        val suppressed = getSuppressedRiskyHandles().toMutableSet()

        val seenUpserts = ArrayList<SeenTweetEntity>(events.size)
        val existingSeenMap = db.seenTweetDao()
            .getByIds(events.map { it.tweet.id }.distinct())
            .associateBy { it.tweetId }

        for (event in events) {
            val tweet = event.tweet
            if (tweet.id.isBlank()) continue
            val authorLower = tweet.author.handle.trim().removePrefix("@").lowercase()

            // Risky/NSFW Safety Firewall on dwell/views:
            // Never record dwell or topic learning for risky/NSFW tweets or creators, even if spent time on them!
            if (LocalForYouRanker.isRiskyOrNsfwTweet(tweet, suppressed)) {
                if (authorLower.isNotBlank() && authorLower !in suppressed) {
                    flagCreatorAsRiskyOrNsfw(authorLower, tweet.author.displayName)
                    suppressed.add(authorLower)
                }
                continue
            }

            val cappedDwellMs = event.dwellMs.coerceIn(0L, MAX_SINGLE_DWELL_MS)
            val isMeaningfulRead = cappedDwellMs >= MEANINGFUL_DWELL_MS || event.wasInteracted
            val isQuickSkip = event.wasSkipped && !isMeaningfulRead && !event.wasInteracted
            val countsAsSeen = cappedDwellMs >= SEEN_THRESHOLD_MS || event.wasInteracted || isQuickSkip

            if (!countsAsSeen) continue

            val prev = existingSeenMap[tweet.id]
            seenUpserts.add(
                SeenTweetEntity(
                    tweetId = tweet.id,
                    authorHandleLower = authorLower,
                    firstSeenEpochMs = prev?.firstSeenEpochMs ?: now,
                    lastSeenEpochMs = now,
                    viewCount = (prev?.viewCount ?: 0) + 1,
                    totalDwellMs = (prev?.totalDwellMs ?: 0L) + cappedDwellMs,
                    wasInteracted = (prev?.wasInteracted ?: false) || event.wasInteracted,
                    wasSkipped = if (event.wasInteracted || isMeaningfulRead) false else (prev?.wasSkipped ?: false) || isQuickSkip,
                    isDismissed = prev?.isDismissed ?: false,
                )
            )

            if (authorLower.isNotBlank()) {
                val curAff = db.affinityDao().getByHandle(authorLower)
                    ?: AuthorAffinityEntity(handleLower = authorLower, displayName = tweet.author.displayName)
                if (!curAff.isRiskyOrNsfw) {
                    if (isMeaningfulRead) {
                        val addedSec = (cappedDwellMs / 1000L).coerceIn(1L, 30L)
                        db.affinityDao().upsert(
                            curAff.copy(
                                displayName = tweet.author.displayName.ifBlank { curAff.displayName },
                                totalDwellSeconds = curAff.totalDwellSeconds + addedSec,
                                lastInteractedEpochMs = now,
                            )
                        )
                    } else if (isQuickSkip) {
                        db.affinityDao().upsert(
                            curAff.copy(
                                displayName = tweet.author.displayName.ifBlank { curAff.displayName },
                                skipsCount = curAff.skipsCount + 1,
                            )
                        )
                    }
                }
            }

            val tokens = LocalForYouRanker.extractSafeTopicTokens(tweet, suppressed)
            if (isMeaningfulRead) {
                reinforceSafeTopics(tokens, positiveDelta = 0.7, negativeDelta = 0.0, now = now)
            } else if (isQuickSkip) {
                reinforceSafeTopics(tokens, positiveDelta = 0.0, negativeDelta = 0.25, now = now)
            }
        }

        if (seenUpserts.isNotEmpty()) {
            db.seenTweetDao().upsertAll(seenUpserts)
        }
        pruneSeenIfNeeded(now)
    }

    suspend fun markNotInterested(tweet: Tweet): FeedbackUndoToken = withContext(Dispatchers.IO) {
        val now = nowProvider()
        val authorLower = tweet.author.handle.trim().removePrefix("@").lowercase()
        val prevSeen = db.seenTweetDao().getById(tweet.id)
        db.seenTweetDao().upsert(
            SeenTweetEntity(
                tweetId = tweet.id,
                authorHandleLower = authorLower,
                firstSeenEpochMs = prevSeen?.firstSeenEpochMs ?: now,
                lastSeenEpochMs = now,
                viewCount = maxOf(1, prevSeen?.viewCount ?: 0),
                totalDwellMs = prevSeen?.totalDwellMs ?: 0L,
                wasInteracted = prevSeen?.wasInteracted ?: false,
                wasSkipped = true,
                isDismissed = true,
            )
        )

        val suppressed = getSuppressedRiskyHandles()
        val affectedTokens = if (LocalForYouRanker.isRiskyOrNsfwTweet(tweet, suppressed)) {
            flagCreatorAsRiskyOrNsfw(authorLower, tweet.author.displayName)
            emptyList()
        } else {
            val tokens = LocalForYouRanker.extractSafeTopicTokens(tweet, suppressed).take(5)
            reinforceSafeTopics(tokens, positiveDelta = 0.0, negativeDelta = NOT_INTERESTED_TOPIC_PENALTY, now = now)
            tokens.map { it.tokenLower }
        }

        FeedbackUndoToken(
            isNotInterestedPost = true,
            tweet = tweet,
            previousSeen = prevSeen,
            affectedTopics = affectedTokens,
        )
    }

    suspend fun markShowLessFromAuthor(tweet: Tweet): FeedbackUndoToken = withContext(Dispatchers.IO) {
        val now = nowProvider()
        val authorLower = tweet.author.handle.trim().removePrefix("@").lowercase()
        val prevAffinity = db.affinityDao().getByHandle(authorLower)
        if (authorLower.isNotBlank()) {
            val base = prevAffinity ?: AuthorAffinityEntity(
                handleLower = authorLower,
                displayName = tweet.author.displayName,
            )
            db.affinityDao().upsert(
                base.copy(
                    displayName = tweet.author.displayName.ifBlank { base.displayName },
                    isPinnedBoost = false,
                    isSoftDemoted = true,
                    notInterestedCount = base.notInterestedCount + 1,
                    lastInteractedEpochMs = now,
                )
            )
        }
        FeedbackUndoToken(
            isNotInterestedPost = false,
            tweet = tweet,
            previousAffinity = prevAffinity,
        )
    }

    suspend fun undoFeedback(token: FeedbackUndoToken) = withContext(Dispatchers.IO) {
        if (token.isNotInterestedPost) {
            if (token.previousSeen != null) {
                db.seenTweetDao().upsert(token.previousSeen)
            } else {
                db.seenTweetDao().deleteById(token.tweet.id)
            }
            token.affectedTopics.forEach { tok ->
                val cur = db.topicInterestDao().getByToken(tok) ?: return@forEach
                val restoredNeg = (cur.negativeScore - NOT_INTERESTED_TOPIC_PENALTY).coerceAtLeast(0.0)
                if (cur.manualState == TopicManualState.AUTO && cur.positiveScore <= 0.01 && restoredNeg <= 0.01) {
                    db.topicInterestDao().deleteByToken(tok)
                } else {
                    db.topicInterestDao().upsert(cur.copy(negativeScore = restoredNeg))
                }
            }
        } else {
            val authorLower = token.tweet.author.handle.trim().removePrefix("@").lowercase()
            if (authorLower.isBlank()) return@withContext
            if (token.previousAffinity != null) {
                db.affinityDao().upsert(token.previousAffinity)
            } else {
                db.affinityDao().deleteByHandle(authorLower)
            }
        }
    }

    suspend fun addManualTopic(rawInput: String): Result<TopicInterestEntity> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.length < 2) {
            return@withContext Result.failure(IllegalArgumentException("Enter at least 2 characters."))
        }
        if (LocalForYouRanker.isRiskyOrNsfwToken(trimmed) ||
            LocalForYouRanker.isRiskyOrNsfwHandleOrText("", trimmed, trimmed)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("Risky or NSFW topics are blocked from your interests.")
            )
        }
        val isHashtag = trimmed.startsWith("#")
        val normalized = if (isHashtag) {
            "#" + trimmed.removePrefix("#").trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
        } else {
            trimmed.lowercase().replace(Regex("\\s+"), " ")
        }
        if (normalized.removePrefix("#").length < 2) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid topic or #hashtag."))
        }
        val now = nowProvider()
        val existing = db.topicInterestDao().getByToken(normalized)
        val entity = TopicInterestEntity(
            tokenLower = normalized,
            displayLabel = if (isHashtag) "#${trimmed.removePrefix("#").trim()}" else trimmed,
            kind = if (isHashtag) TopicKind.HASHTAG else TopicKind.KEYWORD,
            positiveScore = maxOf(existing?.positiveScore ?: 0.0, 5.0),
            negativeScore = 0.0,
            manualState = TopicManualState.BOOSTED,
            lastUpdatedEpochMs = now,
        )
        db.topicInterestDao().upsert(entity)
        Result.success(entity)
    }

    suspend fun setTopicManualState(tokenLower: String, state: String) = withContext(Dispatchers.IO) {
        val existing = db.topicInterestDao().getByToken(tokenLower) ?: return@withContext
        val now = nowProvider()
        val updated = when (state) {
            TopicManualState.BOOSTED -> existing.copy(
                manualState = TopicManualState.BOOSTED,
                positiveScore = maxOf(existing.positiveScore, 4.0),
                negativeScore = 0.0,
                lastUpdatedEpochMs = now,
            )
            TopicManualState.DEMOTED -> existing.copy(
                manualState = TopicManualState.DEMOTED,
                negativeScore = maxOf(existing.negativeScore, 4.0),
                lastUpdatedEpochMs = now,
            )
            TopicManualState.BLOCKED -> existing.copy(
                manualState = TopicManualState.BLOCKED,
                lastUpdatedEpochMs = now,
            )
            else -> existing.copy(
                manualState = TopicManualState.AUTO,
                lastUpdatedEpochMs = now,
            )
        }
        db.topicInterestDao().upsert(updated)
    }

    suspend fun deleteTopic(tokenLower: String) = withContext(Dispatchers.IO) {
        db.topicInterestDao().deleteByToken(tokenLower)
    }

    suspend fun setAuthorSoftDemoted(handleLower: String, demoted: Boolean) = withContext(Dispatchers.IO) {
        val clean = handleLower.trim().removePrefix("@").lowercase()
        val existing = db.affinityDao().getByHandle(clean) ?: return@withContext
        if (existing.isRiskyOrNsfw) return@withContext
        db.affinityDao().upsert(
            existing.copy(
                isSoftDemoted = demoted,
                isPinnedBoost = if (demoted) false else existing.isPinnedBoost,
                lastInteractedEpochMs = nowProvider(),
            )
        )
    }

    suspend fun resetAuthorAffinity(handleLower: String) = withContext(Dispatchers.IO) {
        val clean = handleLower.trim().removePrefix("@").lowercase()
        val existing = db.affinityDao().getByHandle(clean) ?: return@withContext
        if (existing.isRiskyOrNsfw) return@withContext
        db.affinityDao().deleteByHandle(clean)
    }

    suspend fun clearSeenHistory() = withContext(Dispatchers.IO) {
        db.seenTweetDao().clearSeenHistory()
    }

    /**
     * Resets learned safe affinities, topic interests, and seen history while keeping
     * `isRiskyOrNsfw = 1` creator suppression flags intact for ongoing safety.
     */
    suspend fun resetAllSafeSignals() = withContext(Dispatchers.IO) {
        db.affinityDao().clearSafeAll()
        db.topicInterestDao().clearAll()
        db.seenTweetDao().clearAll()
    }

    private suspend fun reinforceSafeTopics(
        tokens: List<ExtractedTopicToken>,
        positiveDelta: Double,
        negativeDelta: Double,
        now: Long,
    ) {
        if (tokens.isEmpty()) return
        for (tok in tokens) {
            if (LocalForYouRanker.isRiskyOrNsfwToken(tok.tokenLower)) continue
            val existing = db.topicInterestDao().getByToken(tok.tokenLower)
            if (existing != null) {
                db.topicInterestDao().upsert(
                    existing.copy(
                        positiveScore = (existing.positiveScore + positiveDelta * tok.weightMultiplier)
                            .coerceAtMost(MAX_TOPIC_SCORE),
                        negativeScore = (existing.negativeScore + negativeDelta * tok.weightMultiplier)
                            .coerceAtMost(MAX_TOPIC_SCORE),
                        lastUpdatedEpochMs = now,
                    )
                )
            } else {
                db.topicInterestDao().upsert(
                    TopicInterestEntity(
                        tokenLower = tok.tokenLower,
                        displayLabel = tok.displayLabel,
                        kind = tok.kind,
                        positiveScore = (positiveDelta * tok.weightMultiplier).coerceAtMost(MAX_TOPIC_SCORE),
                        negativeScore = (negativeDelta * tok.weightMultiplier).coerceAtMost(MAX_TOPIC_SCORE),
                        manualState = TopicManualState.AUTO,
                        lastUpdatedEpochMs = now,
                    )
                )
            }
        }
    }

    private suspend fun pruneSeenIfNeeded(now: Long) {
        if (now - lastPruneEpochMs < PRUNE_INTERVAL_MS) return
        lastPruneEpochMs = now
        val cutoff = now - SEEN_RETENTION_MS
        db.seenTweetDao().pruneOlderThan(cutoff)
        db.seenTweetDao().pruneBeyondCount(MAX_SEEN_ROWS)
    }

    private fun zeroOutAsRisky(entity: AuthorAffinityEntity, now: Long): AuthorAffinityEntity =
        entity.copy(
            likesGiven = 0,
            repostsGiven = 0,
            repliesGiven = 0,
            bookmarksGiven = 0,
            profileOpens = 0,
            isPinnedBoost = false,
            threadOpens = 0,
            totalDwellSeconds = 0L,
            skipsCount = 0,
            notInterestedCount = 0,
            isSoftDemoted = false,
            isRiskyOrNsfw = true,
            lastInteractedEpochMs = now,
        )

    companion object {
        const val SEEN_THRESHOLD_MS = 900L
        const val MEANINGFUL_DWELL_MS = 3_000L
        const val MAX_SINGLE_DWELL_MS = 30_000L
        private const val NOT_INTERESTED_TOPIC_PENALTY = 4.0
        private const val MAX_TOPIC_SCORE = 60.0
        private const val SEEN_RETENTION_MS = 14L * 24L * 60L * 60L * 1000L
        private const val MAX_SEEN_ROWS = 3_000
        private const val PRUNE_INTERVAL_MS = 5L * 60L * 1000L
    }
}
