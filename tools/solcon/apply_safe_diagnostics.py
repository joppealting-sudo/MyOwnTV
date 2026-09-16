#!/usr/bin/env python3
"""Apply the safe Solcon diagnostics/session UX slice.

The diagnostics snapshot intentionally stores only typed booleans/enums/counts/timestamps. Provider
messages, credentials, cookies, signed playback URLs, DRM licence URLs and headers never enter it.
Edits are exact and idempotent so source drift fails before a partial commit is produced.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(path)
    if marker and marker in text:
        return
    if old not in text:
        raise SystemExit(f"Expected diagnostics anchor missing in {path}: {old[:140]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Diagnostics anchor is not unique in {path}: {old[:140]!r}")
    write(path, text.replace(old, new, 1))


# --------------------------------------------------------------------------------------
# Safe persistent diagnostics store.
# --------------------------------------------------------------------------------------
diag_path = "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconDiagnostics.kt"
diag = r'''package tv.own.owntv.provider.solcon.tvplus

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
        NOT_AUTHENTICATED,
        NETWORK,
        PROTOCOL,
        EMPTY_CATALOG,
        PROTECTED_UNSUPPORTED,
        PLAYBACK,
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
        )
    }

    fun recordDiscovery(result: DiscoveryResult) = update {
        it.copy(lastDiscovery = result, lastError = null)
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
        )
    }

    fun recordPlaybackRoute(route: PlaybackRoute) = update {
        it.copy(lastPlaybackRoute = route, lastError = null)
    }

    fun recordError(category: ErrorCategory) = update {
        it.copy(lastError = category)
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

        internal fun inMemory(
            backing: MutableMap<String, String> = mutableMapOf(),
        ): SolconDiagnostics = SolconDiagnostics(MapStore(backing))
    }
}
'''
if not (ROOT / diag_path).exists():
    write(diag_path, diag)
elif "class SolconDiagnostics" not in read(diag_path):
    raise SystemExit("Unexpected existing SolconDiagnostics.kt")

# --------------------------------------------------------------------------------------
# Client: keep only a typed discovery outcome in memory so the repository can persist it safely.
# --------------------------------------------------------------------------------------
client = "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusClient.kt"
replace_once(
    client,
    "    val isLoggedIn: Boolean get() = sessions.isLoggedIn()\n    val deviceId: String get() = sessions.deviceId()\n",
    "    val isLoggedIn: Boolean get() = sessions.isLoggedIn()\n    val deviceId: String get() = sessions.deviceId()\n    var lastDiscoveryResult: SolconDiagnostics.DiscoveryResult? = null\n        private set\n",
    marker="var lastDiscoveryResult:",
)
replace_once(
    client,
    """        val discovered = discoverApiRoot() ?: SolconTvPlusProtocol.DEFAULT_API_ROOT
        val attempts = listOf(
            discovered to SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV,
            discovered to SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV,
            SolconTvPlusProtocol.COMPAT_API_ROOT to SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV,
        ).distinct()

        var lastProtocol: String? = null
        for ((root, flavor) in attempts) {
""",
    """        val discovered = discoverApiRoot()
        val primaryRoot = discovered ?: SolconTvPlusProtocol.DEFAULT_API_ROOT
        val primaryDiscovery = if (discovered != null) {
            SolconDiagnostics.DiscoveryResult.DISCOVERED
        } else {
            SolconDiagnostics.DiscoveryResult.DEFAULT_FALLBACK
        }
        val attempts = listOf(
            Triple(primaryRoot, SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV, primaryDiscovery),
            Triple(primaryRoot, SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV, primaryDiscovery),
            Triple(
                SolconTvPlusProtocol.COMPAT_API_ROOT,
                SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV,
                SolconDiagnostics.DiscoveryResult.COMPAT_FALLBACK,
            ),
        ).distinctBy { it.first to it.second }

        var lastProtocol: String? = null
        for ((root, flavor, discoveryResult) in attempts) {
""",
    marker="val primaryDiscovery = if (discovered != null)",
)
replace_once(
    client,
    """                is SolconTvPlusProtocol.LoginParse.Success -> {
                    sessions.save(root, parsed.session)
                    return@withContext LoginResult.Success(requireNotNull(sessions.load()))
                }
""",
    """                is SolconTvPlusProtocol.LoginParse.Success -> {
                    lastDiscoveryResult = discoveryResult
                    sessions.save(root, parsed.session)
                    return@withContext LoginResult.Success(requireNotNull(sessions.load()))
                }
""",
    marker="lastDiscoveryResult = discoveryResult",
)

# --------------------------------------------------------------------------------------
# Repository: own the safe state, map every provider outcome to typed categories/routes.
# --------------------------------------------------------------------------------------
repo = "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt"
replace_once(
    repo,
    "    private val epgDao: EpgDao,\n    private val settings: SettingsRepository,\n) {\n",
    "    private val epgDao: EpgDao,\n    private val settings: SettingsRepository,\n    private val diagnostics: SolconDiagnostics,\n) {\n",
    marker="private val diagnostics: SolconDiagnostics",
)
replace_once(
    repo,
    """        ) : ResolvedPlayback
        data class Unsupported(val reason: String) : ResolvedPlayback
        data class Failed(val reason: String) : ResolvedPlayback
    }

    fun isLoggedIn(): Boolean = client.isLoggedIn

    suspend fun login(subscriptionNumber: String, pin: String): SolconTvPlusClient.LoginResult =
        client.login(subscriptionNumber, pin)

    fun logout() = client.logout()
