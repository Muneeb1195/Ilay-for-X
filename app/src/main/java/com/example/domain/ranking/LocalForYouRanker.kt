package com.example.domain.ranking

import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.MutedRuleEntity
import com.example.data.local.SeenTweetEntity
import com.example.data.local.TopicInterestEntity
import com.example.data.local.TopicKind
import com.example.data.local.TopicManualState
import com.example.data.local.UserModerationEntity
import com.example.domain.model.Tweet
import com.example.domain.model.XUser
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class ExtractedTopicToken(
    val tokenLower: String,
    val displayLabel: String,
    val kind: String,
    val weightMultiplier: Double = 1.0,
)

/**
 * Ilay's privacy-first On-Device "For You" Ranking Engine v2.
 *
 * Scoring & safety architecture (100% local, zero cloud telemetry):
 * 1. **Two-Layer Risky & NSFW Firewall**: Excludes sensitive, adult, graphic, and risky posts,
 *    domains, tokens, and flagged creators from both For You and Following timelines AND blocks
 *    all topic/creator learning from risky/NSFW interactions, profile visits, or dwell time.
 * 2. **Time-Decayed Creator Affinity**: Weighs explicit interactions, thread/profile opens,
 *    capped reading dwell time, and pinned boosts minus skip fatigue and soft-demote ("Show less").
 * 3. **Persistent On-Device Topic Graph**: Scores hashtags, meaningful keywords, and link domains
 *    from `topic_interests` (positive minus negative weights, boosted/demoted/blocked overrides).
 * 4. **Engagement Velocity, Substance & Anti-Spam**: Balances organic traction, recency velocity,
 *    save quality, long-form/media/poll value, and anti-bait dampening.
 * 5. **Tiered Seen-Post Demotion**: Strictly places unseen posts ahead of already-viewed posts
 *    when enabled, while keeping seen posts reachable below the fold.
 * 6. **Multi-Signal Diversity & Adjacency Spread**: Prevents single-author or single-thread domination.
 */
class LocalForYouRanker {

