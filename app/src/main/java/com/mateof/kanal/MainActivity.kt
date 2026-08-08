package com.mateof.kanal

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mateof.kanal.core.InactivityWatcher
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.player.PipController
import com.mateof.kanal.player.PlayerHandover
import com.mateof.kanal.ui.AppShell
import com.mateof.kanal.ui.KanalNavHost
import com.mateof.kanal.ui.theme.KanalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sleepTimer: SleepTimer

    @Inject lateinit var inactivity: InactivityWatcher

    @Inject lateinit var prefs: AppPreferences

    @Inject lateinit var handover: PlayerHandover

    @Inject lateinit var pip: PipController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nothing here is worth letting the panel sleep over a long film.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            prefs.settings
                .map { it.stillWatching }
                .distinctUntilChanged()
                .collect(inactivity::setEnabled)
        }

        // The sleep timer running out and an unanswered "still watching?" mean
        // the same thing: stop pulling a stream nobody is watching. The task is
        // removed as well as finished so a launcher cannot bring it straight
        // back from recents.
        lifecycleScope.launch {
            merge(sleepTimer.expired, inactivity.expired).collect { finishAndRemoveTask() }
        }

        lifecycleScope.launch { pip.requests.collect { enterPipIfPossible() } }

        // From Android 12 the system can shrink the window itself as the user
        // leaves, which animates properly. Below that it has to be asked for in
        // onUserLeaveHint, which jumps.
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@launch
            pip.videoSize.collect { size ->
                runCatching {
                    setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAutoEnterEnabled(size != null)
                            .apply { aspectOf(size)?.let(::setAspectRatio) }
                            .build()
                    )
                }
            }
        }

        setContent {
            KanalTheme {
                AppShell {
                    KanalNavHost()
                }
            }
        }
    }

    /** @return false when there is nothing playing worth shrinking. */
    private fun enterPipIfPossible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isInPictureInPictureMode) return false
        val aspect = aspectOf(pip.videoSize.value) ?: return false
        return runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(aspect).build()
            )
        }.getOrDefault(false)
    }

    /**
     * The system rejects anything narrower than roughly 1:2.39 or taller than
     * 2.39:1, and throws rather than clamping, so the shape is bounded here.
     */
    private fun aspectOf(size: Pair<Int, Int>?): Rational? {
        val (width, height) = size ?: return null
        if (width <= 0 || height <= 0) return null
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(width, height)
        }
    }

    /** Swiping home while watching leaves the picture running in a corner. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 and later do this themselves through setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        enterPipIfPossible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.setActive(isInPictureInPictureMode)
    }

    // The watcher only makes sense while Kanal is on screen: left running it
    // would keep asking an empty room whether it is still watching, long after
    // it closed the app itself.
    override fun onStart() {
        super.onStart()
        inactivity.start()
    }

    override fun onStop() {
        super.onStop()
        pip.setActive(false)
        inactivity.stop()
        // A player parked mid-handover would otherwise keep pulling the stream
        // with nothing on screen to hand it to.
        handover.discard()
    }

    /** Every touch and every key press on the remote passes through here. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        inactivity.onInteraction()
    }
}
