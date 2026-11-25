package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CachedTweetEntity::class,
        BookmarkEntity::class,
        AuthorAffinityEntity::class,
        SeenTweetEntity::class,
        TopicInterestEntity::class,
        MutedRuleEntity::class,
        DraftTweetEntity::class,
        EndpointHashEntity::class,
        RecentSearchEntity::class,
        CachedProfileEntity::class,
        UserModerationEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class IlayDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun tweetCacheDao(): TweetCacheDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun affinityDao(): AffinityDao
    abstract fun seenTweetDao(): SeenTweetDao
    abstract fun topicInterestDao(): TopicInterestDao
    abstract fun mutedRuleDao(): MutedRuleDao
    abstract fun draftDao(): DraftDao
    abstract fun endpointHashDao(): EndpointHashDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun profileCacheDao(): ProfileCacheDao
    abstract fun userModerationDao(): UserModerationDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_tweets_feedBucket_createdAtEpochMs` " +
                        "ON `cached_tweets` (`feedBucket`, `createdAtEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_tweets_tweetId` " +
                        "ON `cached_tweets` (`tweetId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_savedAtEpochMs` " +
                        "ON `bookmarks` (`savedAtEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_folderTag_savedAtEpochMs` " +
                        "ON `bookmarks` (`folderTag`, `savedAtEpochMs`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_profiles` (" +
                        "`handleLower` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`userJson` TEXT NOT NULL, " +
                        "`cachedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`handleLower`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_profiles_userId` " +
                        "ON `cached_profiles` (`userId`)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_moderations` (" +
                        "`handleLower` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`isMuted` INTEGER NOT NULL, " +
                        "`isBlocked` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`handleLower`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_moderations_userId` " +
                        "ON `user_moderations` (`userId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_moderations_isMuted` " +
                        "ON `user_moderations` (`isMuted`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_moderations_isBlocked` " +
                        "ON `user_moderations` (`isBlocked`)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `user_moderations` " +
                        "(`handleLower`, `userId`, `displayName`, `isMuted`, `isBlocked`, `updatedAtEpochMs`) " +
                        "SELECT LOWER(TRIM(REPLACE(`value`, '@', ''))), '', '', 1, 0, `createdAtEpochMs` " +
                        "FROM `muted_rules` WHERE `isHandle` = 1 AND TRIM(`value`) != ''"
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `author_affinities` ADD COLUMN `threadOpens` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `author_affinities` ADD COLUMN `totalDwellSeconds` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `author_affinities` ADD COLUMN `skipsCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `author_affinities` ADD COLUMN `notInterestedCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `author_affinities` ADD COLUMN `isSoftDemoted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `author_affinities` ADD COLUMN `isRiskyOrNsfw` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `seen_tweets` (" +
                        "`tweetId` TEXT NOT NULL, " +
                        "`authorHandleLower` TEXT NOT NULL, " +
                        "`firstSeenEpochMs` INTEGER NOT NULL, " +
                        "`lastSeenEpochMs` INTEGER NOT NULL, " +
                        "`viewCount` INTEGER NOT NULL, " +
                        "`totalDwellMs` INTEGER NOT NULL, " +
                        "`wasInteracted` INTEGER NOT NULL, " +
                        "`wasSkipped` INTEGER NOT NULL, " +
                        "`isDismissed` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`tweetId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_seen_tweets_lastSeenEpochMs` " +
                        "ON `seen_tweets` (`lastSeenEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_seen_tweets_isDismissed` " +
                        "ON `seen_tweets` (`isDismissed`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `topic_interests` (" +
                        "`tokenLower` TEXT NOT NULL, " +
                        "`displayLabel` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`positiveScore` REAL NOT NULL, " +
                        "`negativeScore` REAL NOT NULL, " +
                        "`manualState` TEXT NOT NULL, " +
                        "`lastUpdatedEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`tokenLower`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_topic_interests_manualState` " +
                        "ON `topic_interests` (`manualState`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_topic_interests_lastUpdatedEpochMs` " +
                        "ON `topic_interests` (`lastUpdatedEpochMs`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: IlayDatabase? = null

        fun getInstance(context: Context): IlayDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IlayDatabase::class.java,
                    "ilay_for_x.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
