package tv.own.owntv.provider.solcon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for integration points that can compile independently while being unreachable.
 * These deliberately inspect the app wiring, not provider helper classes in isolation.
 */
class SolconEndToEndWiringTest {
    private fun source(path: String): String =
        File(System.getProperty("user.dir")).resolve(path).readText()

    @Test
    fun `settings and add source both reach the Solcon account screen`() {
        val settings = source("src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt")
        val chooser = source("src/main/java/tv/own/owntv/features/setup/AddSourceChooserScreen.kt")
        val wizard = source("src/main/java/tv/own/owntv/features/setup/SetupWizard.kt")

        assertTrue(settings.contains("tabRowKey(SettingsTab.SOLCON)"))
        assertTrue(settings.contains("SettingsTab.SOLCON -> { SolconTvPlusAccountScreen"))
        assertTrue(chooser.contains("onSolcon: () -> Unit"))
        assertTrue(wizard.contains("Step.ADD_SOURCE_SOLCON -> SolconTvPlusAccountScreen"))
    }

    @Test
    fun `first successful Solcon sync can become the active OwnTV source`() {
        val repository = source("src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt")

        assertTrue(repository.contains("settings.setDefaultSource(sourceId)"))
    }

    @Test
    fun `live playback resolves TV plus references before selecting an engine`() {
        val live = source("src/main/java/tv/own/owntv/features/live/LiveViewModel.kt")

        assertTrue(live.contains("SolconTvPlusRepository"))
        assertTrue(live.contains("solconTvPlusRepository.resolveLive(channel)"))
    }

    @Test
    fun `preview and multiview also use the just in time Solcon resolver`() {
        val live = source("src/main/java/tv/own/owntv/features/live/LiveViewModel.kt")

        assertTrue(live.contains("playPreview(resolved)"))
        assertTrue(live.contains("tuneTile(engine, resolved, muted)"))
        assertTrue(live.contains("val playableChannel = resolveSolconPlayback(channel)"))
    }

    @Test
    fun `safe diagnostics are wired to account UI and playback failures`() {
        val repository = source("src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt")
        val accountVm = source("src/main/java/tv/own/owntv/features/settings/SolconTvPlusViewModel.kt")
        val account = source("src/main/java/tv/own/owntv/features/settings/SolconTvPlusAccountScreen.kt")
        val live = source("src/main/java/tv/own/owntv/features/live/LiveViewModel.kt")
        val shell = source("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt")

        assertTrue(repository.contains("diagnostics.recordSync("))
        assertTrue(repository.contains("diagnostics.recordPlaybackRoute("))
        assertTrue(accountVm.contains("val diagnostics"))
        assertTrue(account.contains("vm.diagnostics.collectAsStateWithLifecycle()"))
        assertTrue(live.contains("solconPlaybackError"))
        assertTrue(shell.contains("solconPlaybackError.collect"))
        assertFalse(live.contains("engineLog(resolved.reason)"))
    }
}
