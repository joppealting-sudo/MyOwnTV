package tv.own.owntv.provider.solcon.tvplus

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure AVS protocol helpers. No credentials, tokens, cookies or signed playback URLs are logged here.
 * The shapes mirror the legitimate subscription-number + PIN login used by Solcon TV+; the legacy
 * PCTV payload is kept only as a compatibility fallback for older AVS deployments.
 */
object SolconTvPlusProtocol {
    const val DISCOVERY_URL = "https://ausar.tcloud-itv-prd1.prod.aws.kpn.com/public/v1/ear?type=ott"
    const val DEFAULT_API_ROOT = "https://api-avs67.tv.prod.itvavs.prod.aws.kpn.com/101/1.5/A/nld/kpn"
    const val COMPAT_API_ROOT = "https://api-avs67.tv.prod.itvavs.prod.aws.kpn.com/101/1.2.0/A/nld/pctv/kpn"

    enum class LoginFlavor { CURRENT_ANDROID_TV, LEGACY_PCTV }

    data class Session(
        val token: String?,
        val cookie: String?,
        val deviceSession: String?,
        val accountId: String?,
        val clientId: String? = null,
        val tenantId: String? = null,
    ) {
        override fun toString(): String =
            "Session(token=${if (token.isNullOrBlank()) "none" else "***"}, cookie=${if (cookie.isNullOrBlank()) "none" else "***"}, deviceSession=${deviceSession?.let { "set" } ?: "none"}, accountId=${accountId?.let { "set" } ?: "none"})"
    }

    sealed interface LoginParse {
        data class Success(val session: Session) : LoginParse
        data class Rejected(val description: String?) : LoginParse
        data class ProtocolError(val description: String) : LoginParse
    }

    data class LiveChannel(
        val id: String,
        val name: String,
        val number: Int?,
        val epgId: String?,
        val logoUrl: String?,
        val order: Int,
        val radio: Boolean,
        val assetId: String?,
        val catchupDays: Int = 0,
    )

    data class EpgEntry(
        val id: String,
        val channelId: String,
        val title: String,
        val description: String?,
        val startMs: Long,
        val endMs: Long,
        val assetId: String?,
    )

    sealed interface Playback {
        class Clear(
            val url: String,
            val streamHeaders: Map<String, String> = emptyMap(),
        ) : Playback {
            override fun toString(): String = "Clear(url=**redacted**, headers=${streamHeaders.keys})"
        }

        class Widevine(
            val url: String,
            val licenseUrl: String,
            val streamHeaders: Map<String, String> = emptyMap(),
            val licenseHeaders: Map<String, String> = emptyMap(),
        ) : Playback {
            override fun toString(): String =
                "Widevine(url=**redacted**, licenseUrl=**redacted**, streamHeaders=${streamHeaders.keys}, licenseHeaders=${licenseHeaders.keys})"
        }

        data class UnsupportedProtected(val reason: String) : Playback
        data class Error(val reason: String) : Playback
    }

    fun buildLoginBody(
        subscriptionNumber: String,
        pin: String,
        deviceId: String,
        flavor: LoginFlavor,
    ): String {
        val device = when (flavor) {
            LoginFlavor.CURRENT_ANDROID_TV -> JSONObject()
                .put("deviceId", deviceId)
                .put("deviceIdType", "DEVICEID")
                .put("deviceType", "ANDROIDTV")
                .put("vendor", "Android")
                .put("model", "Android TV")
                .put("deviceFirmVersion", android.os.Build.VERSION.RELEASE ?: "")
                .put("appVersion", "MyOwnTV")
            LoginFlavor.LEGACY_PCTV -> JSONObject()
                .put("deviceId", deviceId)
                .put("accountDeviceIdType", "DEVICEID")
                .put("deviceType", "PCTV")
                .put("vendor", "Android")
                .put("model", "Android TV")
                .put("deviceFirmVersion", android.os.Build.VERSION.RELEASE ?: "")
                .put("appVersion", "MyOwnTV")
        }
        val credentials = JSONObject()
            .put("username", subscriptionNumber.trim())
            .put("password", pin)
            .put("remember", "Y")
            .put(if (flavor == LoginFlavor.CURRENT_ANDROID_TV) "deviceInfo" else "deviceRegistrationData", device)
        return JSONObject().put("credentialsStdAuth", credentials).toString()
    }

    fun loginUrl(root: String): String = join(root, "USER/SESSIONS/")

    fun liveChannelsUrl(root: String): String =
        join(root, "TRAY/LIVECHANNELS?orderBy=orderId&sortOrder=asc&from=0&to=999&dfilter_channels=subscription")

