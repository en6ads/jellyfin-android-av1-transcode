package org.jellyfin.mobile.app

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.work.WorkManager
import coil3.ImageLoader
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.downloads.DownloadNotificationManager
import org.jellyfin.mobile.downloads.DownloadQueue
import org.jellyfin.mobile.downloads.DownloadsViewModel
import org.jellyfin.mobile.downloads.FileDownloader
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.dolbyvision.DolbyVisionProfile7CompatExtractorsFactory
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.mobile.player.qualityoptions.QualityOptionsProvider
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.PermissionRequestHelper
import org.jellyfin.mobile.utils.extractId
import org.jellyfin.mobile.utils.isLowRamDevice
import org.jellyfin.mobile.webapp.RemoteVolumeProvider
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.fragment.dsl.fragment
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit

const val PLAYER_EVENT_CHANNEL = "PlayerEventChannel"
private const val TS_SEARCH_PACKETS = 1800

/**
 * Connect timeout for media HTTP requests. media3 defaults to 8s, which a transcoding server on
 * a high-latency link can exceed before it has produced anything to send.
 */
private const val DATA_SOURCE_CONNECT_TIMEOUT_SECONDS = 30L

/**
 * Read timeout for media HTTP requests, for the same reason: an HLS segment request can sit
 * with no bytes flowing while the segment is still being encoded.
 */
private const val DATA_SOURCE_READ_TIMEOUT_SECONDS = 60L

/**
 * How many times a single chunk load may fail before the error is escalated to a fatal player
 * error. media3's default is 3.
 *
 * Escalation is expensive here in a way it is not on an ordinary connection: a fatal error tears
 * the session down and restarts it with a fresh PlaySessionId, which makes the server kill its
 * ffmpeg process and start a new one from scratch. A transient socket failure therefore throws
 * away a perfectly good transcode. Retrying the chunk a few more times costs a few seconds;
 * escalating costs the whole transcode.
 */
private const val MINIMUM_LOADABLE_RETRY_COUNT = 6

