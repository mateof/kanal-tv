package com.mateof.kanal

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.log.Klog
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.link.LinkServer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    @Inject lateinit var link: LinkServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Klog.bind(logger)
        logger.installCrashHandler()
        logger.i("App", "Arrancando Kanal ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        scope.launch {
            prefs.settings.collectLatest { http.verboseLogging = it.verboseHttpLog }
        }

        // Other devices with Kanal can only find this one while it is on
        // screen: "a device with Kanal open" is what the person sending sees.
        val foreground = MutableStateFlow(false)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { foreground.value = false }
        })
        scope.launch {
            combine(foreground, prefs.settings.map { it.linkVisible }) { shown, visible -> shown && visible }
                .distinctUntilChanged()
                .collect { run -> if (run) link.start() else link.stop() }
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
