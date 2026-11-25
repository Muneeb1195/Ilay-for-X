package com.example.data.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import coil3.SingletonImageLoader
import com.example.IlayApplication
import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.data.remote.IlayImageCache
import com.example.data.repository.TimelineRefreshSource
import com.example.domain.model.FeedType
import com.example.domain.model.ProfileTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi-only background [CoroutineWorker] that pre-fetches the user's home timelines
 * (`For You` & `Following`), active user profile, profile posts, and top timeline media
 * thumbnails into Room + Coil disk cache upon app startup for instant screen loads.
 */
class StartupPrefetchWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Double-check unmetered Wi-Fi at execution time so metered hotspots / cellular are never used
        if (!StartupPrefetchScheduler.isOnUnmeteredWifi(applicationContext)) {
            return@withContext Result.success(
                workDataOf(KEY_SKIPPED_REASON to "not_on_unmetered_wifi")
            )
        }

        val app = applicationContext as? IlayApplication
            ?: return@withContext Result.failure()
        val container = app.container
        val repository = container.repository

        try {
            // Yield to cold-start foreground UI load (up to 5s) so background prefetch never
            // contends with interactive Home feed requests on connections or rate-limit buckets.
            repository.awaitForegroundReadyForPrefetch(maxWaitMs = FOREGROUND_YIELD_MAX_MS)

            // Ensure endpoint query hashes and transaction ID key are ready before parallel fetches
            runCatching { container.endpointRegistry.ensureInitialized() }
            container.txIdProvider.warmUp()

            val activeAccount = container.database.accountDao().getActiveAccount()
            val forYouAlreadyFresh = repository.wasForYouRefreshedRecently(withinMs = 30_000L)

            val outcome = coroutineScope {
                // 1. Pre-fetch primary Home "For You" timeline only if foreground UI hasn't just loaded it
                val forYouDeferred = if (!forYouAlreadyFresh) {
                    async {
                        repository.refreshTimeline(
                            feedType = FeedType.FOR_YOU,
                            cursor = null,
                            source = TimelineRefreshSource.BACKGROUND_WORKER,
                        )
                    }
                } else {
                    null
                }

                // 2. Pre-fetch "Following" timeline when signed in so tab switching is instant
                val followingDeferred = if (activeAccount != null) {
                    async {
                        repository.refreshTimeline(
                            feedType = FeedType.FOLLOWING,
                            cursor = null,
                            source = TimelineRefreshSource.BACKGROUND_WORKER,
                        )
                    }
                } else {
                    null
                }

                // 3. Pre-fetch active profile (and initial Posts tab) or public preview creator profiles
                val profileDeferred = async {
                    if (activeAccount != null) {
                        val profileRes = repository.refreshActiveAccountProfile()
                        val resolvedUser = profileRes.getOrNull()
                        if (resolvedUser != null && resolvedUser.id.isNotBlank() && resolvedUser.id.all(Char::isDigit)) {
                            runCatching {
                                repository.loadUserTweets(
                                    user = resolvedUser,
                                    operationName = "UserTweets",
                                    cacheTab = ProfileTab.POSTS,
                                )
                            }
                        }
                        profileRes
                    } else {
                        val handles = repository.preferencesFlow.first().publicPreviewHandles.take(2)
                        val firstResolved = handles.firstNotNullOfOrNull { handle ->
                            repository.loadUserProfile(handle).getOrNull()
                        }
                        if (firstResolved != null) kotlin.Result.success(firstResolved)
                        else kotlin.Result.failure(IllegalStateException("Public profile prefetch skipped"))
                    }
                }

                val forYouResult = forYouDeferred?.await()
                val followingResult = followingDeferred?.await()
                val profileResult = profileDeferred.await()

                // 4. Warm Coil's disk & memory caches for top timeline media and profile visuals
                // strictly using bandwidth-saving lower-resolution preview variants.
                val imageQualityMode = runCatching {
                    repository.preferencesFlow.first().imageQualityMode
                }.getOrDefault(AdaptiveImageQualitySelector.currentMode)
                AdaptiveImageQualitySelector.currentMode = imageQualityMode

                val forYouTweets = forYouResult?.getOrNull()?.tweets
                    ?: if (forYouAlreadyFresh) repository.getCachedTimeline(FeedType.FOR_YOU) else emptyList()
                if (forYouTweets.isNotEmpty()) {
                    runCatching {
                        IlayImageCache.prefetchTimelineMedia(
                            context = applicationContext,
                            tweets = forYouTweets,
                            startIndex = 0,
                            windowSize = PREFETCH_MEDIA_WINDOW,
                            mode = imageQualityMode,
                        )
                    }
                }

                profileResult.getOrNull()?.let { user ->
                    runCatching {
                        val imageLoader = SingletonImageLoader.get(applicationContext)
                        if (user.avatarUrl.isNotBlank()) {
                            imageLoader.enqueue(
                                IlayImageCache.avatarRequest(applicationContext, user.avatarUrl, mode = imageQualityMode)
                            )
                        }
                        if (user.bannerUrl.isNotBlank()) {
                            imageLoader.enqueue(
                                IlayImageCache.bannerRequest(applicationContext, user.bannerUrl, mode = imageQualityMode)
                            )
                        }
                    }
                }

                PrefetchSummary(
                    forYouCount = forYouTweets.size,
                    followingCount = followingResult?.getOrNull()?.tweets?.size ?: 0,
                    profileHandle = profileResult.getOrNull()?.handle.orEmpty(),
                    anySucceeded = forYouAlreadyFresh || forYouResult?.isSuccess == true ||
                        profileResult.isSuccess || followingResult?.isSuccess == true,
                )
            }

            if (!outcome.anySucceeded && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                return@withContext Result.retry()
            }

            Result.success(
                workDataOf(
                    KEY_FOR_YOU_COUNT to outcome.forYouCount,
                    KEY_FOLLOWING_COUNT to outcome.followingCount,
                    KEY_PROFILE_HANDLE to outcome.profileHandle,
                    KEY_COMPLETED_AT_MS to System.currentTimeMillis(),
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private data class PrefetchSummary(
        val forYouCount: Int,
        val followingCount: Int,
        val profileHandle: String,
        val anySucceeded: Boolean,
    )

    companion object {
        private const val FOREGROUND_YIELD_MAX_MS = 5_000L
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val PREFETCH_MEDIA_WINDOW = 8

        const val KEY_FOR_YOU_COUNT = "prefetched_for_you_count"
        const val KEY_FOLLOWING_COUNT = "prefetched_following_count"
        const val KEY_PROFILE_HANDLE = "prefetched_profile_handle"
        const val KEY_COMPLETED_AT_MS = "completed_at_ms"
        const val KEY_SKIPPED_REASON = "skipped_reason"
    }
}

/**
 * Schedules Wi-Fi-constrained startup and periodic background pre-fetching via WorkManager.
 */
object StartupPrefetchScheduler {
    const val UNIQUE_STARTUP_WORK_NAME = "ilay_startup_wifi_prefetch"
    const val UNIQUE_PERIODIC_WORK_NAME = "ilay_periodic_wifi_prefetch"
    private const val TAG_PREFETCH = "ilay_wifi_prefetch"

    /**
     * Enqueues a Wi-Fi-only (`NetworkType.UNMETERED`) startup prefetch job and ensures a
     * battery-conscious periodic Wi-Fi cache warm-up is registered for future cold starts.
     */
    fun scheduleOnAppStartup(context: Context) {
        val appContext = context.applicationContext
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return

        val startupConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val startupRequest = OneTimeWorkRequestBuilder<StartupPrefetchWorker>()
            .setConstraints(startupConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PREFETCH)
            .build()

        // ExistingWorkPolicy.KEEP avoids duplicate overlapping startup jobs
        workManager.enqueueUniqueWork(
            UNIQUE_STARTUP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            startupRequest,
        )

        val periodicConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<StartupPrefetchWorker>(
            repeatInterval = 3,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
        )
            .setConstraints(periodicConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG_PREFETCH)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest,
        )
    }

    /**
     * Returns true only when the active network is an unmetered Wi-Fi connection with
     * validated internet capability.
     */
    fun isOnUnmeteredWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