    fun filterMuted(
        tweets: List<Tweet>,
        mutedRules: List<MutedRuleEntity>,
        moderations: List<UserModerationEntity> = emptyList(),
        excludeRiskyAndNsfw: Boolean = false,
        suppressedRiskyHandles: Set<String> = emptySet(),
        dismissedTweetIds: Set<String> = emptySet(),
    ): List<Tweet> {
        val mutedHandles = buildSet {
            mutedRules.filter { it.isHandle }.forEach { rule ->
                val clean = rule.value.trim().removePrefix("@").lowercase()
                if (clean.isNotBlank()) add(clean)
            }
            moderations.filter { it.isMuted || it.isBlocked }.forEach { mod ->
                val clean = mod.handleLower.trim().removePrefix("@").lowercase()
                if (clean.isNotBlank()) add(clean)
            }
        }
        val blockedHandles = moderations
            .filter { it.isBlocked }
            .map { it.handleLower.trim().removePrefix("@").lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val excludedUserIds = moderations
            .filter { (it.isMuted || it.isBlocked) && it.userId.isNotBlank() }
            .map { it.userId.trim() }
            .toSet()
        val mutedKeywords = mutedRules.filterNot { it.isHandle }
            .map { it.value.trim().lowercase() }
            .filter { it.isNotBlank() }

        return tweets.filter { tweet ->
            if (tweet.id in dismissedTweetIds) return@filter false
            if (tweet.isPromoted) return@filter false
            if (tweet.author.isMuted || tweet.author.isBlocked) return@filter false

            val authorLower = tweet.author.handle.trim().removePrefix("@").lowercase()
            if (authorLower in mutedHandles) return@filter false
            if (tweet.author.id.isNotBlank() && tweet.author.id in excludedUserIds) return@filter false

            val reposterLower = tweet.repostedBy?.trim()?.removePrefix("@")?.lowercase()
            if (!reposterLower.isNullOrBlank() && reposterLower in mutedHandles) return@filter false

            val replyToLower = tweet.replyToHandle?.trim()?.removePrefix("@")?.lowercase()
            if (!replyToLower.isNullOrBlank() && replyToLower in blockedHandles) return@filter false

            tweet.quotedTweet?.let { qt ->
                if (qt.author.isMuted || qt.author.isBlocked) return@filter false
                val qtHandleLower = qt.author.handle.trim().removePrefix("@").lowercase()
                if (qtHandleLower in mutedHandles) return@filter false
                if (qt.author.id.isNotBlank() && qt.author.id in excludedUserIds) return@filter false
            }

            if (excludeRiskyAndNsfw && isRiskyOrNsfwTweet(tweet, suppressedRiskyHandles)) {
                return@filter false
            }

            val bodyLower = tweet.text.lowercase()
            val quoteLower = tweet.quotedTweet?.text?.lowercase().orEmpty()
            mutedKeywords.none { kw -> bodyLower.contains(kw) || quoteLower.contains(kw) }
        }
    }

    fun rankForYou(
        tweets: List<Tweet>,
        affinities: List<AuthorAffinityEntity>,
        mutedRules: List<MutedRuleEntity>,
        prefs: AppPreferences,
        nowEpochMs: Long = System.currentTimeMillis(),
        bookmarkedTexts: List<String> = emptyList(),
        recentSearchQueries: List<String> = emptyList(),
        moderations: List<UserModerationEntity> = emptyList(),
        seenByTweetId: Map<String, SeenTweetEntity> = emptyMap(),
        topicInterests: List<TopicInterestEntity> = emptyList(),
        sourceTags: Map<String, String> = emptyMap(),
    ): List<Tweet> {
        val suppressedRiskyHandles = affinities
            .filter { it.isRiskyOrNsfw }
            .mapTo(HashSet()) { it.handleLower.trim().removePrefix("@").lowercase() }

        val dismissedTweetIds = seenByTweetId.values
            .filter { it.isDismissed }
            .mapTo(HashSet()) { it.tweetId }

        val blockedTopics = topicInterests
            .filter { it.manualState == TopicManualState.BLOCKED }
            .mapTo(HashSet()) { it.tokenLower }

        val filtered = filterMuted(
            tweets = tweets,
            mutedRules = mutedRules,
            moderations = moderations,
            excludeRiskyAndNsfw = prefs.excludeRiskyAndNsfw,
            suppressedRiskyHandles = suppressedRiskyHandles,
            dismissedTweetIds = dismissedTweetIds,
        ).filterNot { tweet ->
            if (blockedTopics.isEmpty()) false
            else extractSafeTopicTokens(tweet).any { tok ->
                tok.tokenLower in blockedTopics || tok.tokenLower.removePrefix("#") in blockedTopics
            }
        }
        if (filtered.isEmpty()) return emptyList()

        val excludedHandles = buildSet {
            mutedRules.filter { it.isHandle }.forEach { rule ->
                val clean = rule.value.trim().removePrefix("@").lowercase()
                if (clean.isNotBlank()) add(clean)
            }
            moderations.filter { it.isMuted || it.isBlocked }.forEach { mod ->
                val clean = mod.handleLower.trim().removePrefix("@").lowercase()
                if (clean.isNotBlank()) add(clean)
            }
            addAll(suppressedRiskyHandles)
        }

        val affinityByHandle = affinities
            .filterNot {
                it.isRiskyOrNsfw ||
                    it.handleLower in excludedHandles ||
                    isRiskyOrNsfwHandleOrText(it.handleLower, it.displayName)
            }
            .associateBy { it.handleLower }

        val topicWeightMap = buildPersistentTopicWeights(
            topicInterests = topicInterests,
            bookmarkedTexts = bookmarkedTexts,
            recentSearchQueries = recentSearchQueries,
            nowEpochMs = nowEpochMs,
        )

        val affinityWeight = prefs.affinityWeight.coerceIn(0.05f, 3.0f).toDouble()
        val recencyWeight = prefs.recencyWeight.coerceIn(0.05f, 3.0f).toDouble()

        val scored = filtered.map { tweet ->
            val handleLower = tweet.author.handle.lowercase()
            val affinity = affinityByHandle[handleLower]
            val seenRecord = seenByTweetId[tweet.id]
            val isSeenPost = prefs.demoteSeenPosts && seenRecord != null && seenRecord.viewCount > 0
            val sourceTag = sourceTags[tweet.id].orEmpty()

            // 1. Time-decayed Author Affinity (actions + opens + dwell - skips - soft demote)
            val affinityScore = computeAuthorAffinityScore(
                tweet = tweet,
                affinity = affinity,
                affinityWeight = affinityWeight,
                nowEpochMs = nowEpochMs,
            )

            // 2. Persistent Safe On-Device Topic Interest Graph
            val interestScore = computeTopicInterestMatchScore(tweet, topicWeightMap) *
                (0.65 + 0.35 * affinityWeight)

            // 3. Smooth Recency Curve
            val ageHours = ((nowEpochMs - tweet.createdAtEpochMs).coerceAtLeast(0L)) / 3_600_000.0
            val recencyScore = (
                32.0 * exp(-ageHours / 6.5) +
                    18.0 * exp(-ageHours / 28.0)
                ) * recencyWeight

            // 4. Engagement Velocity & Save-Quality Density
            val likes = tweet.metrics.likes.coerceAtLeast(0L)
            val reposts = tweet.metrics.reposts.coerceAtLeast(0L)
            val replies = tweet.metrics.replies.coerceAtLeast(0L)
            val quotes = tweet.metrics.quotes.coerceAtLeast(0L)
            val bookmarks = tweet.metrics.bookmarks.coerceAtLeast(0L)
            val views = tweet.metrics.views.coerceAtLeast(0L)

            val weightedEngagement = likes + (reposts * 2L) + (replies * 1.5).toLong() +
                (quotes * 2.5).toLong() + (bookmarks * 3.5).toLong()
            val organicScore = ln(1.0 + weightedEngagement) * 2.6

            val velocityPerHour = weightedEngagement.toDouble() / (ageHours + 1.5).pow(0.75)
            val velocityScore = ln(1.0 + velocityPerHour) * 3.1

            val saveRatio = if (likes >= 15L) {
                (bookmarks.toDouble() + quotes.toDouble() * 0.6) / likes.toDouble()
            } else {
                0.0
            }
            val viewEngagementRate = if (views >= 500L) {
                (likes + bookmarks * 2L + reposts).toDouble() / views.toDouble()
            } else {
                0.0
            }
            val qualityDensityBonus = (saveRatio.coerceAtMost(0.65) * 14.0) +
                (viewEngagementRate.coerceAtMost(0.08) * 90.0)

            val ratioPenalty = if (replies >= 40L && replies > (likes.coerceAtLeast(1L) * 2.5)) {
                -6.5
            } else {
                0.0
            }

            // 5. Substantive Content & Format Signals
            val hasActiveUnvotedPoll = tweet.poll != null &&
                !tweet.poll.isClosedAt(nowEpochMs) &&
                !tweet.poll.hasVoted
            val isLongFormNote = tweet.text.length >= 260 && tweet.text.count { it == ' ' } >= 35
            val contentBonus = buildContentBonus(tweet, hasActiveUnvotedPoll, isLongFormNote)

            // 6. Reply & Thread Context + Multi-Source Blend Bonus
            val replyContextModifier = computeReplyContextModifier(tweet, affinityByHandle)
            val blendSourceBonus = when {
                sourceTag == SOURCE_FOLLOWING && ageHours <= 8.0 -> 5.5
                sourceTag.startsWith(SOURCE_TOPIC_PREFIX) -> 6.0
                else -> 0.0
            }

            // 7. Anti-Spam & Engagement-Bait Dampening
            val spamPenalty = computeSpamAndBaitPenalty(tweet)

            // 8. Repost & Already-Interacted Adjustments
            val repostFactor = if (tweet.repostedBy != null) 0.87 else 1.0
            val alreadyInteractedFactor = if (tweet.viewerState.isLiked || tweet.viewerState.isReposted) 0.86 else 1.0

            val rawTotal = (
                affinityScore +
                    interestScore +
                    recencyScore +
                    organicScore +
                    velocityScore +
                    qualityDensityBonus +
                    contentBonus +
                    replyContextModifier +
                    blendSourceBonus +
                    ratioPenalty +
                    spamPenalty
                ) * repostFactor * alreadyInteractedFactor

            val baseScore = rawTotal.coerceAtLeast(0.5)

            // 9. Tiered Seen-Post Score Scaling (seen posts strictly demote below unseen tier)
            val finalScore = if (isSeenPost) {
                val viewCount = seenRecord.viewCount.coerceAtLeast(1)
                val viewDecay = 1.0 / (1.0 + 0.45 * viewCount)
                (baseScore * 0.22 * viewDecay).coerceAtLeast(0.05)
            } else {
                baseScore
            }

            val reason = selectRankReason(
                isSeenPost = isSeenPost,
                sourceTag = sourceTag,
                affinity = affinity,
                affinityScore = affinityScore,
                interestScore = interestScore,
                velocityPerHour = velocityPerHour,
                saveRatio = saveRatio,
                hasActiveUnvotedPoll = hasActiveUnvotedPoll,
                hasArticleOrLongForm = tweet.article != null || isLongFormNote,
                ageHours = ageHours,
                weightedEngagement = weightedEngagement,
            )

            ScoredCandidate(
                tweet = tweet.copy(
                    localRankScore = finalScore,
                    localRankReason = reason,
                ),
                isSeen = isSeenPost,
            )
        }

        // Partition into Tier 1 (Unseen) and Tier 2 (Seen), running diversity & adjacency spread
        // independently so unseen posts ALWAYS remain above seen posts when demoteSeenPosts is on.
        val unseenCandidates = scored.filterNot { it.isSeen }.map { it.tweet }
        val seenCandidates = scored.filter { it.isSeen }.map { it.tweet }

        val rankedUnseen = diversifyAndSpread(unseenCandidates)
        val rankedSeen = diversifyAndSpread(seenCandidates)

        // Ensure numeric monotonicity between Tier 1 and Tier 2 for downstream consumers/tests
        val minUnseenScore = rankedUnseen.minOfOrNull { it.localRankScore } ?: 0.0
        val normalizedSeen = if (rankedUnseen.isNotEmpty() && rankedSeen.isNotEmpty()) {
            val maxSeenScore = rankedSeen.maxOf { it.localRankScore }.coerceAtLeast(0.01)
            val targetCeiling = (minUnseenScore * 0.85).coerceAtLeast(0.05)
            if (maxSeenScore >= minUnseenScore) {
                val scale = targetCeiling / maxSeenScore
                rankedSeen.map { it.copy(localRankScore = (it.localRankScore * scale).coerceAtLeast(0.01)) }
            } else {
                rankedSeen
            }
        } else {
            rankedSeen
        }

        return rankedUnseen + normalizedSeen
    }

    private data class ScoredCandidate(
        val tweet: Tweet,
        val isSeen: Boolean,
    )

    private fun diversifyAndSpread(items: List<Tweet>): List<Tweet> {
        if (items.size <= 1) return items
        val authorCounts = HashMap<String, Int>()
        val conversationCounts = HashMap<String, Int>()
        val reposterCounts = HashMap<String, Int>()

        val diversified = items
            .sortedByDescending { it.localRankScore }
            .map { tweet ->
                val authorKey = tweet.author.handle.lowercase()
                val seenAuthor = authorCounts.getOrDefault(authorKey, 0)
                authorCounts[authorKey] = seenAuthor + 1

                val convKey = tweet.conversationId.ifBlank { tweet.id }
                val seenConv = conversationCounts.getOrDefault(convKey, 0)
                conversationCounts[convKey] = seenConv + 1

                val reposterPenalty = tweet.repostedBy?.lowercase()?.let { rep ->
                    val seenRep = reposterCounts.getOrDefault(rep, 0)
                    reposterCounts[rep] = seenRep + 1
                    seenRep * 5.0
                } ?: 0.0

                val authorPenalty = if (seenAuthor > 0) 10.0 * seenAuthor.toDouble().pow(1.35) else 0.0
                val convPenalty = if (seenConv > 0) 14.0 * seenConv else 0.0

                tweet.copy(
                    localRankScore = (tweet.localRankScore - authorPenalty - convPenalty - reposterPenalty)
                        .coerceAtLeast(0.01)
                )
            }
            .sortedByDescending { it.localRankScore }

        return spreadAdjacentAuthors(diversified)
    }

    private fun computeAuthorAffinityScore(
        tweet: Tweet,
        affinity: AuthorAffinityEntity?,
        affinityWeight: Double,
        nowEpochMs: Long,
    ): Double {
        val followBonus = if (tweet.author.isFollowing) 4.0 else 0.0
        if (affinity == null) return followBonus * affinityWeight

        val positiveRaw = (affinity.likesGiven * 1.5) +
            (affinity.repostsGiven * 2.6) +
            (affinity.repliesGiven * 3.2) +
            (affinity.bookmarksGiven * 3.8) +
            (affinity.profileOpens * 1.1) +
            (affinity.threadOpens * 1.6) +
            (affinity.totalDwellSeconds.coerceAtMost(600L) * 0.08)

        val daysSinceInteraction = ((nowEpochMs - affinity.lastInteractedEpochMs).coerceAtLeast(0L)) / 86_400_000.0
        val decayFactor = 0.35 + 0.65 * exp(-daysSinceInteraction / 21.0)
        val decayedPositive = positiveRaw * decayFactor
        val pinBonus = if (affinity.isPinnedBoost) 24.0 else 0.0

        val skipPenalty = (affinity.skipsCount.coerceAtMost(20) * 0.65)
        val softDemotePenalty = (if (affinity.isSoftDemoted) 28.0 else 0.0) +
            (affinity.notInterestedCount.coerceAtMost(5) * 9.0)

        val baseAffinity = (ln(1.0 + decayedPositive + pinBonus) * 18.0 + followBonus) * affinityWeight
        return baseAffinity - skipPenalty - softDemotePenalty
    }

    private fun buildPersistentTopicWeights(
        topicInterests: List<TopicInterestEntity>,
        bookmarkedTexts: List<String>,
        recentSearchQueries: List<String>,
        nowEpochMs: Long,
    ): Map<String, Double> {
        val weights = HashMap<String, Double>()

        topicInterests.forEach { entity ->
            if (isRiskyOrNsfwToken(entity.tokenLower) || isRiskyOrNsfwHandleOrText("", entity.displayLabel)) {
                return@forEach
            }
            val daysOld = ((nowEpochMs - entity.lastUpdatedEpochMs).coerceAtLeast(0L)) / 86_400_000.0
            val decay = 0.40 + 0.60 * exp(-daysOld / 28.0)
            val decayedNet = (entity.positiveScore - entity.negativeScore) * decay

            val effective = when (entity.manualState) {
                TopicManualState.BOOSTED -> (ln(1.0 + decayedNet.coerceAtLeast(0.0)) * 2.2 + 3.2).coerceAtMost(6.5)
                TopicManualState.DEMOTED -> -5.0 - ln(1.0 + entity.negativeScore.coerceAtLeast(0.0))
                TopicManualState.BLOCKED -> -25.0
                else -> {
                    if (decayedNet >= 0.0) {
                        (ln(1.0 + decayedNet) * 1.55).coerceAtMost(4.2)
                    } else {
                        -(ln(1.0 + (-decayedNet)) * 1.85).coerceAtMost(5.5)
                    }
                }
            }
            if (effective != 0.0) {
                weights[entity.tokenLower] = effective
            }
        }

        // Fallback/supplemental lightweight signal from bookmarks & recent searches when topic graph is still sparse
        if (weights.size < 10 && (bookmarkedTexts.isNotEmpty() || recentSearchQueries.isNotEmpty())) {
            val fallback = buildFallbackInterestProfile(bookmarkedTexts, recentSearchQueries)
            fallback.forEach { (token, score) ->
                if (token !in weights) {
                    weights[token] = score
                }
            }
        }

        return weights
    }

    private fun buildFallbackInterestProfile(
        bookmarkedTexts: List<String>,
        recentSearchQueries: List<String>,
    ): Map<String, Double> {
        val rawWeights = HashMap<String, Double>()
        bookmarkedTexts.forEachIndexed { idx, txt ->
            if (isRiskyOrNsfwHandleOrText("", "", txt)) return@forEachIndexed
            val recencyBoost = 1.0 / (1.0 + idx * 0.04)
            extractSafeTokensFromText(txt).forEach { tok ->
                rawWeights[tok.tokenLower] = (rawWeights[tok.tokenLower] ?: 0.0) + (1.2 * tok.weightMultiplier * recencyBoost)
            }
        }
        recentSearchQueries.forEachIndexed { idx, q ->
            if (q.startsWith("quoted_tweet_id:", ignoreCase = true) || q.contains("://")) return@forEachIndexed
            if (isRiskyOrNsfwHandleOrText("", "", q)) return@forEachIndexed
            val recencyBoost = 1.0 / (1.0 + idx * 0.08)
            extractSafeTokensFromText(q).forEach { tok ->
                rawWeights[tok.tokenLower] = (rawWeights[tok.tokenLower] ?: 0.0) + (1.8 * tok.weightMultiplier * recencyBoost)
            }
        }
        return rawWeights.entries
            .filter { it.value >= 1.5 }
            .sortedByDescending { it.value }
            .take(80)
            .associate { (k, v) -> k to (ln(1.0 + v) * 1.4).coerceAtMost(3.5) }
    }

    private fun computeTopicInterestMatchScore(
        tweet: Tweet,
        topicWeights: Map<String, Double>,
    ): Double {
        if (topicWeights.isEmpty()) return 0.0
        val tokens = extractSafeTopicTokens(tweet)
        if (tokens.isEmpty()) return 0.0

        var matchScore = 0.0
        val matched = HashSet<String>()
        tokens.forEach { tok ->
            if (!matched.add(tok.tokenLower)) return@forEach
            val w = topicWeights[tok.tokenLower] ?: return@forEach
            val multiplier = when (tok.kind) {
                TopicKind.HASHTAG -> 2.1
                TopicKind.DOMAIN -> 1.5
                else -> 1.35
            }
            matchScore += w * multiplier
        }
        return matchScore.coerceIn(-24.0, 26.0)
    }

    private fun buildContentBonus(
        tweet: Tweet,
        hasActiveUnvotedPoll: Boolean,
        isLongFormNote: Boolean,
    ): Double {
        var bonus = 0.0
        if (tweet.article != null) bonus += 6.5
        if (isLongFormNote) bonus += 4.0
        if (tweet.media.isNotEmpty()) bonus += 4.5
        if (tweet.quotedTweet != null) bonus += 3.0
        if (tweet.linkPreview != null && tweet.linkPreview.title.isNotBlank()) bonus += 2.0
        if (hasActiveUnvotedPoll) {
            bonus += 5.0
        } else if (tweet.poll != null) {
            bonus += 1.5
        }
        return bonus.coerceAtMost(12.5)
    }

    private fun computeReplyContextModifier(
        tweet: Tweet,
        affinityByHandle: Map<String, AuthorAffinityEntity>,
    ): Double {
        val replyHandle = tweet.replyToHandle?.trim()?.removePrefix("@")?.lowercase()
            ?: return 0.0
        if (replyHandle == tweet.author.handle.lowercase()) return 2.5
        if (affinityByHandle.containsKey(replyHandle)) return 1.0
        return -5.5
    }

    private fun computeSpamAndBaitPenalty(tweet: Tweet): Double {
        var penalty = 0.0
        val text = tweet.text.trim()

        val hashtagCount = HASHTAG_TOKEN_REGEX.findAll(text).count()
        if (hashtagCount >= 5) {
            penalty -= ((hashtagCount - 4) * 3.0).coerceAtMost(15.0)
        }
        if (ENGAGEMENT_BAIT_REGEX.containsMatchIn(text)) {
            penalty -= 10.0
        }
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 24) {
            val upperRatio = letters.count { it.isUpperCase() }.toDouble() / letters.length.toDouble()
            if (upperRatio >= 0.78) {
                penalty -= 5.0
            }
        }
        if (text.length < 18 && tweet.media.isEmpty() && tweet.quotedTweet == null &&
            tweet.poll == null && tweet.article == null
        ) {
            penalty -= 3.5
        }
        return penalty
    }