    fun epgUrl(root: String, startMs: Long, endMs: Long, channelIds: Collection<String>): String {
        val ids = channelIds.filter { it.isNotBlank() }.joinToString(",")
        return join(
            root,
            "TRAY/EPG?filter_startTime=$startMs&filter_endTime=$endMs&from=0&to=9999" +
                if (ids.isBlank()) "" else "&filter_channelIds=${encode(ids)}",
        )
    }

    fun livePlaybackUrl(root: String, channelId: String, assetId: String?, deviceId: String, nowMs: Long): String {
        val contentId = assetId?.takeIf { it.isNotBlank() } ?: channelId
        return join(
            root,
            "CONTENT/VIDEOURL/LIVE/${encodePath(channelId)}/${encodePath(contentId)}/?deviceId=${encode(deviceId)}&profile=G02&time=$nowMs",
        )
    }

    fun parseLoginResponse(json: String, setCookie: String?): LoginParse = runCatching {
        val root = JSONObject(json)
        val resultCode = root.optString("resultCode")
        if (!isOk(resultCode)) {
            return@runCatching LoginParse.Rejected(firstString(root, "errorDescription", "description", "message"))
        }
        val scope = root.optJSONObject("resultObj") ?: root
        val token = firstStringRecursive(scope, setOf("token", "sessionToken", "accessToken"))
        val cookie = firstStringRecursive(scope, setOf("cookie", "sessionCookie"))?.takeIf { it.isNotBlank() }
            ?: setCookie?.takeIf { it.isNotBlank() }
        val deviceSession = firstStringRecursive(scope, setOf("deviceSession", "devicesession", "deviceSessionId"))
        val accountId = firstStringRecursive(scope, setOf("accountId", "customerId"))
        val clientId = firstStringRecursive(scope, setOf("clientId"))
        val tenantId = firstStringRecursive(scope, setOf("tenantId"))
        if (token.isNullOrBlank() && cookie.isNullOrBlank() && deviceSession.isNullOrBlank()) {
            LoginParse.ProtocolError("Login succeeded without usable session material")
        } else {
            LoginParse.Success(Session(token, cookie, deviceSession, accountId, clientId, tenantId))
        }
    }.getOrElse { LoginParse.ProtocolError(it.message ?: "Invalid login response") }

    fun parseLiveChannels(json: String): List<LiveChannel> = runCatching {
        val root = JSONObject(json)
        if (!isOk(root.optString("resultCode"))) return@runCatching emptyList()
        val objects = flattenObjects(root.opt("resultObj") ?: root)
        val out = LinkedHashMap<String, LiveChannel>()
        for (obj in objects) {
            val id = firstString(obj, "channelId", "id", "externalChannelId")?.takeIf { it.isNotBlank() } ?: continue
            val metadata = obj.optJSONObject("metadata")
            val name = firstString(obj, "channelName", "name", "title")
                ?: metadata?.let { firstString(it, "channelName", "name", "title") }
                ?: continue
            val mediaType = (firstString(obj, "mediaType", "contentType", "type")
                ?: metadata?.let { firstString(it, "mediaType", "contentType", "type") }).orEmpty()
            val radio = obj.optBoolean("radio", false) || mediaType.contains("radio", ignoreCase = true)
            val number = firstInt(obj, "channelNumber", "lcn", "number", "orderId")
            val order = firstInt(obj, "orderId", "channelNumber", "number") ?: number ?: out.size
            val epgId = firstString(obj, "externalChannelId", "epgChannelId", "xmltvId")
                ?: metadata?.let { firstString(it, "externalChannelId", "epgChannelId", "xmltvId") }
            val logo = firstString(obj, "logoUrl", "pictureUrl", "imageUrl")
                ?: metadata?.let { firstString(it, "logoUrl", "pictureUrl", "imageUrl") }
            val asset = firstString(obj, "assetId", "contentId", "programId")
            val catchupDays = firstInt(obj, "catchupDays", "replayDays", "archiveDays") ?: 0
            out[id] = LiveChannel(id, name, number, epgId, logo, order, radio, asset, catchupDays)
        }
        out.values.toList().sortedWith(compareBy<LiveChannel> { it.order }.thenBy { it.name })
    }.getOrDefault(emptyList())

