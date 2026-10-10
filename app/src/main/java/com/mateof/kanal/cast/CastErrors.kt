package com.mateof.kanal.cast

import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import java.io.IOException

/**
 * The relay cannot be offered from here: no address on the television's
 * network, a VPN that swallows that network too, a port already taken.
 *
 * The message is the precise reason, for the log and the detail line; [hint]
 * is what to do about it.
 */
class RelayUnavailable(message: String, val hint: UiText) : IOException(message)

/** The direct route failed and the relay could not be tried either. */
class CastFailure(direct: Throwable, val relay: RelayUnavailable) :
    IOException("${direct.message} · tampoco a través del móvil: ${relay.message}", direct)

/** Turns a failed send into something the user can act on. */
object CastHints {

    fun of(error: Throwable): UiText? = when (error) {
        is RelayUnavailable -> error.hint
        is CastFailure -> error.relay.hint
        else -> forDetail(error.message.orEmpty())
    }

    /** A UPnP code is exact but says nothing about what to do about it. */
    private fun forDetail(detail: String): UiText? = when {
        detail.contains("UPnP 716") -> UiText(R.string.cast_hint_716)
        detail.contains("UPnP 714") -> UiText(R.string.cast_hint_714)
        detail.contains("UPnP 701") -> UiText(R.string.cast_hint_701)
        detail.contains("UPnP 402") -> UiText(R.string.cast_hint_402)
        detail.contains("UPnP 401") -> UiText(R.string.cast_hint_401)
        else -> null
    }
}
