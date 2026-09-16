package tv.own.owntv.provider.solcon.tvplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolconDiagnosticsTest {
    @Test
    fun `logout preserves safe catalog diagnostics across recreation`() {
        val backing = mutableMapOf<String, String>()
        val diagnostics = SolconDiagnostics.inMemory(backing)

        diagnostics.setSessionAuthenticated(true)
        diagnostics.recordDiscovery(SolconDiagnostics.DiscoveryResult.DISCOVERED)
        diagnostics.recordSync(
            tvChannels = 83,
            radioChannels = 41,
            programmes = 1_204,
            epgComplete = true,
            atMs = 1_700_000_000_000L,
        )
        diagnostics.recordPlaybackRoute(SolconDiagnostics.PlaybackRoute.WIDEVINE)
        diagnostics.setSessionAuthenticated(false)

        val restored = SolconDiagnostics.inMemory(backing).state.value
        assertFalse(restored.sessionAuthenticated)
        assertEquals(SolconDiagnostics.DiscoveryResult.DISCOVERED, restored.lastDiscovery)
        assertEquals(1_700_000_000_000L, restored.lastSyncAtMs)
        assertEquals(83, restored.tvChannels)
        assertEquals(41, restored.radioChannels)
        assertEquals(1_204, restored.programmes)
        assertEquals(true, restored.epgComplete)
        assertEquals(SolconDiagnostics.PlaybackRoute.WIDEVINE, restored.lastPlaybackRoute)
        assertNull(restored.lastError)
    }

    @Test
    fun `diagnostic errors are typed and never accept provider detail strings`() {
        val diagnostics = SolconDiagnostics.inMemory()

        diagnostics.recordError(SolconDiagnostics.ErrorCategory.PROTECTED_UNSUPPORTED)

        assertEquals(
            SolconDiagnostics.ErrorCategory.PROTECTED_UNSUPPORTED,
            diagnostics.state.value.lastError,
        )
        assertTrue(
            SolconDiagnostics.Snapshot::class.java.declaredFields.none {
                it.type == String::class.java
            },
        )
    }
}
