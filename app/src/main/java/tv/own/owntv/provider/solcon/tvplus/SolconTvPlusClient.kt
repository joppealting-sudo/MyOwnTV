package tv.own.owntv.provider.solcon.tvplus

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.FailureDetail
import tv.own.owntv.provider.solcon.tvplus.SolconDiagnostics.Step

/**
 * Small authenticated AVS client. It creates its own Solcon TV+ device session using credentials the
 * user enters in OwnTV; it never imports credentials, certificates or private material from another app.
 */
class SolconTvPlusClient(
    private val http: OkHttpClient,
    private val sessions: SolconTvPlusSessionStore,
) {
    enum class FailureReason { INVALID_CREDENTIALS, DEVICE_LIMIT, ACCOUNT_BLOCKED, REJECTED, NOT_AUTHENTICATED, NETWORK, PROTOCOL }

    sealed interface LoginResult {
        data class Success(val session: SolconTvPlusSessionStore.StoredSession) : LoginResult
        data class Failure(val reason: FailureReason, val detail: FailureDetail) : LoginResult
    }

    val isLoggedIn: Boolean get() = sessions.isLoggedIn()
    val deviceId: String get() = sessions.deviceId()
    var lastDiscoveryResult: SolconDiagnostics.DiscoveryResult? = null
        private set

    suspend fun login(subscriptionNumber: String, pin: String): LoginResult = withContext(Dispatchers.IO) {
        if (subscriptionNumber.isBlank() || pin.isBlank()) {
            return@withContext LoginResult.Failure(FailureReason.INVALID_CREDENTIALS, FailureDetail(Step.SIGN_IN))
        }

        val discovered = discoverApiEndpoint(subscriptionNumber)
        val endpoint = discovered ?: SolconDiscoveryEndpoint(FALLBACK_AVS_HOST)
        val attempts = listOf(
            Attempt(
                SolconTvPlusProtocol.apiRoot(endpoint, SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV),
                SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV,
                if (discovered != null) SolconDiagnostics.DiscoveryResult.DISCOVERED else SolconDiagnostics.DiscoveryResult.COMPAT_FALLBACK,
            ),
            Attempt(
                SolconTvPlusProtocol.apiRoot(endpoint, SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV),
                SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV,
                if (discovered != null) SolconDiagnostics.DiscoveryResult.DISCOVERED else SolconDiagnostics.DiscoveryResult.DEFAULT_FALLBACK,
            ),
        ).distinctBy { it.root to it.flavor }

        var lastStatus: Int? = null
        for (attempt in attempts) {
            val response = runCatching {
                post(
                    SolconTvPlusProtocol.loginUrl(attempt.root),
                    SolconTvPlusProtocol.buildLoginBody(subscriptionNumber, pin, sessions.deviceId(), attempt.flavor),
                    session = null,
                )
            }.getOrElse {
                return@withContext LoginResult.Failure(FailureReason.NETWORK, FailureDetail(Step.SIGN_IN))
            }
            lastStatus = response.code
            if (response.code == 401 || response.code == 403) {
                // The body usually names the refusal; without one a 401 is the credentials and a 403 is
                // this client or device being turned away.
                val rejected = SolconTvPlusProtocol.parseLoginResponse(response.body, null) as? SolconTvPlusProtocol.LoginParse.Rejected
                val reason = rejected?.let { rejectionReason(it) }?.takeIf { rejected.code != null }
                    ?: if (response.code == 401) FailureReason.INVALID_CREDENTIALS else FailureReason.REJECTED
                return@withContext LoginResult.Failure(reason, FailureDetail(Step.SIGN_IN, response.code, rejected?.code))
            }
            if (response.code !in 200..299) {
                // Only compatibility-shaped errors may advance to another login shape. Never retry an
                // actual auth rejection several times and risk locking the user's PIN.
                if (response.code in COMPAT_STATUSES) continue
                return@withContext LoginResult.Failure(FailureReason.NETWORK, FailureDetail(Step.SIGN_IN, response.code))
            }
            when (val parsed = SolconTvPlusProtocol.parseLoginResponse(response.body, response.cookieHeader)) {
                is SolconTvPlusProtocol.LoginParse.Success -> {
                    lastDiscoveryResult = attempt.discovery
                    sessions.save(attempt.root, parsed.session)
                    return@withContext LoginResult.Success(requireNotNull(sessions.load()))
                }
                is SolconTvPlusProtocol.LoginParse.Rejected -> return@withContext LoginResult.Failure(
                    rejectionReason(parsed),
                    FailureDetail(Step.SIGN_IN, response.code, parsed.code),
                )
                is SolconTvPlusProtocol.LoginParse.ProtocolError -> Unit
            }
        }
        LoginResult.Failure(FailureReason.PROTOCOL, FailureDetail(Step.SIGN_IN, lastStatus))
    }

    suspend fun liveChannels(): Result<List<SolconTvPlusProtocol.LiveChannel>> = authenticatedGet { session ->
        SolconTvPlusProtocol.liveChannelsUrl(session.apiRoot)
    }.mapCatching { payload ->
        SolconTvPlusProtocol.parseLiveChannels(payload.requireOk().body)
    }

    suspend fun epg(
        startMs: Long,
        endMs: Long,
        channelIds: Collection<String>,
    ): Result<List<SolconTvPlusProtocol.EpgEntry>> = authenticatedGet { session ->
        SolconTvPlusProtocol.epgUrl(session.apiRoot, startMs, endMs, channelIds)
    }.mapCatching { payload ->
        SolconTvPlusProtocol.parseEpg(payload.requireOk().body)
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
        SolconTvPlusProtocol.parsePlaybackResponse(payload.requireOk().body)
    }

    fun logout() = sessions.clear()

    private fun rejectionReason(rejected: SolconTvPlusProtocol.LoginParse.Rejected): FailureReason =
        when (SolconTvPlusProtocol.classifyLoginRejection(rejected.code, rejected.description)) {
            SolconTvPlusProtocol.LoginRejectionKind.INVALID_CREDENTIALS -> FailureReason.INVALID_CREDENTIALS
            SolconTvPlusProtocol.LoginRejectionKind.ACCOUNT_BLOCKED -> FailureReason.ACCOUNT_BLOCKED
            SolconTvPlusProtocol.LoginRejectionKind.DEVICE_LIMIT -> FailureReason.DEVICE_LIMIT
            SolconTvPlusProtocol.LoginRejectionKind.OTHER -> FailureReason.REJECTED
        }

    private suspend fun discoverApiEndpoint(subscriptionNumber: String): SolconDiscoveryEndpoint? = runCatching {
        val payload = get(SolconTvPlusProtocol.subscriptionDiscoveryUrl(subscriptionNumber), session = null)
        if (payload.code in 200..299) SolconTvPlusProtocol.parseDiscoveryEndpoint(payload.body) else null
    }.getOrNull()

    private suspend fun authenticatedGet(url: (SolconTvPlusSessionStore.StoredSession) -> String): Result<ResponsePayload> {
        val session = sessions.load() ?: return Result.failure(NotAuthenticatedException())
        return runCatching { withContext(Dispatchers.IO) { get(url(session), session) } }
    }

    private fun get(url: String, session: SolconTvPlusSessionStore.StoredSession?): ResponsePayload {
        val request = requestBuilder(url, session).get().build()
        return http.newCall(request).execute().use { response ->
            ResponsePayload(response.code, response.body.string(), cookieHeader(response.headers.values("Set-Cookie")))
        }
    }

    private fun post(url: String, json: String, session: SolconTvPlusSessionStore.StoredSession?): ResponsePayload {
        val body = json.toRequestBody(JSON)
        val request = requestBuilder(url, session).post(body).build()
        return http.newCall(request).execute().use { response ->
            ResponsePayload(response.code, response.body.string(), cookieHeader(response.headers.values("Set-Cookie")))
        }
    }

    private fun requestBuilder(url: String, session: SolconTvPlusSessionStore.StoredSession?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
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

    private class Attempt(
        val root: String,
        val flavor: SolconTvPlusProtocol.LoginFlavor,
        val discovery: SolconDiagnostics.DiscoveryResult,
    )

    private data class ResponsePayload(val code: Int, val body: String, val cookieHeader: String?) {
        fun requireOk(): ResponsePayload {
            if (code == 401 || code == 403) throw NotAuthenticatedException()
            if (code !in 200..299) throw HttpStatusException(code)
            return this
        }
    }

    class NotAuthenticatedException : IllegalStateException("Solcon TV+ session is not authenticated")

    /** Solcon answered an authenticated request with [status]; the caller knows which request it was. */
    class HttpStatusException(val status: Int) : IOException("HTTP $status")

    private companion object {
        const val FALLBACK_AVS_HOST = "api-avs67.tv.prod.itvavs.prod.aws.kpn.com"
        const val USER_AGENT = "MyOwnTV Android TV"
        val COMPAT_STATUSES = setOf(400, 404, 405, 415, 422)
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
