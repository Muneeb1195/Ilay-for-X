package com.example

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.example.data.local.IlayDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.remote.IlayImageCache
import com.example.data.remote.LinkPreviewResolver
import com.example.data.remote.PublicFallbackClient
import com.example.data.remote.XClientTransactionIdProvider
import com.example.data.remote.XEndpointRegistry
import com.example.data.remote.XGraphqlClient
import com.example.data.repository.BookmarkRepository
import com.example.data.repository.XRepository
import com.example.data.repository.XSearchRepository
import com.example.data.worker.StartupPrefetchScheduler
import com.example.domain.model.TrendsResult
import com.example.domain.model.Tweet
import com.example.domain.model.XUser
import com.example.domain.ranking.LocalForYouRanker
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Raised per-host concurrency (default 5 -> 16) so parallel timelines, profiles, and pbs.twimg.com media don't queue
    private val httpDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(httpDispatcher)
        .cache(Cache(File(application.cacheDir, "ilay_http_cache"), 24L * 1024L * 1024L))
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // Single shared Moshi instance using compile-time KSP-generated adapters (zero kotlin-reflect overhead)
    val moshi: Moshi = Moshi.Builder().build()
    val tweetAdapter: JsonAdapter<Tweet> = moshi.adapter(Tweet::class.java)
    val userAdapter: JsonAdapter<XUser> = moshi.adapter(XUser::class.java)
    val trendsResultAdapter: JsonAdapter<TrendsResult> = moshi.adapter(TrendsResult::class.java)

    val database: IlayDatabase = IlayDatabase.getInstance(application)
    val preferencesRepository: PreferencesRepository = PreferencesRepository(application)
    val endpointRegistry: XEndpointRegistry = XEndpointRegistry(okHttpClient, database.endpointHashDao())
    val txIdProvider: XClientTransactionIdProvider = XClientTransactionIdProvider(application, okHttpClient)
    val graphqlClient: XGraphqlClient = XGraphqlClient(okHttpClient, endpointRegistry, txIdProvider)
    val publicClient: PublicFallbackClient = PublicFallbackClient(okHttpClient)
    val linkPreviewResolver: LinkPreviewResolver = LinkPreviewResolver(okHttpClient)
    val localRanker: LocalForYouRanker = LocalForYouRanker()

    val bookmarkRepository: BookmarkRepository = BookmarkRepository(database.bookmarkDao(), tweetAdapter)

    val searchRepository: XSearchRepository = XSearchRepository(
        db = database,
        preferencesRepository = preferencesRepository,
        graphqlClient = graphqlClient,
        publicClient = publicClient,
        endpointRegistry = endpointRegistry,
        tweetAdapter = tweetAdapter,
        trendsResultAdapter = trendsResultAdapter,
    )

    val repository: XRepository = XRepository(
        appContext = application,
        db = database,
        preferencesRepository = preferencesRepository,
        graphqlClient = graphqlClient,
        publicClient = publicClient,
        endpointRegistry = endpointRegistry,
        ranker = localRanker,
        tweetAdapter = tweetAdapter,
        userAdapter = userAdapter,
        searchRepository = searchRepository,
        bookmarkRepository = bookmarkRepository,
    )

    init {
        // Pre-warm endpoint registry, transaction-id verification key, and cached user profile concurrently
        appScope.launch {
            runCatching { endpointRegistry.ensureInitialized() }
        }
        appScope.launch {
            runCatching { txIdProvider.warmUp() }
        }
        appScope.launch {
            runCatching { repository.warmUpActiveProfileCache() }
        }
    }
}

class IlayApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        StartupPrefetchScheduler.scheduleOnAppStartup(this)
    }

    /**
     * Builds the app-wide Coil 3 ImageLoader backed by a dedicated 64-250 MB disk cache,
     * 25% RAM strong+weak memory cache, canonical URL keying, and a cacheless OkHttpClient
     * sharing AppContainer's HTTP/2 connection pool to prevent double disk writes.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        IlayImageCache.buildImageLoader(
            context = context,
            appCacheDir = cacheDir,
            sharedOkHttpClient = container.okHttpClient,
        )
}
