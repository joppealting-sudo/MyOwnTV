package tv.own.owntv.provider.solcon.tvplus

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Subscription-scoped AVS endpoint discovery used before Solcon TV+ standard-auth login. */
internal data class SolconDiscoveryEndpoint(
    val host: String,
    val tenant: String = DEFAULT_SOLCON_TENANT,
)

internal fun SolconTvPlusProtocol.subscriptionDiscoveryUrl(subscriptionNumber: String): String {
    val tan = URLEncoder.encode(subscriptionNumber.trim(), StandardCharsets.UTF_8.name())
    return "$DISCOVERY_URL&tan=$tan"
}

internal fun SolconTvPlusProtocol.parseDiscoveryEndpoint(json: String): SolconDiscoveryEndpoint? = runCatching {
    val root = JSONObject(json)
    val rawEndpoint = findDiscoveryString(root, setOf("url", "host", "endpoint"))
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return@runCatching null

    val uri = URI(if (rawEndpoint.startsWith("http://") || rawEndpoint.startsWith("https://")) rawEndpoint else "https://$rawEndpoint")
    val host = uri.host
        ?.lowercase()
        ?.takeIf { SAFE_HOST.matches(it) && it.contains('.') }
        ?: return@runCatching null

    val tenantFromBody = findDiscoveryString(root, setOf("tenant", "tenantId"))
        ?.trim()
        ?.takeIf { SAFE_TENANT.matches(it) }
    val tenantFromPath = uri.path
        ?.split('/')
        ?.firstOrNull { it.isNotBlank() && SAFE_TENANT.matches(it) && it.any(Char::isDigit) }
    val tenant = tenantFromBody ?: tenantFromPath ?: DEFAULT_SOLCON_TENANT

    SolconDiscoveryEndpoint(host = host, tenant = tenant)
}.getOrNull()

internal fun SolconTvPlusProtocol.apiRoot(
    endpoint: SolconDiscoveryEndpoint,
    flavor: SolconTvPlusProtocol.LoginFlavor,
): String {
    val platform = when (flavor) {
        SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV -> "pctv"
        SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV -> "androidtv"
    }
    return "https://${endpoint.host}/${endpoint.tenant}/1.5/A/nld/$platform/kpn"
}

private fun findDiscoveryString(value: Any?, keys: Set<String>): String? {
    when (value) {
        is JSONObject -> {
            for (key in keys) {
                if (!value.has(key) || value.isNull(key)) continue
                when (val direct = value.opt(key)) {
                    is String -> if (direct.isNotBlank()) return direct
                    is Number, is Boolean -> return direct.toString()
                }
            }
            val names = value.keys()
            while (names.hasNext()) {
                findDiscoveryString(value.opt(names.next()), keys)?.let { return it }
            }
        }
        is JSONArray -> for (index in 0 until value.length()) {
            findDiscoveryString(value.opt(index), keys)?.let { return it }
        }
    }
    return null
}

private const val DEFAULT_SOLCON_TENANT = "000002"
private val SAFE_HOST = Regex("^[a-z0-9.-]+$")
private val SAFE_TENANT = Regex("^[A-Za-z0-9_-]+$")