val applicationModule = module {
    single { AppPreferences(androidApplication()) }
    single { OkHttpClient() }
    single { ImageLoader(androidApplication()) }
    single { PermissionRequestHelper() }
    single { RemoteVolumeProvider(get()) }
    single(named(PLAYER_EVENT_CHANNEL)) { Channel<PlayerEvent>() }
    factory { WorkManager.getInstance(get()) }
    single { AssHandler(AssRenderType.OVERLAY_OPEN_GL) }

    // Controllers
    single { ApiClientController(get(), get(), get(), get(), get()) }

    // Event handlers and channels
    single { ActivityEventHandler(get()) }
    single { WebappFunctionChannel() }

    // Bridge interfaces
    single { NativePlayer(get(), get(), get(named(PLAYER_EVENT_CHANNEL))) }
    single { MediaSegments(get()) }

    // ViewModels
    viewModel { MainViewModel(get(), get()) }
    viewModel { DownloadsViewModel() }

    // Fragments
    fragment { WebViewFragment() }
    fragment { PlayerFragment() }

    // Connection helper
    single { ConnectionHelper(get(), get()) }

    // Media player helpers
    single { MediaSourceResolver(get()) }
    single { DeviceProfileBuilder(get()) }
    single { QualityOptionsProvider() }
    single { MediaSegmentRepository() }

    // ExoPlayer factories
    single<DatabaseProvider> {
        val dbProvider = StandaloneDatabaseProvider(get<Context>())
        dbProvider
    }
    single<Cache> {
        val downloadPath = File(get<Context>().filesDir, Constants.DOWNLOAD_PATH)
        if (!downloadPath.exists()) {
            downloadPath.mkdirs()
        }
        val cache = SimpleCache(downloadPath, NoOpCacheEvictor(), get())
        cache
    }

    single<DataSource.Factory> {
        val context: Context = get()
        val apiClient: ApiClient = get()

        // OkHttp rather than media3's default DefaultHttpDataSource, which is backed by
        // HttpURLConnection. HttpURLConnection pools connections but does not reliably detect a
        // pooled socket the peer has already dropped: the next request over it fails
        // immediately, surfacing as ERROR_CODE_IO_NETWORK_CONNECTION_FAILED rather than a
        // timeout. That is the exact shape of the failure seen on this setup - a request
        // succeeds, and the very next one dies about a second later, far too fast to be a
        // timeout. A tunnelled link whose underlying path can change mid-session (a tailnet
        // moving between a relay and a direct route) kills idle sockets that way routinely.
        //
        // OkHttp's retryOnConnectionFailure handles precisely this case: a request that fails on
        // a stale pooled connection is transparently retried on a fresh one. It is on by
        // default; it is set explicitly here because it is the reason for the swap.
        val okHttpClient = get<OkHttpClient>().newBuilder()
            .connectTimeout(DATA_SOURCE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // media3 defaults to 8s for both, which is too short for a transcoded HLS stream on
            // a high-latency link. A segment request can block with no bytes flowing at all
            // while the server produces that segment - ffmpeg start-up, or simply not having
            // encoded that far yet - and only then does the transfer begin. Measured against a
            // real server over a cellular tailnet: responses of 8.3s for a request, and 1.2-2.6s
            // just for a few-kilobyte fMP4 initialisation segment. Measured again from the
            // server to itself, with no network in the path at all: 2.1s and 3.7s for that same
            // init segment, so several seconds of it is the encoder starting, not the link.
            //
            // Past the default the load fails as ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, which
            // surfaces to the user as a bare "source error". It presents as the client fetching
            // the init segment over and over and never reaching a media segment, and it does not
            // improve when the bitrate is lowered, because the delay is the server's
            // time-to-first-byte rather than the segment size.
            //
            // Direct play is unaffected, which is what makes this look mysterious: a progressive
            // download streams continuously, so no single read ever approaches the timeout.
            .readTimeout(DATA_SOURCE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // Timeouts live on the OkHttp client above, not here.
        val baseDataSourceFactory = OkHttpDataSource.Factory(okHttpClient).apply {
            setUserAgent(Util.getUserAgent(context, Constants.APP_INFO_NAME))
        }

        val dataSourceFactory = DefaultDataSource.Factory(context, baseDataSourceFactory)

        // Add authorization header. This is needed as we don't pass the
        // access token in the URL for Android Auto.
        ResolvingDataSource.Factory(dataSourceFactory) { dataSpec: DataSpec ->
            // Only send authorization header if URI matches the jellyfin server
            val baseUrlAuthority = apiClient.baseUrl?.toUri()?.authority

            if (dataSpec.uri.authority == baseUrlAuthority) {
                val authorizationHeaderString = AuthorizationHeaderBuilder.buildHeader(
                    clientName = apiClient.clientInfo.name,
                    clientVersion = apiClient.clientInfo.version,
                    deviceId = apiClient.deviceInfo.id,
                    deviceName = apiClient.deviceInfo.name,
                    accessToken = apiClient.accessToken,
                )

                dataSpec.withRequestHeaders(hashMapOf("Authorization" to authorizationHeaderString))
            } else {
                dataSpec
            }
        }
    }

    single<CacheDataSource.Factory> {
        // Create a read-only cache data source factory using the download cache.
        CacheDataSource.Factory()
            .setCache(get())
            .setUpstreamDataSourceFactory(get<DataSource.Factory>())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec ->
                spec.key ?: spec.uri.extractId()
            }
    }

    factory<MediaSource.Factory> {
        val context: Context = get()
        val extractorsFactory = DefaultExtractorsFactory().apply {
            // https://github.com/google/ExoPlayer/issues/8571
            setTsExtractorTimestampSearchBytes(
                when {
                    !context.isLowRamDevice -> TS_SEARCH_PACKETS * TsExtractor.TS_PACKET_SIZE // 3x default
                    else -> TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                },
            )
        }

        val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT)

        val appPreferences: AppPreferences = get()
        if (appPreferences.exoPlayerDirectPlayAss) {
            val assHandler: AssHandler = get()
            val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
            val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
            DefaultMediaSourceFactory(
                get<CacheDataSource.Factory>(),
                DolbyVisionProfile7CompatExtractorsFactory(assExtractorsFactory),
            )
                .setSubtitleParserFactory(assSubtitleParserFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        } else {
            DefaultMediaSourceFactory(
                get<CacheDataSource.Factory>(),
                DolbyVisionProfile7CompatExtractorsFactory(extractorsFactory),
            )
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        }
    }

    single(createdAtStart = true) { StorageManager(get(), get()) }
    single { DownloadManager(get(), get(), get(), get(), get()) }
    single { DownloadNotificationManager(get()) }
    single { DownloadQueue(get(), get(), get(), get(), get(), get()) }
    single { FileDownloader(get()) }
}
