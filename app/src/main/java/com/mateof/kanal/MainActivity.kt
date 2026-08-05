package com.mateof.kanal

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mateof.kanal.core.InactivityWatcher
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.data.prefs.AppPreferences
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

        setContent {
            KanalTheme {
                AppShell {
                    KanalNavHost()
                }
            }
        }
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
