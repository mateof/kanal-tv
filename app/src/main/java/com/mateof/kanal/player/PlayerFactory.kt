package com.mateof.kanal.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.prefs.BufferProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpProvider,
    private val logger: FileLogger
) {
    /**
     * IPTV streams are mostly raw MPEG-TS with AC3/EAC3 audio and the odd
     * HEVC channel, which is exactly what cheap sticks decode badly. NextLib's
     * FFmpeg renderers are registered *after* the hardware ones
     * (EXTENSION_RENDERER_MODE_ON) so hardware is still preferred and software
     * only kicks in for what the device cannot handle.
     */
    fun create(userAgent: String, profile: BufferProfile): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(http.client)
            .setUserAgent(userAgent)

        val renderers = NextRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.startMs,
                profile.startMs * 2
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("spa", "es", "esp")
                    .setPreferredTextLanguages("spa", "es")
                    // Sticks report absurd max sizes; let the renderer decide.
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
            )
        }

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory), extractors)
            )
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { logger.d("Player", "Reproductor creado (buffer ${profile.name}, UA '$userAgent')") }
    }

    /**
     * Hints the container so ExoPlayer does not have to sniff it. Xtream live
     * urls end in `.ts` or `.m3u8`; VOD keeps the provider's extension.
     */
    fun mediaItem(url: String, title: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val path = url.substringBefore('?').lowercase()
        when {
            path.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".ts") -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return builder
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
            .build()
    }
}
