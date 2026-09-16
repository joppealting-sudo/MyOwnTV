package tv.own.owntv.provider.solcon.tvplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolconTvPlusProtocolTest {
    @Test
    fun `current android tv login body carries only entered credentials and local device identity`() {
        val body = SolconTvPlusProtocol.buildLoginBody(
            subscriptionNumber = "1234567890",
            pin = "4321",
            deviceId = "local-device-123",
            flavor = SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV,
        )

        assertTrue(body.contains("credentialsStdAuth"))
        assertTrue(body.contains("\"username\":\"1234567890\""))
        assertTrue(body.contains("\"password\":\"4321\""))
        assertTrue(body.contains("\"deviceId\":\"local-device-123\""))
        assertTrue(body.contains("\"deviceType\":\"ANDROIDTV\""))
        assertTrue(body.contains("\"deviceInfo\""))
        assertFalse(body.contains("clientSecret", ignoreCase = true))
        assertFalse(body.contains("privateKey", ignoreCase = true))
    }

    @Test
    fun `legacy body remains available only as compatibility shape`() {
        val body = SolconTvPlusProtocol.buildLoginBody(
            "1234567890", "4321", "local-device-123",
            SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV,
        )

        assertTrue(body.contains("deviceRegistrationData"))
        assertTrue(body.contains("accountDeviceIdType"))
        assertTrue(body.contains("\"deviceType\":\"PCTV\""))
    }

    @Test
    fun `endpoint builders use bounded account scoped paths`() {
        val root = "https://example.test/101/1.5/A/nld/kpn/"
        assertEquals(
            "https://example.test/101/1.5/A/nld/kpn/USER/SESSIONS/",
            SolconTvPlusProtocol.loginUrl(root),
        )
        assertTrue(SolconTvPlusProtocol.liveChannelsUrl(root).contains("TRAY/LIVECHANNELS"))
        assertTrue(SolconTvPlusProtocol.liveChannelsUrl(root).contains("dfilter_channels=subscription"))
        assertTrue(SolconTvPlusProtocol.livePlaybackUrl(root, "11", "22", "dev-1", 1000L)
            .contains("CONTENT/VIDEOURL/LIVE/11/22/"))
    }

    @Test
    fun `login parser accepts usable session and never includes secrets in diagnostics`() {
        val json = """{
          "resultCode":"OK",
          "resultObj":{
            "token":"secret-token",
            "cookie":"secret-cookie",
            "deviceSession":"session-1",
            "accountId":"account-1"
          }
        }""".trimIndent()

        val session = SolconTvPlusProtocol.parseLoginResponse(json, setCookie = null)
        require(session is SolconTvPlusProtocol.LoginParse.Success)
        assertEquals("secret-token", session.session.token)
        assertEquals("secret-cookie", session.session.cookie)
        assertFalse(session.session.toString().contains("secret-token"))
        assertFalse(session.session.toString().contains("secret-cookie"))
    }

    @Test
    fun `failed provider result keeps provider error code`() {
        val parsed = SolconTvPlusProtocol.parseLoginResponse(
            """{"resultCode":"NOK","errorCode":"INVALID_LOGIN_CREDENTIALS","errorDescription":"Wrong credentials"}""",
            setCookie = null,
        )
        require(parsed is SolconTvPlusProtocol.LoginParse.Rejected)
        assertEquals("INVALID_LOGIN_CREDENTIALS", parsed.code)
    }

    @Test
    fun `only explicit credential errors classify as invalid credentials`() {
        assertEquals(
            SolconTvPlusProtocol.LoginRejectionKind.INVALID_CREDENTIALS,
            SolconTvPlusProtocol.classifyLoginRejection("INVALID_LOGIN_CREDENTIALS", "Wrong credentials"),
        )
        assertEquals(
            SolconTvPlusProtocol.LoginRejectionKind.ACCOUNT_BLOCKED,
            SolconTvPlusProtocol.classifyLoginRejection("BLOCKED_USER", "Account blocked"),
        )
        assertEquals(
            SolconTvPlusProtocol.LoginRejectionKind.DEVICE_LIMIT,
            SolconTvPlusProtocol.classifyLoginRejection("MAX_DEVICES", "Maximum number of devices"),
        )
        assertEquals(
            SolconTvPlusProtocol.LoginRejectionKind.OTHER,
            SolconTvPlusProtocol.classifyLoginRejection("UNAUTHORIZED_CLIENT", "Client is not allowed"),
        )
        assertEquals(
            SolconTvPlusProtocol.LoginRejectionKind.OTHER,
            SolconTvPlusProtocol.classifyLoginRejection(null, null),
        )
    }

    @Test
    fun `catalog parser keeps channel number radio and epg identity`() {
        val json = """{
          "resultCode":"OK",
          "resultObj":{"containers":[
            {"id":"101","channelId":"101","channelName":"NPO 1","externalChannelId":"npo1.nl","orderId":1,"channelNumber":1,"logoUrl":"https://img/1.png"},
            {"id":"901","channelId":"901","channelName":"Radio 1","externalChannelId":"radio1","orderId":901,"channelNumber":801,"mediaType":"RADIO"}
          ]}
        }""".trimIndent()

        val channels = SolconTvPlusProtocol.parseLiveChannels(json)
        assertEquals(2, channels.size)
        assertEquals(1, channels[0].number)
        assertEquals("npo1.nl", channels[0].epgId)
        assertFalse(channels[0].radio)
        assertTrue(channels[1].radio)
    }

    @Test
    fun `playback parser supports clear and standard widevine without exposing auth`() {
        val clear = SolconTvPlusProtocol.parsePlaybackResponse(
            """{"resultCode":"OK","resultObj":{"videoUrl":"https://cdn/live.m3u8?token=signed"}}""",
        )
        assertTrue(clear is SolconTvPlusProtocol.Playback.Clear)

        val drm = SolconTvPlusProtocol.parsePlaybackResponse(
            """{"resultCode":"OK","resultObj":{"videoUrl":"https://cdn/live.mpd?token=signed","drmLicenseUrl":"https://license/wv","drmType":"widevine"}}""",
        )
        assertTrue(drm is SolconTvPlusProtocol.Playback.Widevine)
        assertFalse(drm.toString().contains("token=signed"))
    }
}
