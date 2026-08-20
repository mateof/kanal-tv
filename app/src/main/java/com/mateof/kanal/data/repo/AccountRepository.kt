package com.mateof.kanal.data.repo

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.xtream.XtreamClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many of the account's connections are in use, and by extension whether
 * there is one left to play with.
 *
 * The panel answers this on every authentication and the figure was going
 * straight to the log. It is worth more than that: most accounts allow very few
 * simultaneous connections — often exactly one — and when the slot is taken,
 * every channel fails in a way that looks like a broken channel. The player
 * cannot tell those apart, and neither could the user.
 */
data class AccountStatus(
    val sourceId: String,
    val username: String,
    val status: String,
    val isActive: Boolean,
    val activeConnections: Int,
    val maxConnections: Int,
    val expiresAt: Long,
    val checkedAt: Long
) {
    /** True when the panel says there is nothing free to open. */
    val full: Boolean get() = maxConnections in 1..activeConnections
}

@Singleton
class AccountRepository @Inject constructor(
    private val xtream: XtreamClient,
    private val logger: FileLogger
) {
    private val _status = MutableStateFlow<AccountStatus?>(null)

    /** Last answer from the panel, or null when it has not been asked yet. */
    val status: StateFlow<AccountStatus?> = _status.asStateFlow()

    /** @return the fresh status, or null when the source is not a panel. */
    suspend fun refresh(source: Source): AccountStatus? {
        if (!source.isXtream) {
            _status.value = null
            return null
        }
        return runCatching {
            val account = xtream.authenticate(source)
            AccountStatus(
                sourceId = source.id,
                username = account.username,
                status = account.status,
                isActive = account.isActive,
                activeConnections = account.activeConnections,
                maxConnections = account.maxConnections,
                expiresAt = account.expiresAt,
                checkedAt = System.currentTimeMillis()
            ).also { _status.value = it }
        }.getOrElse { failure ->
            logger.w("Account", "No se pudo consultar el estado de la cuenta", failure)
            null
        }
    }

    /** Records what a sync already found out, so it need not be asked twice. */
    fun remember(status: AccountStatus) {
        _status.value = status
    }

    fun forget() {
        _status.value = null
    }
}
