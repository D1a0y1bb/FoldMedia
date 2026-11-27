package com.foldmedia.app

import android.app.Application
import com.foldmedia.app.engine.YtDlpEngine
import com.foldmedia.app.media.GalleryMediaSaver
import com.foldmedia.app.parser.MediaExtractorRepository
import com.foldmedia.app.parser.WebViewBridgeExtractor
import com.foldmedia.app.storage.CookieVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FoldMediaApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val cookieVault: CookieVault by lazy { CookieVault(this) }
    val ytDlpEngine: YtDlpEngine by lazy { YtDlpEngine(this) }
    val webViewBridge: WebViewBridgeExtractor by lazy { WebViewBridgeExtractor(this) }
    val mediaSaver: GalleryMediaSaver by lazy { GalleryMediaSaver(this) }

    val extractorRepository: MediaExtractorRepository by lazy {
        MediaExtractorRepository(
            appContext = this,
            httpClient = okHttpClient,
            cookieVault = cookieVault,
            webViewBridge = webViewBridge,
            ytDlpEngine = ytDlpEngine
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Pre-warm on-device Python / yt-dlp / ffmpeg / aria2c runtime off the main thread
        appScope.launch {
            ytDlpEngine.ensureInitialized()
        }
    }
}
