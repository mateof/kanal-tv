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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
    fun create(
        userAgent: String,
        profile: BufferProfile,
        resilient: Boolean = false,
        subtitles: Boolean = false
    ): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(http.client)
            .setUserAgent(userAgent)

        // The hardware decoders always go first. Putting FFmpeg ahead of them
        // was tried and reverted: ExoPlayer paces video off the audio clock, and
        // the software audio path drifted enough to run the picture fast, drain
        // the buffer and stall in a loop. Decoder fallback still hands anything
        // the device cannot manage over to FFmpeg.
        val renderers = NextRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        // Waiting twice as long after a rebuffer as on a cold start avoids
        // stuttering straight back into another stall, but it cannot exceed the
        // minimum buffer: DefaultLoadControl rejects that and throws while the
        // player is being built. The low profile asked for 1.5 s minimum against
        // a 1.6 s threshold and took the app down as soon as a stream opened.
        val afterRebufferMs = (profile.startMs * 2).coerceAtMost(profile.minBufferMs)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.startMs,
                afterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("spa", "es", "esp")
                    // Subtitles stay off unless they were asked for. A preferred
                    // text language on its own is enough for the selector to turn
                    // on any matching track it finds, which is why channels that
                    // carry subtitles used to come up with them burned on screen.
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
                    .setPreferredTextLanguages("spa", "es")
                    // Sticks report absurd max sizes; let the renderer decide.
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
            )
        }

        // A dropout is a load error, and the default policy gives up after three
        // tries. Insisting for longer turns many "se cortó" into a hiccup.
        val mediaSourceFactory =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory), extractors)
                .setLoadErrorHandlingPolicy(
                    if (resilient) StubbornLoadErrorPolicy() else DefaultLoadErrorHandlingPolicy()
                )

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also {
                logger.d(
                    "Player",
                    "Reproductor creado (buffer ${profile.name}: arranque ${profile.startMs} ms, " +
                        "mínimo ${profile.minBufferMs} ms, máximo ${profile.maxBufferMs} ms; " +
                        "tolerante=$resilient, subtítulos=$subtitles, UA '$userAgent')"
                )
            }
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

/**
 * Retries a failed chunk far more often than the default three attempts, with a
 * short flat back-off. On IPTV a load error is usually a momentary dropout, and
 * giving up on it is what the user sees as the picture dying.
 */
@UnstableApi
private class StubbornLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 12

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val previous = super.getRetryDelayMsFor(info)
        if (previous == C.TIME_UNSET) return C.TIME_UNSET
        return previous.coerceAtMost(2_000L)
    }
}
