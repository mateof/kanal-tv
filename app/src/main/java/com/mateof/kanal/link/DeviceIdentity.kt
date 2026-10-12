package com.mateof.kanal.link

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import com.mateof.kanal.BuildConfig
import com.mateof.kanal.cast.relay.NetworkInspector
import com.mateof.kanal.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** How this device introduces itself to other devices with Kanal. */
@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val network: NetworkInspector,
    private val inbox: LinkInbox
) {
    suspend fun sender(): Sender = Sender(prefs.linkDeviceId(), name())

    /** What the user called it in Ajustes, else what the system calls it. */
    suspend fun name(): String =
        prefs.settings.first().linkDeviceName.ifBlank { systemName() }

    suspend fun info(port: Int): PeerInfo = PeerInfo(
        id = prefs.linkDeviceId(),
        name = name(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        formFactor = formFactor(),
        android = Build.VERSION.RELEASE.orEmpty(),
        app = BuildConfig.VERSION_NAME,
        port = port,
        nowPlaying = inbox.nowPlaying.value?.title.orEmpty()
    )

    /** The addresses this device can be reached at in the house. */
    fun addresses(): List<String> =
        network.lanLinks().mapNotNull { it.address.hostAddress }.distinct()

    /**
     * The name the owner gave the device in the system settings, which is
     * what they will recognise. Android TV and recent phones keep it in
     * `device_name` (API 25+); older ones only have the Bluetooth name, and
     * from Android 12 that one needs a permission, so a failure just moves on.
     * Last of all the model, which at least tells a television from a phone.
     */
    fun systemName(): String {
        val resolver = context.contentResolver
        val candidates = listOf(
            { Settings.Global.getString(resolver, "device_name") },
            { Settings.Secure.getString(resolver, "bluetooth_name") }
        )
        for (read in candidates) {
            val name = runCatching(read).getOrNull()?.trim()
            if (!name.isNullOrBlank()) return name
        }
        return modelName()
    }

    /** "Xiaomi MiTV-AXSO0" rather than "Xiaomi Xiaomi MiTV-AXSO0". */
    fun modelName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    fun formFactor(): String {
        val uiMode = context.getSystemService(UiModeManager::class.java)
        val tv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return when {
            tv -> FORM_TV
            context.resources.configuration.smallestScreenWidthDp >= 600 -> FORM_TABLET
            else -> FORM_PHONE
        }
    }

    companion object {
        const val FORM_TV = "tv"
        const val FORM_TABLET = "tablet"
        const val FORM_PHONE = "phone"
    }
}
