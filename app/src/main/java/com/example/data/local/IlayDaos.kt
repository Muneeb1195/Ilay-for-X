package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isActive DESC, addedAtEpochMs DESC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun observeActiveAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY addedAtEpochMs DESC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: AccountEntity)

    @Query("UPDATE accounts SET isActive = 0")
    suspend fun clearActiveFlags()

    @Query("UPDATE accounts SET isActive = 1 WHERE userId = :userId")
    suspend fun setActiveFlag(userId: String)

    @Transaction
    suspend fun switchActiveAccount(userId: String) {
        clearActiveFlags()
        setActiveFlag(userId)
    }

    @Query("DELETE FROM accounts WHERE userId = :userId")
    suspend fun deleteAccount(userId: String)
}

@Dao
interface TweetCacheDao {
    @Query("SELECT * FROM cached_tweets WHERE feedBucket = :bucket ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun getFeedOnce(bucket: String, limit: Int = 120): List<CachedTweetEntity>

    @Query("SELECT * FROM cached_tweets WHERE tweetId = :tweetId LIMIT 1")
    suspend fun findByTweetId(tweetId: String): CachedTweetEntity?

    @Query("SELECT * FROM cached_tweets ORDER BY createdAtEpochMs DESC LIMIT 200")
    suspend fun getAllCachedTweets(): List<CachedTweetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTweets(items: List<CachedTweetEntity>)

    @Query("DELETE FROM cached_tweets WHERE feedBucket = :bucket")
    suspend fun clearBucket(bucket: String)

    @Transaction
    suspend fun replaceBucket(bucket: String, items: List<CachedTweetEntity>) {
        clearBucket(bucket)
        upsertTweets(items)
    }

    @Query("UPDATE cached_tweets SET tweetJson = :tweetJson WHERE tweetId = :tweetId")
    suspend fun updateTweetJsonEverywhere(tweetId: String, tweetJson: String)

    @Query("DELETE FROM cached_tweets WHERE tweetId = :tweetId")
    suspend fun deleteByTweetId(tweetId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY savedAtEpochMs DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE textSearchable LIKE '%' || :query || '%'
           OR authorHandle LIKE '%' || :query || '%'
           OR authorName LIKE '%' || :query || '%'
           OR folderTag LIKE '%' || :query || '%'
        ORDER BY savedAtEpochMs DESC
        """
    )
    fun searchBookmarks(query: String): Flow<List<BookmarkEntity>>

    @Query("SELECT tweetId FROM bookmarks")
    suspend fun getBookmarkedIdsOnce(): List<String>

    @Query("SELECT * FROM bookmarks ORDER BY savedAtEpochMs DESC")
    suspend fun getAllOnce(): List<BookmarkEntity>

    @Query("SELECT textSearchable FROM bookmarks ORDER BY savedAtEpochMs DESC LIMIT 60")
    suspend fun getRecentSearchableTexts(): List<String>

    @Query("SELECT * FROM bookmarks WHERE tweetId = :tweetId LIMIT 1")
    suspend fun findById(tweetId: String): BookmarkEntity?

    @Query("SELECT MIN(savedAtEpochMs) FROM bookmarks")
    suspend fun getOldestSavedAtEpochMs(): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE tweetId = :tweetId)")
    suspend fun isBookmarked(tweetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE isSyncedWithX = 1")
    suspend fun clearSyncedBookmarks()

    @Transaction
    suspend fun replaceSyncedBookmarks(bookmarks: List<BookmarkEntity>) {
        clearSyncedBookmarks()
        upsertAll(bookmarks)
    }

    @Query("DELETE FROM bookmarks WHERE tweetId = :tweetId")
    suspend fun deleteById(tweetId: String)
}

@Dao
interface AffinityDao {
    @Query("SELECT * FROM author_affinities WHERE isRiskyOrNsfw = 0 ORDER BY isPinnedBoost DESC, isSoftDemoted ASC, lastInteractedEpochMs DESC")
    fun observeAll(): Flow<List<AuthorAffinityEntity>>

    @Query("SELECT COUNT(*) FROM author_affinities WHERE isRiskyOrNsfw = 1")
    fun observeRiskySuppressedCount(): Flow<Int>

    @Query("SELECT * FROM author_affinities")
    suspend fun getAllOnce(): List<AuthorAffinityEntity>

    @Query("SELECT handleLower FROM author_affinities WHERE isRiskyOrNsfw = 1")
    suspend fun getRiskySuppressedHandlesOnce(): List<String>

    @Query("SELECT * FROM author_affinities WHERE handleLower = :handleLower LIMIT 1")
    suspend fun getByHandle(handleLower: String): AuthorAffinityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AuthorAffinityEntity)

    @Query("DELETE FROM author_affinities WHERE handleLower = :handleLower")
    suspend fun deleteByHandle(handleLower: String)

    @Query("DELETE FROM author_affinities WHERE isRiskyOrNsfw = 0")
    suspend fun clearSafeAll()

    @Query("DELETE FROM author_affinities")
    suspend fun clearAll()
}

@Dao
interface SeenTweetDao {
    @Query("SELECT COUNT(*) FROM seen_tweets WHERE viewCount > 0")
    fun observeSeenCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM seen_tweets WHERE isDismissed = 1")
    fun observeDismissedCount(): Flow<Int>

    @Query("SELECT * FROM seen_tweets ORDER BY lastSeenEpochMs DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int = 3000): List<SeenTweetEntity>

    @Query("SELECT * FROM seen_tweets WHERE tweetId = :tweetId LIMIT 1")
    suspend fun getById(tweetId: String): SeenTweetEntity?

    @Query("SELECT * FROM seen_tweets WHERE tweetId IN (:tweetIds)")
    suspend fun getByIds(tweetIds: List<String>): List<SeenTweetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SeenTweetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SeenTweetEntity>)

    @Query("DELETE FROM seen_tweets WHERE tweetId = :tweetId")
    suspend fun deleteById(tweetId: String)

    @Query("DELETE FROM seen_tweets WHERE isDismissed = 0 AND lastSeenEpochMs < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long)

    @Query(
        """
        DELETE FROM seen_tweets
        WHERE isDismissed = 0 AND tweetId NOT IN (
            SELECT tweetId FROM seen_tweets ORDER BY lastSeenEpochMs DESC LIMIT :maxKeep
        )
        """
    )
    suspend fun pruneBeyondCount(maxKeep: Int = 3000)

    @Query("DELETE FROM seen_tweets WHERE isDismissed = 0")
    suspend fun clearSeenHistory()

    @Query("DELETE FROM seen_tweets")
    suspend fun clearAll()
}

@Dao
interface TopicInterestDao {
    @Query(
        """
        SELECT * FROM topic_interests
        ORDER BY
            CASE manualState
                WHEN 'BOOSTED' THEN 0
                WHEN 'AUTO' THEN 1
                WHEN 'DEMOTED' THEN 2
                ELSE 3
            END ASC,
            (positiveScore - negativeScore) DESC,
            lastUpdatedEpochMs DESC
        """
    )
    fun observeAll(): Flow<List<TopicInterestEntity>>

    @Query("SELECT * FROM topic_interests")
    suspend fun getAllOnce(): List<TopicInterestEntity>

    @Query(
        """
        SELECT * FROM topic_interests
        WHERE manualState IN ('BOOSTED', 'AUTO')
          AND (positiveScore - negativeScore) > 0.5
        ORDER BY
            CASE manualState WHEN 'BOOSTED' THEN 0 ELSE 1 END ASC,
            (positiveScore - negativeScore) DESC,
            lastUpdatedEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun getTopPositiveOnce(limit: Int = 12): List<TopicInterestEntity>

    @Query("SELECT * FROM topic_interests WHERE tokenLower = :tokenLower LIMIT 1")
    suspend fun getByToken(tokenLower: String): TopicInterestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TopicInterestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TopicInterestEntity>)

    @Query("DELETE FROM topic_interests WHERE tokenLower = :tokenLower")
    suspend fun deleteByToken(tokenLower: String)

    @Query("DELETE FROM topic_interests")
    suspend fun clearAll()
}

@Dao
interface MutedRuleDao {
    @Query("SELECT * FROM muted_rules ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<MutedRuleEntity>>

    @Query("SELECT * FROM muted_rules")
    suspend fun getAllOnce(): List<MutedRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: MutedRuleEntity)