""",
    """        ) : ResolvedPlayback
        data class Unsupported(
            val category: SolconDiagnostics.ErrorCategory,
            val reason: String,
        ) : ResolvedPlayback
        data class Failed(
            val category: SolconDiagnostics.ErrorCategory,
            val reason: String,
        ) : ResolvedPlayback
    }

    val diagnosticsState = diagnostics.state

    init {
        diagnostics.setSessionAuthenticated(client.isLoggedIn)
    }

    fun isLoggedIn(): Boolean = client.isLoggedIn

    suspend fun login(subscriptionNumber: String, pin: String): SolconTvPlusClient.LoginResult {
        val result = client.login(subscriptionNumber, pin)
        when (result) {
            is SolconTvPlusClient.LoginResult.Success -> {
                diagnostics.setSessionAuthenticated(true)
                client.lastDiscoveryResult?.let(diagnostics::recordDiscovery)
            }
            is SolconTvPlusClient.LoginResult.Failure -> {
                diagnostics.setSessionAuthenticated(false)
                diagnostics.recordError(result.reason.toDiagnosticsCategory())
            }
        }
        return result
    }

    fun logout() {
        client.logout()
        diagnostics.setSessionAuthenticated(false)
    }
""",
    marker="val diagnosticsState = diagnostics.state",
)
# Convert sync expression to a value so safe summary diagnostics are written once on success/failure.
replace_once(
    repo,
    """    suspend fun sync(
        sourceName: String,
        tvCategoryName: String,
        radioCategoryName: String,
    ): Result<SyncSummary> = runCatching {
""",
    """    suspend fun sync(
        sourceName: String,
        tvCategoryName: String,
        radioCategoryName: String,
    ): Result<SyncSummary> {
        val result = runCatching {
""",
    marker="val result = runCatching {",
)
replace_once(
    repo,
    """        SyncSummary(
            channels = channels.size,
            radioChannels = channels.count { it.radio },
            programmes = programmes,
            epgComplete = epgComplete,
        )
    }

    suspend fun resolveLive(channel: ChannelEntity): ResolvedPlayback {
""",
    """        SyncSummary(
            channels = channels.size,
            radioChannels = channels.count { it.radio },
            programmes = programmes,
            epgComplete = epgComplete,
        )
        }
        result.onSuccess { summary ->
            diagnostics.recordSync(
                tvChannels = summary.channels - summary.radioChannels,
                radioChannels = summary.radioChannels,
                programmes = summary.programmes,
                epgComplete = summary.epgComplete,
            )
        }.onFailure { failure ->
            diagnostics.recordError(
                when (failure) {
                    is EmptyCatalogException -> SolconDiagnostics.ErrorCategory.EMPTY_CATALOG
                    is SolconTvPlusClient.NotAuthenticatedException -> SolconDiagnostics.ErrorCategory.NOT_AUTHENTICATED
                    else -> SolconDiagnostics.ErrorCategory.NETWORK
                },
            )
        }
        return result
    }

    suspend fun resolveLive(channel: ChannelEntity): ResolvedPlayback {
""",
    marker="diagnostics.recordSync(",
)
# Replace resolveLive as a whole. Runtime reason remains local; diagnostics receives only a category.
text = read(repo)
start = text.index("    suspend fun resolveLive(channel: ChannelEntity): ResolvedPlayback {")
end = text.index("\n    suspend fun existingSourceId(): Long?", start)
new_resolve = r'''    suspend fun resolveLive(channel: ChannelEntity): ResolvedPlayback {
        val id = SolconStreamPolicy.tvPlusLiveId(channel.streamUrl) ?: run {
            val category = SolconDiagnostics.ErrorCategory.PROTOCOL
            diagnostics.recordError(category)
            return ResolvedPlayback.Failed(category, "Invalid Solcon TV+ channel reference")
        }
        return client.resolveLivePlayback(id).fold(
            onSuccess = { playback ->
                when (playback) {
                    is SolconTvPlusProtocol.Playback.Clear -> {
                        val route = if (SolconStreamPolicy.classify(playback.url).isMulticast) {
                            SolconDiagnostics.PlaybackRoute.MULTICAST
                        } else {
                            SolconDiagnostics.PlaybackRoute.CLEAR_HTTP
                        }
                        diagnostics.recordPlaybackRoute(route)
                        ResolvedPlayback.Ready(
                            url = playback.url,
                            httpHeaders = StreamHeaders.encode(playback.streamHeaders),
                            drmConfig = null,
                        )
                    }
                    is SolconTvPlusProtocol.Playback.Widevine -> {
                        diagnostics.recordPlaybackRoute(SolconDiagnostics.PlaybackRoute.WIDEVINE)
                        ResolvedPlayback.Ready(
                            url = playback.url,
                            httpHeaders = StreamHeaders.encode(playback.streamHeaders),
                            drmConfig = DrmConfig.encode(
                                DrmConfig(
                                    scheme = DrmConfig.Scheme.WIDEVINE,
                                    licenseUrl = playback.licenseUrl,
                                    headers = playback.licenseHeaders,
                                ),
                            ),
                        )
                    }
                    is SolconTvPlusProtocol.Playback.UnsupportedProtected -> {
                        val category = SolconDiagnostics.ErrorCategory.PROTECTED_UNSUPPORTED
                        diagnostics.recordError(category)
                        ResolvedPlayback.Unsupported(category, playback.reason)
                    }
                    is SolconTvPlusProtocol.Playback.Error -> {
                        val category = SolconDiagnostics.ErrorCategory.PLAYBACK
                        diagnostics.recordError(category)
                        ResolvedPlayback.Failed(category, playback.reason)
                    }
                }
            },
            onFailure = { failure ->
                val category = if (failure is SolconTvPlusClient.NotAuthenticatedException) {
                    SolconDiagnostics.ErrorCategory.NOT_AUTHENTICATED
                } else {
                    SolconDiagnostics.ErrorCategory.NETWORK
                }
                diagnostics.recordError(category)
                ResolvedPlayback.Failed(category, "Solcon TV+ playback request failed")
            },
        )
    }
'''
text = text[:start] + new_resolve + text[end:]
# Typed login error mapper stays implementation-only.
if "toDiagnosticsCategory()" not in text[text.index("companion object"):]:
    companion = """    companion object {
"""
    helper = """    private fun SolconTvPlusClient.FailureReason.toDiagnosticsCategory(): SolconDiagnostics.ErrorCategory =
        when (this) {
            SolconTvPlusClient.FailureReason.INVALID_CREDENTIALS -> SolconDiagnostics.ErrorCategory.INVALID_CREDENTIALS
            SolconTvPlusClient.FailureReason.DEVICE_LIMIT -> SolconDiagnostics.ErrorCategory.DEVICE_LIMIT
            SolconTvPlusClient.FailureReason.NOT_AUTHENTICATED -> SolconDiagnostics.ErrorCategory.NOT_AUTHENTICATED
            SolconTvPlusClient.FailureReason.NETWORK -> SolconDiagnostics.ErrorCategory.NETWORK
            SolconTvPlusClient.FailureReason.PROTOCOL -> SolconDiagnostics.ErrorCategory.PROTOCOL
        }

"""
    if companion not in text:
        raise SystemExit("Repository companion anchor missing")
    text = text.replace(companion, helper + companion, 1)
write(repo, text)

# --------------------------------------------------------------------------------------
# Koin + account ViewModel expose the safe snapshot.
# --------------------------------------------------------------------------------------
module = "app/src/main/java/tv/own/owntv/di/SolconModule.kt"
replace_once(
    module,
    "import tv.own.owntv.provider.solcon.tvplus.SolconTvPlusClient\n",
    "import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics\nimport tv.own.owntv.provider.solcon.tvplus.SolconTvPlusClient\n",
    marker="import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics",
)
replace_once(
    module,
    "val solconModule = module {\n    single { SolconTvPlusSessionStore(androidContext()) }\n",
    "val solconModule = module {\n    single { SolconTvPlusSessionStore(androidContext()) }\n    single { SolconDiagnostics(androidContext()) }\n",
    marker="single { SolconDiagnostics(androidContext()) }",
)

vm = "app/src/main/java/tv/own/owntv/features/settings/SolconTvPlusViewModel.kt"
replace_once(
    vm,
    "    val state: StateFlow<UiState> = _state.asStateFlow()\n\n    private val _error",
    "    val state: StateFlow<UiState> = _state.asStateFlow()\n    val diagnostics = repository.diagnosticsState\n\n    private val _error",
    marker="val diagnostics = repository.diagnosticsState",
)

# --------------------------------------------------------------------------------------
# Account screen: diagnostics survive process restart/logout and are TV-readable.
# --------------------------------------------------------------------------------------
account = "app/src/main/java/tv/own/owntv/features/settings/SolconTvPlusAccountScreen.kt"
replace_once(
    account,
    "    val state by vm.state.collectAsStateWithLifecycle()\n    val error by vm.error.collectAsStateWithLifecycle()\n",
    "    val state by vm.state.collectAsStateWithLifecycle()\n    val error by vm.error.collectAsStateWithLifecycle()\n    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()\n",
    marker="vm.diagnostics.collectAsStateWithLifecycle()",
)
replace_once(
    account,
    "        Spacer(Modifier.height(8.dp))\n\n        when (val current = state) {\n",
    "        Spacer(Modifier.height(8.dp))\n        SolconDiagnosticsSummary(diagnostics)\n\n        when (val current = state) {\n",
    marker="SolconDiagnosticsSummary(diagnostics)",
)
# Append safe diagnostics composable before the existing errorText helper.
text = read(account)
marker = "@Composable\nprivate fun errorText(kind: SolconTvPlusViewModel.ErrorKind): String"
if "private fun SolconDiagnosticsSummary(" not in text:
    idx = text.index(marker)
    helper = r'''@Composable
private fun SolconDiagnosticsSummary(
    snapshot: tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.Snapshot,
) {
    if (
        snapshot.lastSyncAtMs == null &&
        snapshot.lastDiscovery == null &&
        snapshot.lastPlaybackRoute == null &&
        snapshot.lastError == null
    ) return

    val colors = OwnTVTheme.colors
    Text(
        stringResource(R.string.solcon_tvplus_status_title),
        style = MaterialTheme.typography.titleMedium,
        color = colors.onSurface,
    )
    snapshot.lastSyncAtMs?.let { atMs ->
        val formatted = remember(atMs) {
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
            ).format(java.util.Date(atMs))
        }
        Text(
            stringResource(R.string.solcon_tvplus_last_sync_time, formatted),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        val tvText = pluralStringResource(
            R.plurals.solcon_tvplus_channels_count,
            snapshot.tvChannels,
            snapshot.tvChannels,
        )
        val radioText = pluralStringResource(
            R.plurals.solcon_tvplus_radio_channels_count,
            snapshot.radioChannels,
            snapshot.radioChannels,
        )
        val guideText = pluralStringResource(
            R.plurals.solcon_tvplus_guide_entries_count,
            snapshot.programmes,
            snapshot.programmes,
        )
        Text(
            stringResource(R.string.solcon_tvplus_cached_summary, tvText, radioText, guideText),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
    snapshot.epgComplete?.let { complete ->
        Text(
            stringResource(
                if (complete) R.string.solcon_tvplus_epg_complete else R.string.solcon_tvplus_epg_partial,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
    snapshot.lastDiscovery?.let { discovery ->
        Text(
            stringResource(
                when (discovery) {
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.DiscoveryResult.DISCOVERED ->
                        R.string.solcon_tvplus_discovery_discovered
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.DiscoveryResult.DEFAULT_FALLBACK ->
                        R.string.solcon_tvplus_discovery_default
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.DiscoveryResult.COMPAT_FALLBACK ->
                        R.string.solcon_tvplus_discovery_compat
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
    snapshot.lastPlaybackRoute?.let { route ->
        Text(
            stringResource(
                when (route) {
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.PlaybackRoute.CLEAR_HTTP ->
                        R.string.solcon_tvplus_route_clear
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.PlaybackRoute.MULTICAST ->
                        R.string.solcon_tvplus_route_multicast
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.PlaybackRoute.WIDEVINE ->
                        R.string.solcon_tvplus_route_widevine
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

'''
    text = text[:idx] + helper + text[idx:]
    write(account, text)

# --------------------------------------------------------------------------------------
# Live: typed failure event; never log the provider-supplied reason.
# --------------------------------------------------------------------------------------
live = "app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt"
replace_once(
    live,
    "    private val _liveOnExo = MutableStateFlow(false)\n    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()\n",
    "    private val _liveOnExo = MutableStateFlow(false)\n    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()\n    private val _solconPlaybackError = MutableSharedFlow<tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory>(extraBufferCapacity = 1)\n    val solconPlaybackError: SharedFlow<tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory> = _solconPlaybackError.asSharedFlow()\n",
    marker="val solconPlaybackError:",
)
# Replace helper and notify only deliberate playback actions, not focus previews.
text = read(live)
start = text.index("    /** Resolve a Solcon TV+ channel just before playback without persisting the signed result. */")
end = text.index("\n    /** Internal playback:", start)
new_helper = r'''    /** Resolve a Solcon TV+ channel just before playback without persisting the signed result. */
    private suspend fun resolveSolconPlayback(
        channel: ChannelEntity,
        notifyFailure: Boolean = false,
    ): ChannelEntity? {
        if (!tv.own.owntv.provider.solcon.SolconStreamPolicy.classify(channel.streamUrl).isTvPlus) return channel
        return when (val resolved = solconTvPlusRepository.resolveLive(channel)) {
            is tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback.Ready -> channel.copy(
                streamUrl = resolved.url,
                httpHeaders = resolved.httpHeaders,
                drmConfig = resolved.drmConfig,
            )
            is tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback.Unsupported -> {
                engineLog("Solcon TV+ playback unavailable (${resolved.category.name})")
                if (notifyFailure) _solconPlaybackError.tryEmit(resolved.category)
                null
            }
            is tv.own.owntv.provider.solcon.tvplus.SolconTvPlusRepository.ResolvedPlayback.Failed -> {
                engineLog("Solcon TV+ playback failed (${resolved.category.name})")
                if (notifyFailure) _solconPlaybackError.tryEmit(resolved.category)
                null
            }
        }
    }
'''
text = text[:start] + new_helper + text[end:]
# Explicit external action and full-screen tune should notify. Preview/multiview calls remain default false.
external_start = text.index("    fun playExternal(channel: ChannelEntity) {")
external_end = text.index("\n    /** Go full-screen on [channel].", external_start)
external_block = text[external_start:external_end]
external_block = external_block.replace(
    "val playableChannel = resolveSolconPlayback(channel) ?: return@launch",
    "val playableChannel = resolveSolconPlayback(channel, notifyFailure = true) ?: return@launch",
)
text = text[:external_start] + external_block + text[external_end:]
text = text.replace(
    "        val channel = resolveSolconPlayback(originalChannel) ?: return\n",
    "        val channel = resolveSolconPlayback(originalChannel, notifyFailure = true) ?: return\n",
    1,
)
write(live, text)

# --------------------------------------------------------------------------------------
# Shell: map typed errors to local strings and surface via the existing in-app TV toast.
# --------------------------------------------------------------------------------------
shell = "app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt"
replace_once(
    shell,
    "import kotlinx.coroutines.flow.distinctUntilChanged\n",
    "import kotlinx.coroutines.flow.collect\nimport kotlinx.coroutines.flow.distinctUntilChanged\n",
    marker="import kotlinx.coroutines.flow.collect",
)
replace_once(
    shell,
    """    val liveVm = org.koin.androidx.compose.koinViewModel<LiveViewModel>()
    val epgVm = org.koin.androidx.compose.koinViewModel<tv.own.owntv.features.epg.EpgViewModel>()
""",
    """    val liveVm = org.koin.androidx.compose.koinViewModel<LiveViewModel>()
    val solconSessionExpired = stringResource(R.string.solcon_tvplus_playback_session_expired)
    val solconProtectedUnsupported = stringResource(R.string.solcon_tvplus_playback_unsupported)
    val solconPlaybackFailed = stringResource(R.string.solcon_tvplus_error_playback)
    val solconNetworkFailed = stringResource(R.string.solcon_tvplus_error_network)
    LaunchedEffect(liveVm) {
        liveVm.solconPlaybackError.collect { category ->
            localSubToast.show(
                when (category) {
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory.NOT_AUTHENTICATED -> solconSessionExpired
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory.PROTECTED_UNSUPPORTED -> solconProtectedUnsupported
                    tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.ErrorCategory.NETWORK -> solconNetworkFailed
                    else -> solconPlaybackFailed
                },
            )
        }
    }
    val epgVm = org.koin.androidx.compose.koinViewModel<tv.own.owntv.features.epg.EpgViewModel>()
""",
    marker="liveVm.solconPlaybackError.collect",
)

# --------------------------------------------------------------------------------------
# Resource-backed user-visible diagnostics labels.
# --------------------------------------------------------------------------------------
strings = "app/src/main/res/values/strings_solcon.xml"
text = read(strings)
if "solcon_tvplus_status_title" not in text:
    insert = r'''    <string name="solcon_tvplus_status_title">Provider status</string>
    <string name="solcon_tvplus_last_sync_time">Last sync: %1$s</string>
    <string name="solcon_tvplus_cached_summary">Cached: %1$s, %2$s and %3$s.</string>
    <string name="solcon_tvplus_epg_complete">EPG sync complete</string>
    <string name="solcon_tvplus_epg_partial">EPG sync partial</string>
    <string name="solcon_tvplus_discovery_discovered">API endpoint discovered automatically</string>
    <string name="solcon_tvplus_discovery_default">Using Solcon’s standard API endpoint</string>
    <string name="solcon_tvplus_discovery_compat">Using Solcon’s compatibility API endpoint</string>
    <string name="solcon_tvplus_route_clear">Last playback: standard stream</string>
    <string name="solcon_tvplus_route_multicast">Last playback: multicast</string>
    <string name="solcon_tvplus_route_widevine">Last playback: Widevine</string>
    <string name="solcon_tvplus_playback_session_expired">The Solcon TV+ session expired. Sign in again.</string>
    <string name="solcon_tvplus_playback_unsupported">This protected Solcon TV+ channel is not supported by MyOwnTV.</string>
'''
    text = text.replace("</resources>", insert + "</resources>")
    write(strings, text)

# Classify only the new implementation-only diagnostics file. Compose copy remains resource-backed.
classifier = "tools/solcon/classify_solcon_protocol_literals.py"
replace_once(
    classifier,
    '    "app/src/main/java/tv/own/owntv/provider/solcon/SolconStreamPolicy.kt": "protocol",\n',
    '    "app/src/main/java/tv/own/owntv/provider/solcon/SolconStreamPolicy.kt": "protocol",\n    "app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconDiagnostics.kt": "technical",\n',
    marker='SolconDiagnostics.kt": "technical"',
)

print("Applied sanitized Solcon diagnostics, status UI, and typed playback failure UX.")
