package com.mateof.kanal

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.log.Klog
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.prefs.AppPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class KanalApp : Application(), ImageLoaderFactory {

    @Inject lateinit var logger: FileLogger
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var http: HttpProvider
    @Inject lateinit var imageLoader: Provider<ImageLoader>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Klog.bind(logger)
        logger.installCrashHandler()
        logger.i("App", "Arrancando Kanal ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        scope.launch {
            prefs.settings.collectLatest { http.verboseLogging = it.verboseHttpLog }
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
