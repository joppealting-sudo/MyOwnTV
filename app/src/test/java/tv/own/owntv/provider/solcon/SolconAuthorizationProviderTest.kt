package tv.own.owntv.provider.solcon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SolconAuthorizationProviderTest {
    @Test
    fun `default provider is explicitly not provisioned`() {
        val provider: SolconAuthorizationProvider = NotProvisionedSolconAuthorizationProvider

        assertEquals(SolconAuthorizationState.NotProvisioned, provider.state())
    }

    @Test
    fun `not provisioned state never claims protected playback is available`() {
        val state = NotProvisionedSolconAuthorizationProvider.state()

        assertFalse(state.canPlayProtectedContent)
    }
}