    private fun selectRankReason(
        isSeenPost: Boolean,
        sourceTag: String,
        affinity: AuthorAffinityEntity?,
        affinityScore: Double,
        interestScore: Double,
        velocityPerHour: Double,
        saveRatio: Double,
        hasActiveUnvotedPoll: Boolean,
        hasArticleOrLongForm: Boolean,
        ageHours: Double,
        weightedEngagement: Long,
    ): String = when {
        isSeenPost -> "Seen earlier"
        affinity?.isPinnedBoost == true -> "Pinned creator"
        sourceTag.startsWith(SOURCE_TOPIC_PREFIX) -> "Top topic"
        interestScore >= 6.5 -> "Matches your interests"
        affinityScore >= 16.0 -> "Top affinity"
        sourceTag == SOURCE_FOLLOWING && ageHours <= 8.0 -> "From Following"
        hasActiveUnvotedPoll -> "Active poll"
        velocityPerHour >= 350.0 && ageHours <= 6.0 -> "Rising fast"
        saveRatio >= 0.18 -> "High-save pick"
        hasArticleOrLongForm -> "Long-form read"
        ageHours <= 1.5 -> "Just posted"
        weightedEngagement >= 3000L -> "Trending signal"
        else -> "Balanced pick"
    }

    private fun spreadAdjacentAuthors(sorted: List<Tweet>): List<Tweet> {
        if (sorted.size <= 2) return sorted
        val pool = sorted.toMutableList()
        val result = ArrayList<Tweet>(sorted.size)
        while (pool.isNotEmpty()) {
            val prevAuthor = result.lastOrNull()?.author?.handle?.lowercase()
            val nextIdx = if (prevAuthor == null) {
                0
            } else {
                val topScore = pool[0].localRankScore
                val candidate = (0 until minOf(pool.size, 5)).firstOrNull { idx ->
                    val sameAuthor = pool[idx].author.handle.lowercase() == prevAuthor
                    !sameAuthor && (topScore - pool[idx].localRankScore) <= 18.0
                }
                candidate ?: 0
            }
            result.add(pool.removeAt(nextIdx))
        }
        return result
    }