    @Query("DELETE FROM muted_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM muted_rules WHERE isHandle = 1 AND LOWER(value) = :handleLower")
    suspend fun deleteHandleRule(handleLower: String)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM draft_tweets ORDER BY updatedAtEpochMs DESC")
    fun observeDrafts(): Flow<List<DraftTweetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftTweetEntity): Long

    @Query("DELETE FROM draft_tweets WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface EndpointHashDao {
    @Query("SELECT * FROM endpoint_hashes ORDER BY operationName ASC")
    fun observeAll(): Flow<List<EndpointHashEntity>>

    @Query("SELECT * FROM endpoint_hashes")
    suspend fun getAllOnce(): List<EndpointHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<EndpointHashEntity>)
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAtEpochMs DESC LIMIT 12")
    fun observeRecent(): Flow<List<RecentSearchEntity>>

    @Query("SELECT query FROM recent_searches ORDER BY searchedAtEpochMs DESC LIMIT 20")
    suspend fun getRecentQueriesOnce(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}

@Dao
interface ProfileCacheDao {
    @Query("SELECT * FROM cached_profiles WHERE handleLower = :handleLower LIMIT 1")
    suspend fun getByHandle(handleLower: String): CachedProfileEntity?

    @Query("SELECT * FROM cached_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getByUserId(userId: String): CachedProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CachedProfileEntity)
}

@Dao
interface UserModerationDao {
    @Query("SELECT * FROM user_moderations WHERE isMuted = 1 OR isBlocked = 1 ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<UserModerationEntity>>

    @Query("SELECT * FROM user_moderations WHERE handleLower = :handleLower LIMIT 1")
    fun observeByHandle(handleLower: String): Flow<UserModerationEntity?>

    @Query("SELECT * FROM user_moderations WHERE isMuted = 1 OR isBlocked = 1")
    suspend fun getAllOnce(): List<UserModerationEntity>

    @Query("SELECT * FROM user_moderations WHERE handleLower = :handleLower LIMIT 1")
    suspend fun getByHandle(handleLower: String): UserModerationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserModerationEntity)

    @Query("DELETE FROM user_moderations WHERE handleLower = :handleLower")
    suspend fun deleteByHandle(handleLower: String)
}
