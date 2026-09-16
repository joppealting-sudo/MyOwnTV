package tv.own.owntv.provider.solcon.tvplus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Small authenticated AVS client. It creates its own Solcon TV+ device session using credentials the
 * user enters in OwnTV; it never imports credentials, certificates or private material from another app.
 */
class SolconTvPlusClient(
    private val http: OkHttpClient,
    private val sessions: SolconTvPlusSessionStore,
) {
    enum class FailureReason { INVALID_CREDENTIALS, DEVICE_LIMIT, NOT_AUTHENTICATED, NETWORK, PROTOCOL }

    sealed interface LoginResult {
        data class Success(val session: SolconTvPlusSessionStore.StoredSession) : LoginResult
        data class Failure(val reason: FailureReason, val message: String?) : LoginResult
    }

    val isLoggedIn: Boolean get() = sessions.isLoggedIn()
    val deviceId: String get() = sessions.deviceId()
    var lastDiscoveryResult: SolconDiagnostics.DiscoveryResult? = null
        private set

    suspend fun login(subscriptionNumber: String, pin: String): LoginResult = withContext(Dispatchers.IO) {
        if (subscriptionNumber.isBlank() || pin.isBlank()) {
            return@withContext LoginResult.Failure(FailureReason.INVALID_CREDENTIALS, "Missing credentials")
        }
        val discovered = discoverApiRoot()
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
            val response = runCatching {
                post(
                    SolconTvPlusProtocol.loginUrl(root),
                    SolconTvPlusProtocol.buildLoginBody(subscriptionNumber, pin, sessions.deviceId(), flavor),
                    session = null,
                )
            }.getOrElse {
                return@withContext LoginResult.Failure(FailureReason.NETWORK, it.message)
            }
            if (response.code == 401 || response.code == 403) {
                return@withContext LoginResult.Failure(FailureReason.INVALID_CREDENTIALS, null)
            }
            if (response.code !in 200..299) {
                // Only compatibility-shaped errors may advance to another login shape. Never retry an
                // actual auth rejection several times and risk locking the user's PIN.
                if (response.code in setOf(400, 404, 405, 415, 422)) {
                    lastProtocol = "HTTP ${response.code}"
                    continue
                }
                return@withContext LoginResult.Failure(FailureReason.NETWORK, "HTTP ${response.code}")
            }
            when (val parsed = SolconTvPlusProtocol.parseLoginResponse(response.body, response.cookieHeader)) {
                is SolconTvPlusProtocol.LoginParse.Success -> {
                    lastDiscoveryResult = discoveryResult
                    sessions.save(root, parsed.session)
                    return@withContext LoginResult.Success(requireNotNull(sessions.load()))
                }
                is SolconTvPlusProtocol.LoginParse.Rejected -> {
                    val text = parsed.description.orEmpty()
                    val kind = if (
                        text.contains("device", true) &&
                        (text.contains("limit", true) || text.contains("max", true) || text.contains("maximum", true))
                    ) FailureReason.DEVICE_LIMIT else FailureReason.INVALID_CREDENTIALS
                    return@withContext LoginResult.Failure(kind, parsed.description)
                }
                is SolconTvPlusProtocol.LoginParse.ProtocolError -> {
                    lastProtocol = parsed.description
                }
            }
        }
        LoginResult.Failure(FailureReason.PROTOCOL, lastProtocol)
    }

    suspend fun liveChannels(): Result<List<SolconTvPlusProtocol.LiveChannel>> = authenticatedGet { session ->
        SolconTvPlusProtocol.liveChannelsUrl(session.apiRoot)
    }.mapCatching { payload ->
        if (payload.code == 401 || payload.code == 403) throw NotAuthenticatedException()
        if (payload.code !in 200..299) error("Channel catalog HTTP ${payload.code}")
        SolconTvPlusProtocol.parseLiveChannels(payload.body)
    }

    suspend fun epg(
        startMs: Long,
        endMs: Long,
        channelIds: Collection<String>,
    ): Result<List<SolconTvPlusProtocol.EpgEntry>> = authenticatedGet { session ->
        SolconTvPlusProtocol.epgUrl(session.apiRoot, startMs, endMs, channelIds)
    }.mapCatching { payload ->
        if (payload.code == 401 || payload.code == 403) throw NotAuthenticatedException()
        if (payload.code !in 200..299) error("EPG HTTP ${payload.code}")
        SolconTvPlusProtocol.parseEpg(payload.body)
    }

    suspend fun resolveLivePlayback(
        channelId: String,
        assetId: String? = null,
    ): Result<SolconTvPlusProtocol.Playback> = authenticatedGet { session ->
        SolconTvPlusProtocol.livePlaybackUrl(
            session.apiRoot,
            channelId,
            assetId,
            sessions.deviceId(),
            System.currentTimeMillis(),
        )
    }.mapCatching { payload ->
        if (payload.code == 401 || payload.code == 403) throw NotAuthenticatedException()
        if (payload.code !in 200..299) error("Playback HTTP ${payload.code}")
        SolconTvPlusProtocol.parsePlaybackResponse(payload.body)
    }

    suspend fun validateSession(): Boolean = liveChannels().getOrNull() != null

    fun logout() = sessions.clear()

    private suspend fun discoverApiRoot(): String? = runCatching {
        val payload = get(SolconTvPlusProtocol.DISCOVERY_URL, session = null)
        if (payload.code in 200..299) SolconTvPlusProtocol.parseDiscoveredApiRoot(payload.body) else null
    }.getOrNull()

    private suspend fun authenticatedGet(url: (SolconTvPlusSessionStore.StoredSession) -> String): Result<ResponsePayload> {
        val session = sessions.load() ?: return Result.failure(NotAuthenticatedException())
        return runCatching { withContext(Dispatchers.IO) { get(url(session), session) } }
    }

    private fun get(url: String, session: SolconTvPlusSessionStore.StoredSession?): ResponsePayload {
        val request = requestBuilder(url, session).get().build()
        return http.newCall(request).execute().use { response ->
            ResponsePayload(response.code, response.body?.string().orEmpty(), cookieHeader(response.headers.values("Set-Cookie")))
        }
    }

    private fun post(url: String, json: String, session: SolconTvPlusSessionStore.StoredSession?): ResponsePayload {
        val body = json.toRequestBody(JSON)
        val request = requestBuilder(url, session).post(body).build()
        return http.newCall(request).execute().use { response ->
            ResponsePayload(response.code, response.body?.string().orEmpty(), cookieHeader(response.headers.values("Set-Cookie")))
        }
    }

    private fun requestBuilder(url: String, session: SolconTvPlusSessionStore.StoredSession?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyOwnTV Android TV")
        session?.token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        session?.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        session?.clientId?.takeIf { it.isNotBlank() }?.let { builder.header("client-id", it) }
        session?.tenantId?.takeIf { it.isNotBlank() }?.let { builder.header("tenant-id", it) }
        return builder
    }

    private fun cookieHeader(setCookies: List<String>): String? {
        val pairs = setCookies.mapNotNull { raw ->
            raw.substringBefore(';').trim().takeIf { it.contains('=') && it.isNotBlank() }
        }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private data class ResponsePayload(val code: Int, val body: String, val cookieHeader: String?)

    class NotAuthenticatedException : IllegalStateException("Solcon TV+ session is not authenticated")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
