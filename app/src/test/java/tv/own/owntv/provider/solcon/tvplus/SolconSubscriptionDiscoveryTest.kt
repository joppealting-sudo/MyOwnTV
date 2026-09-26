package tv.own.owntv.provider.solcon.tvplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SolconSubscriptionDiscoveryTest {
    @Test
    fun `subscription discovery sends trimmed TAN`() {
        assertEquals(
            "${SolconTvPlusProtocol.DISCOVERY_URL}&tan=12345678901234",
            SolconTvPlusProtocol.subscriptionDiscoveryUrl(" 12345678901234 "),
        )
    }

    @Test
    fun `EAR host response builds tenant and platform specific AVS roots`() {
        val discovered = SolconTvPlusProtocol.parseDiscoveryEndpoint(
            """{"result":{"url":"api-avs99.tv.prod.itvavs.prod.aws.kpn.com"}}""",
        )
        assertNotNull(discovered)
        requireNotNull(discovered)

        assertEquals(
            "https://api-avs99.tv.prod.itvavs.prod.aws.kpn.com/000002/1.5/A/nld/pctv/kpn",
            SolconTvPlusProtocol.apiRoot(discovered, SolconTvPlusProtocol.LoginFlavor.LEGACY_PCTV),
        )
        assertEquals(
            "https://api-avs99.tv.prod.itvavs.prod.aws.kpn.com/000002/1.5/A/nld/androidtv/kpn",
            SolconTvPlusProtocol.apiRoot(discovered, SolconTvPlusProtocol.LoginFlavor.CURRENT_ANDROID_TV),
        )
    }
}
