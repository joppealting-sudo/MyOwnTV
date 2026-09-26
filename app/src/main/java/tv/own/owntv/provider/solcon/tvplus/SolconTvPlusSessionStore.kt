package tv.own.owntv.provider.solcon.tvplus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private storage for a Solcon TV+ device session.
 *
 * The subscription PIN is deliberately never accepted by this class and therefore cannot be persisted.
 * Session credentials are encrypted with an Android Keystore AES key. If that key becomes unavailable
 * (restore to another TV, lock-screen/key-store reset, etc.) the session fails closed and is cleared.
 */
class SolconTvPlusSessionStore(context: Context) {
    data class StoredSession(
        val apiRoot: String,
        val token: String?,
        val cookie: String?,
        val deviceSession: String?,
        val accountId: String?,
        val clientId: String?,
        val tenantId: String?,
    ) {
        override fun toString(): String =
            "StoredSession(apiRoot=$apiRoot, token=${if (token.isNullOrBlank()) "none" else "***"}, cookie=${if (cookie.isNullOrBlank()) "none" else "***"}, authenticated=true)"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    @Synchronized
    fun save(apiRoot: String, session: SolconTvPlusProtocol.Session) {
        val payload = org.json.JSONObject()
            .put("apiRoot", SolconTvPlusProtocol.normalizeRoot(apiRoot))
            .put("token", session.token ?: org.json.JSONObject.NULL)
            .put("cookie", session.cookie ?: org.json.JSONObject.NULL)
            .put("deviceSession", session.deviceSession ?: org.json.JSONObject.NULL)
            .put("accountId", session.accountId ?: org.json.JSONObject.NULL)
            .put("clientId", session.clientId ?: org.json.JSONObject.NULL)
            .put("tenantId", session.tenantId ?: org.json.JSONObject.NULL)
            .toString()
        prefs.edit().putString(KEY_SESSION, encrypt(payload)).apply()
    }

    @Synchronized
    fun load(): StoredSession? {
        val encrypted = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val obj = org.json.JSONObject(decrypt(encrypted))
            StoredSession(
                apiRoot = obj.getString("apiRoot"),
                token = obj.optNullable("token"),
                cookie = obj.optNullable("cookie"),
                deviceSession = obj.optNullable("deviceSession"),
                accountId = obj.optNullable("accountId"),
                clientId = obj.optNullable("clientId"),
                tenantId = obj.optNullable("tenantId"),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        // Keep the install's device id stable across logout/login; only the account session is removed.
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun isLoggedIn(): Boolean = load() != null

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val pieces = value.split('.', limit = 2)
        require(pieces.size == 2) { "Invalid encrypted session" }
        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun org.json.JSONObject.optNullable(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS = "solcon_tvplus_session_v1"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SESSION = "session"
        private const val KEY_ALIAS = "owntv.solcon.tvplus.session.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
