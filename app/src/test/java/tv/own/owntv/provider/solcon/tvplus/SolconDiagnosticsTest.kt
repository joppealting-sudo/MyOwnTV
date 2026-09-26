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

    @Test
    fun `the last failure survives recreation and clears on success`() {
        val backing = mutableMapOf<String, String>()
        val diagnostics = SolconDiagnostics.inMemory(backing)

        diagnostics.recordError(
            SolconDiagnostics.ErrorCategory.LOGIN_REJECTED,
            SolconDiagnostics.FailureDetail(SolconDiagnostics.Step.SIGN_IN, httpStatus = 403, providerCode = "UNAUTHORIZED_CLIENT"),
        )
        val restored = SolconDiagnostics.inMemory(backing).state.value
        assertEquals(SolconDiagnostics.ErrorCategory.LOGIN_REJECTED, restored.lastError)
        assertEquals(
            SolconDiagnostics.FailureDetail(SolconDiagnostics.Step.SIGN_IN, 403, "UNAUTHORIZED_CLIENT"),
            restored.lastFailure,
        )

        diagnostics.recordSync(tvChannels = 1, radioChannels = 0, programmes = 0, epgComplete = true)
        assertNull(diagnostics.state.value.lastFailure)
        assertNull(SolconDiagnostics.inMemory(backing).state.value.lastFailure)
    }

    @Test
    fun `a provider code is kept only when it is a plain identifier`() {
        fun code(raw: String?) = SolconDiagnostics.FailureDetail(SolconDiagnostics.Step.PLAYBACK, providerCode = raw).providerCode

        assertEquals("MAX_DEVICES", code("MAX_DEVICES"))
        assertEquals("err.403-2", code(" err.403-2 "))
        // Free text, URLs and anything token-shaped with odd characters never reach the screen.
        assertNull(code("Wrong PIN for account 1234567"))
        assertNull(code("https://cdn.test/live.mpd?token=secret"))
        assertNull(code("a".repeat(49)))
        assertNull(code(null))
    }
}