    fun parseEpg(json: String): List<EpgEntry> = runCatching {
        val root = JSONObject(json)
        if (!isOk(root.optString("resultCode"))) return@runCatching emptyList()
        val objects = flattenObjects(root.opt("resultObj") ?: root)
        val out = LinkedHashMap<String, EpgEntry>()
        for (obj in objects) {
            val metadata = obj.optJSONObject("metadata")
            val channelObj = obj.optJSONObject("channel")
            val channelId = firstString(obj, "channelId", "externalChannelId")
                ?: channelObj?.let { firstString(it, "channelId", "externalChannelId", "id") }
                ?: metadata?.let { firstString(it, "channelId", "externalChannelId") }
                ?: continue
            val start = firstLong(obj, "airingStartTime", "startTime", "start")
                ?: metadata?.let { firstLong(it, "airingStartTime", "startTime", "start") }
                ?: continue
            val end = firstLong(obj, "airingEndTime", "endTime", "stop", "end")
                ?: metadata?.let { firstLong(it, "airingEndTime", "endTime", "stop", "end") }
                ?: continue
            val title = firstString(obj, "title", "programTitle", "name")
                ?: metadata?.let { firstString(it, "title", "programTitle", "name") }
                ?: continue
            val id = firstString(obj, "id", "programId", "assetId") ?: "$channelId:$start"
            val description = firstString(obj, "longDescription", "shortDescription", "description")
                ?: metadata?.let { firstString(it, "longDescription", "shortDescription", "description") }
            val assetId = firstString(obj, "assetId", "programId", "contentId")
            out[id] = EpgEntry(id, channelId, title, description, normalizeEpoch(start), normalizeEpoch(end), assetId)
        }
        out.values.toList().sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    fun parsePlaybackResponse(json: String): Playback = runCatching {
        val root = JSONObject(json)
        if (!isOk(root.optString("resultCode"))) {
            return@runCatching Playback.Error(firstString(root, "errorDescription", "message") ?: "Playback rejected")
        }
        val scope = root.optJSONObject("resultObj") ?: root
        val url = firstStringRecursive(scope, setOf("videoUrl", "streamingUrl", "manifestUrl", "playUrl", "url"))
            ?: return@runCatching Playback.Error("No playback URL returned")
        val license = firstStringRecursive(scope, setOf("drmLicenseUrl", "licenseUrl", "widevineLicenseUrl"))
        val drmType = firstStringRecursive(scope, setOf("drmType", "drmSystem", "protectionType")).orEmpty()
        val protected = license != null || drmType.isNotBlank() || scope.toString().contains("widevine", ignoreCase = true)
        if (!license.isNullOrBlank()) {
            Playback.Widevine(url = url, licenseUrl = license)
        } else if (protected) {
            Playback.UnsupportedProtected("Provider returned protected playback without a standard Widevine license URL")
        } else {
            Playback.Clear(url)
        }
    }.getOrElse { Playback.Error(it.message ?: "Invalid playback response") }

    /** Pull an AVS API root from EAR without depending on its exact wrapper schema. */
    fun parseDiscoveredApiRoot(json: String): String? = runCatching {
        val root = JSONObject(json)
        flattenStrings(root)
            .firstOrNull { value -> value.startsWith("https://") && (value.contains("api-avs") || value.contains("/101/")) }
            ?.let(::normalizeRoot)
    }.getOrNull()

    fun normalizeRoot(root: String): String {
        var out = root.trim().trimEnd('/')
        if (!out.endsWith("/kpn") && out.contains("/nld")) out += "/kpn"
        return out
    }

    private fun join(root: String, path: String): String = "${normalizeRoot(root)}/${path.trimStart('/')}"
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun encodePath(value: String): String = encode(value).replace("+", "%20")
    private fun isOk(code: String?): Boolean = code.isNullOrBlank() || code.equals("OK", true) || code.equals("SUCCESS", true) || code == "0"

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is Number, is Boolean -> return value.toString()
            }
        }
        return null
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? =
        firstString(obj, *keys)?.toIntOrNull()

    private fun firstLong(obj: JSONObject, vararg keys: String): Long? =
        firstString(obj, *keys)?.toLongOrNull()

    private fun normalizeEpoch(value: Long): Long = if (value in 1..9_999_999_999L) value * 1000L else value

    private fun firstStringRecursive(value: Any?, keys: Set<String>): String? {
        when (value) {
            is JSONObject -> {
                for (key in keys) {
                    val direct = value.opt(key)
                    if (direct is String && direct.isNotBlank()) return direct
                    if (direct is Number || direct is Boolean) return direct.toString()
                    if (direct is JSONObject) {
                        firstString(direct, "id", "value", "token")?.let { return it }
                    }
                }
                val names = value.keys()
                while (names.hasNext()) {
                    firstStringRecursive(value.opt(names.next()), keys)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) firstStringRecursive(value.opt(i), keys)?.let { return it }
        }
        return null
    }

    private fun flattenObjects(value: Any?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    out += node
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return out
    }

    private fun flattenStrings(value: Any?): List<String> {
        val out = ArrayList<String>()
        fun visit(node: Any?) {
            when (node) {
                is String -> out += node
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return out
    }
}