    companion object {
        const val SOURCE_FOR_YOU = "FOR_YOU"
        const val SOURCE_FOLLOWING = "FOLLOWING"
        const val SOURCE_TOPIC_PREFIX = "TOPIC:"

        private val HASHTAG_TOKEN_REGEX = Regex("#[A-Za-z0-9_]{2,28}")
        private val WORD_TOKEN_REGEX = Regex("""[a-z][a-z0-9_]{3,24}""")
        private val URL_STRIP_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val MENTION_STRIP_REGEX = Regex("""@[A-Za-z0-9_]+""")
        private val ENGAGEMENT_BAIT_REGEX = Regex(
            pattern = """(?i)(rt\s+to\s+win|retweet\s+to\s+win|follow\s*\+\s*r[et]+weet|drop\s+your\s+wallet|like\s+and\s+rt\s+for|comment\s+["']?\w+["']?\s+to\s+get|dm\s+me\s+["']?\w+["']?\s+for\s+free)"""
        )
        private val STOPWORDS = setOf(
            "this", "that", "with", "from", "have", "were", "been", "they", "them", "their",
            "what", "when", "where", "which", "while", "will", "would", "could", "should",
            "about", "after", "before", "into", "over", "under", "just", "like", "more",
            "some", "than", "then", "there", "these", "those", "very", "your", "ours",
            "here", "only", "also", "back", "even", "much", "make", "made", "still", "want",
            "know", "think", "really", "actually", "going", "people", "thing", "things",
            "good", "great", "best", "time", "today", "tomorrow", "yesterday", "https", "http",
            "dont", "cant", "wont", "doesnt", "didnt", "isnt", "arent", "wasnt", "werent",
            "being", "every", "first", "last", "never", "always", "other", "another", "because",
            "through", "between", "around", "without", "something", "anything", "nothing",
            "someone", "everyone", "anyone", "thread", "tweet", "post", "posts", "video", "photo",
        )

        private val RISKY_OR_NSFW_WORD_REGEX = Regex(
            pattern = """(?i)(?:^|[^a-z0-9])(nsfw|nsfl|18\+|21\+|mdni|dni18|onlyfans|fansly|manyvids|justforfans|loyalfans|fancentro|admireme|chaturbate|stripchat|myfreecams|camgirl|cammodel|camboy|pornhub|xvideos|xnxx|redtube|xhamster|brazzers|spankbang|eporner|porn|porno|pornstar|hentai|rule34|r34|ecchi|lewd|lewds|erotica|erotic|nudes|nudity|nude|xxx|fap|gooning|gooner|gore|beheading|adultcontent|afterdark|sexwork|sexworker|escort|fetish|bdsm|boobs|tits|pussy|dickpic|cock|blowjob|handjob|milf|threesome|orgasm|horny|slut|whore|kink|kinky|findom|paypig|sugardaddy|sugarbaby|spicycontent|spicylink|spicyvideo|explicitcontent|uncensoredcontent|leakednudes|sextape|stripper|lingerie\s+model|casino\s+bonus|free\s+spins|crypto\s+drainer|airdrop\s+claim)(?:$|[^a-z0-9])""",
        )

        private val RISKY_PROMO_PHRASE_REGEX = Regex(
            pattern = """(?i)(?:spicy\s+(?:link|page|site|pics?|videos?|content|vip)|vip\s+(?:page|link|content)\s+(?:in\s+bio|below)|(?:free|my)\s+o\.?f\.?\s+(?:link|page|in\s+bio)|top\s+\d+(?:\.\d+)?%\s+on\s+of|18\+\s+only|adults?\s+only|link\s+in\s+bio\s*(?:🔞|🔥|💦|🍑|😈)|🔞|💦💦)""",
        )

        private val RISKY_HANDLE_SUBSTRINGS = listOf(
            "nsfw",
            "nsfl",
            "onlyfans",
            "fansly",
            "manyvids",
            "porn",
            "hentai",
            "lewd",
            "rule34",
            "xxx",
            "18plus",
            "afterdark",
            "nudes",
            "erotica",
            "chaturbate",
            "camgirl",
            "cammodel",
            "fetish",
            "bdsm",
            "findom",
            "sexwork",
            "escort",
            "goon",
            "spicylink",
            "xvideos",
            "xhamster",
        )

        private val RISKY_DOMAINS = listOf(
            "onlyfans.com",
            "fansly.com",
            "manyvids.com",
            "justfor.fans",
            "loyalfans.com",
            "fancentro.com",
            "allmylinks.com/nsfw",
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "xhamster.com",
            "redtube.com",
            "spankbang.com",
            "chaturbate.com",
            "stripchat.com",
            "myfreecams.com",
            "cam4.com",
            "rule34.",
            "gelbooru.com",
            "e621.net",
            "nhentai.net",
            "hanime.tv",
            "redgifs.com",
            "erome.com",
        )

        private val GENERIC_SKIP_DOMAINS = setOf(
            "x.com", "twitter.com", "t.co", "pic.twitter.com", "fxtwitter.com",
            "vxtwitter.com", "fixupx.com", "youtu.be", "youtube.com",
        )

        fun isRiskyOrNsfwToken(token: String): Boolean {
            val clean = token.trim().lowercase()
            if (clean.isBlank()) return false
            val bare = clean.removePrefix("#").removePrefix("@")
            if (RISKY_HANDLE_SUBSTRINGS.any { bare.contains(it) }) return true
            if (RISKY_DOMAINS.any { clean.contains(it) }) return true
            return RISKY_OR_NSFW_WORD_REGEX.containsMatchIn(clean) ||
                RISKY_PROMO_PHRASE_REGEX.containsMatchIn(clean)
        }

        fun isRiskyOrNsfwHandleOrText(
            handle: String,
            displayName: String = "",
            extraText: String = "",
        ): Boolean {
            val cleanHandle = handle.trim().removePrefix("@").lowercase()
            if (cleanHandle.isNotBlank() && RISKY_HANDLE_SUBSTRINGS.any { cleanHandle.contains(it) }) {
                return true
            }
            if (displayName.isNotBlank() &&
                (RISKY_OR_NSFW_WORD_REGEX.containsMatchIn(displayName) ||
                    RISKY_PROMO_PHRASE_REGEX.containsMatchIn(displayName))
            ) {
                return true
            }
            if (extraText.isNotBlank() &&
                (RISKY_OR_NSFW_WORD_REGEX.containsMatchIn(extraText) ||
                    RISKY_PROMO_PHRASE_REGEX.containsMatchIn(extraText) ||
                    RISKY_DOMAINS.any { extraText.lowercase().contains(it) })
            ) {
                return true
            }
            return false
        }

        fun isRiskyOrNsfwUser(
            user: XUser,
            suppressedRiskyHandles: Set<String> = emptySet(),
        ): Boolean {
            val cleanHandle = user.handle.trim().removePrefix("@").lowercase()
            if (cleanHandle.isNotBlank() && cleanHandle in suppressedRiskyHandles) return true
            if (isRiskyOrNsfwHandleOrText(user.handle, user.displayName, user.bio)) return true
            val siteLower = user.websiteUrl.lowercase()
            return siteLower.isNotBlank() && RISKY_DOMAINS.any { siteLower.contains(it) }
        }

        fun isRiskyOrNsfwTweet(
            tweet: Tweet,
            suppressedRiskyHandles: Set<String> = emptySet(),
        ): Boolean {
            if (tweet.isSensitive) return true
            if (isRiskyOrNsfwUser(tweet.author, suppressedRiskyHandles)) return true

            val reposterLower = tweet.repostedBy?.trim()?.removePrefix("@")?.lowercase().orEmpty()
            if (reposterLower.isNotBlank() &&
                (reposterLower in suppressedRiskyHandles || isRiskyOrNsfwHandleOrText(reposterLower))
            ) {
                return true
            }

            val replyLower = tweet.replyToHandle?.trim()?.removePrefix("@")?.lowercase().orEmpty()
            if (replyLower.isNotBlank() &&
                (replyLower in suppressedRiskyHandles || isRiskyOrNsfwHandleOrText(replyLower))
            ) {
                return true
            }

            if (isRiskyOrNsfwHandleOrText("", "", tweet.text)) return true

            tweet.quotedTweet?.let { qt ->
                if (qt.isSensitive ||
                    isRiskyOrNsfwUser(qt.author, suppressedRiskyHandles) ||
                    isRiskyOrNsfwHandleOrText("", "", qt.text)
                ) {
                    return true
                }
            }
            tweet.linkPreview?.let { lp ->
                val urlLower = "${lp.url} ${lp.domain} ${lp.displayUrl}".lowercase()
                if (RISKY_DOMAINS.any { urlLower.contains(it) }) return true
                if (isRiskyOrNsfwHandleOrText("", lp.title, lp.description)) return true
            }
            tweet.article?.let { art ->
                if (isRiskyOrNsfwHandleOrText("", art.title, art.previewText)) return true
            }
            return false
        }

        fun extractSafeTokensFromText(text: String): List<ExtractedTopicToken> {
            if (text.isBlank()) return emptyList()
            if (isRiskyOrNsfwHandleOrText("", "", text)) return emptyList()
            val cleaned = URL_STRIP_REGEX.replace(MENTION_STRIP_REGEX.replace(text, " "), " ")
            val results = LinkedHashMap<String, ExtractedTopicToken>()

            HASHTAG_TOKEN_REGEX.findAll(cleaned).forEach { m ->
                val raw = m.value
                val lower = raw.lowercase()
                if (!isRiskyOrNsfwToken(lower)) {
                    results.putIfAbsent(
                        lower,
                        ExtractedTopicToken(
                            tokenLower = lower,
                            displayLabel = raw,
                            kind = TopicKind.HASHTAG,
                            weightMultiplier = 1.6,
                        ),
                    )
                }
            }

            WORD_TOKEN_REGEX.findAll(cleaned.lowercase()).forEach { m ->
                val token = m.value
                if (token.length >= 4 && token !in STOPWORDS && !isRiskyOrNsfwToken(token)) {
                    val hashKey = "#$token"
                    if (hashKey !in results) {
                        results.putIfAbsent(
                            token,
                            ExtractedTopicToken(
                                tokenLower = token,
                                displayLabel = token.replaceFirstChar { it.uppercase() },
                                kind = TopicKind.KEYWORD,
                                weightMultiplier = 1.0,
                            ),
                        )
                    }
                }
            }

            return results.values.take(10)
        }

        fun extractSafeTopicTokens(
            tweet: Tweet,
            suppressedRiskyHandles: Set<String> = emptySet(),
        ): List<ExtractedTopicToken> {
            if (isRiskyOrNsfwTweet(tweet, suppressedRiskyHandles)) return emptyList()
            val combined = buildString {
                append(tweet.text)
                tweet.quotedTweet?.let {
                    append(' ')
                    append(it.text)
                }
                tweet.linkPreview?.let {
                    append(' ')
                    append(it.title)
                }
                tweet.article?.let {
                    append(' ')
                    append(it.title)
                }
            }
            val results = LinkedHashMap<String, ExtractedTopicToken>()
            extractSafeTokensFromText(combined).forEach { results[it.tokenLower] = it }

            tweet.linkPreview?.domain?.trim()?.lowercase()?.removePrefix("www.")?.let { dom ->
                if (dom.contains('.') && dom !in GENERIC_SKIP_DOMAINS && !isRiskyOrNsfwToken(dom)) {
                    results.putIfAbsent(
                        dom,
                        ExtractedTopicToken(
                            tokenLower = dom,
                            displayLabel = dom,
                            kind = TopicKind.DOMAIN,
                            weightMultiplier = 1.15,
                        ),
                    )
                }
            }

            return results.values.take(12)
        }
    }
}
