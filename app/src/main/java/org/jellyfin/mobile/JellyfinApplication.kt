package org.jellyfin.mobile

import android.app.Application
import android.webkit.WebView
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.utils.FileLogTree
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.context.startKoin
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Setup logging
        Timber.plant(JellyTree())

        // Keep a copy on disk that can be shared from the settings screen. Playback failures
        // are often only reproducible away from home, where attaching to logcat is impractical.
        //
        // Read directly rather than through Koin: logging should be up before anything else is,
        // and AppPreferences is a thin wrapper over SharedPreferences with nothing to inject.
        //
        // Logs from before redaction existed can contain the access token, so they are removed
        // whether or not file logging is still switched on.
        FileLogTree.deleteLegacyLogs(this)
        if (AppPreferences(this).writeLogFile) {
            Timber.plant(FileLogTree(this))
        }

        if (BuildConfig.DEBUG) {
            // Enable WebView debugging
            if (isWebViewSupported()) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }

        startKoin {
            androidContext(this@JellyfinApplication)
            fragmentFactory()

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }
    }
}
