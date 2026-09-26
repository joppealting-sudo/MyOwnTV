package tv.own.owntv.provider.solcon.tvplus

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sanitized provider diagnostics that are safe to persist and show on a television.
 *
 * Deliberately impossible to write arbitrary text: the snapshot contains only typed enums, booleans,
 * counts and a timestamp. Credentials, session material, signed playback URLs, licence URLs, headers
 * and raw provider responses therefore cannot accidentally become diagnostics.
 */
class SolconDiagnostics private constructor(
    private val store: Store,
) {
    enum class DiscoveryResult { DISCOVERED, DEFAULT_FALLBACK, COMPAT_FALLBACK }
    enum class PlaybackRoute { CLEAR_HTTP, MULTICAST, WIDEVINE }
    enum class ErrorCategory {
        INVALID_CREDENTIALS,
        DEVICE_LIMIT,
        ACCOUNT_BLOCKED,
        LOGIN_REJECTED,
        NOT_AUTHENTICATED,
        NETWORK,
        PROTOCOL,
        EMPTY_CATALOG,
        PROTECTED_UNSUPPORTED,
        PLAYBACK,
    }

    /** The request a failure happened on. */
    enum class Step { SIGN_IN, CHANNELS, GUIDE, PLAYBACK }

    /**
     * Where the last failure happened and what Solcon answered: a step, an HTTP status and Solcon's own
     * error code — enough to tell a refused device from a changed API, and never anything private. A code
     * that is not a plain identifier is dropped rather than shown.
     */
    class FailureDetail(val step: Step, val httpStatus: Int? = null, providerCode: String? = null) {
        val providerCode: String? = providerCode?.trim()?.takeIf { SAFE_CODE.matches(it) }

        override fun equals(other: Any?): Boolean =
            other is FailureDetail && other.step == step && other.httpStatus == httpStatus && other.providerCode == providerCode

        override fun hashCode(): Int = (step.hashCode() * 31 + (httpStatus ?: 0)) * 31 + (providerCode?.hashCode() ?: 0)

        override fun toString(): String = "FailureDetail(step=$step, httpStatus=$httpStatus, providerCode=$providerCode)"
    }

    data class Snapshot(
        val sessionAuthenticated: Boolean = false,
        val lastDiscovery: DiscoveryResult? = null,
        val lastSyncAtMs: Long? = null,
        val tvChannels: Int = 0,
        val radioChannels: Int = 0,
        val programmes: Int = 0,
        val epgComplete: Boolean? = null,
        val lastPlaybackRoute: PlaybackRoute? = null,
        val lastError: ErrorCategory? = null,
        val lastFailure: FailureDetail? = null,
    )

    constructor(context: Context) : this(
        PreferencesStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        ),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setSessionAuthenticated(authenticated: Boolean) = update {
        it.copy(
            sessionAuthenticated = authenticated,
            lastError = if (authenticated) null else it.lastError,
            lastFailure = if (authenticated) null else it.lastFailure,
        )
    }

    fun recordDiscovery(result: DiscoveryResult) = update {
        it.copy(lastDiscovery = result, lastError = null, lastFailure = null)
    }

    fun recordSync(
        tvChannels: Int,
        radioChannels: Int,
        programmes: Int,
        epgComplete: Boolean,
        atMs: Long = System.currentTimeMillis(),
    ) = update {
        it.copy(
            sessionAuthenticated = true,
            lastSyncAtMs = atMs,
            tvChannels = tvChannels.coerceAtLeast(0),
            radioChannels = radioChannels.coerceAtLeast(0),
            programmes = programmes.coerceAtLeast(0),
            epgComplete = epgComplete,
            lastError = null,
            lastFailure = null,
        )
    }

    fun recordPlaybackRoute(route: PlaybackRoute) = update {
        it.copy(lastPlaybackRoute = route, lastError = null, lastFailure = null)
    }

    fun recordError(category: ErrorCategory, detail: FailureDetail? = null) = update {
        it.copy(lastError = category, lastFailure = detail)
    }

    private fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(_state.value)
        persist(next)
        _state.value = next
    }

    private fun load(): Snapshot = Snapshot(
        sessionAuthenticated = store.read(KEY_AUTH)?.toBooleanStrictOrNull() ?: false,
        lastDiscovery = store.read(KEY_DISCOVERY)?.let { runCatching { DiscoveryResult.valueOf(it) }.getOrNull() },
        lastSyncAtMs = store.read(KEY_SYNC_AT)?.toLongOrNull(),
        tvChannels = store.read(KEY_TV_COUNT)?.toIntOrNull() ?: 0,
        radioChannels = store.read(KEY_RADIO_COUNT)?.toIntOrNull() ?: 0,
        programmes = store.read(KEY_PROGRAMME_COUNT)?.toIntOrNull() ?: 0,
        epgComplete = store.read(KEY_EPG_COMPLETE)?.toBooleanStrictOrNull(),
        lastPlaybackRoute = store.read(KEY_PLAYBACK_ROUTE)?.let { runCatching { PlaybackRoute.valueOf(it) }.getOrNull() },
        lastError = store.read(KEY_ERROR)?.let { runCatching { ErrorCategory.valueOf(it) }.getOrNull() },
        lastFailure = store.read(KEY_FAILURE_STEP)?.let { runCatching { Step.valueOf(it) }.getOrNull() }?.let { step ->
            FailureDetail(
                step = step,
                httpStatus = store.read(KEY_FAILURE_HTTP)?.toIntOrNull(),
                providerCode = store.read(KEY_FAILURE_CODE),
            )
        },
    )

    private fun persist(snapshot: Snapshot) {
        store.write(KEY_AUTH, snapshot.sessionAuthenticated.toString())
        store.write(KEY_DISCOVERY, snapshot.lastDiscovery?.name)
        store.write(KEY_SYNC_AT, snapshot.lastSyncAtMs?.toString())
        store.write(KEY_TV_COUNT, snapshot.tvChannels.toString())
        store.write(KEY_RADIO_COUNT, snapshot.radioChannels.toString())
        store.write(KEY_PROGRAMME_COUNT, snapshot.programmes.toString())
        store.write(KEY_EPG_COMPLETE, snapshot.epgComplete?.toString())
        store.write(KEY_PLAYBACK_ROUTE, snapshot.lastPlaybackRoute?.name)
        store.write(KEY_ERROR, snapshot.lastError?.name)
        store.write(KEY_FAILURE_STEP, snapshot.lastFailure?.step?.name)
        store.write(KEY_FAILURE_HTTP, snapshot.lastFailure?.httpStatus?.toString())
        store.write(KEY_FAILURE_CODE, snapshot.lastFailure?.providerCode)
    }

    private interface Store {
        fun read(key: String): String?
        fun write(key: String, value: String?)
    }

    private class PreferencesStore(private val preferences: SharedPreferences) : Store {
        override fun read(key: String): String? = preferences.getString(key, null)
        override fun write(key: String, value: String?) {
            preferences.edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.apply()
        }
    }

    private class MapStore(private val backing: MutableMap<String, String>) : Store {
        override fun read(key: String): String? = backing[key]
        override fun write(key: String, value: String?) {
            if (value == null) backing.remove(key) else backing[key] = value
        }
    }

    companion object {
        private const val PREFS = "solcon_tvplus_diagnostics"
        private const val KEY_AUTH = "session_authenticated"
        private const val KEY_DISCOVERY = "last_discovery"
        private const val KEY_SYNC_AT = "last_sync_at_ms"
        private const val KEY_TV_COUNT = "tv_channels"
        private const val KEY_RADIO_COUNT = "radio_channels"
        private const val KEY_PROGRAMME_COUNT = "programmes"
        private const val KEY_EPG_COMPLETE = "epg_complete"
        private const val KEY_PLAYBACK_ROUTE = "last_playback_route"
        private const val KEY_ERROR = "last_error"
        private const val KEY_FAILURE_STEP = "last_failure_step"
        private const val KEY_FAILURE_HTTP = "last_failure_http"
        private const val KEY_FAILURE_CODE = "last_failure_code"
        private val SAFE_CODE = Regex("^[A-Za-z0-9_.:-]{1,48}$")

        internal fun inMemory(
            backing: MutableMap<String, String> = mutableMapOf(),
        ): SolconDiagnostics = SolconDiagnostics(MapStore(backing))
    }
}
