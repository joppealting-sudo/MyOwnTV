package tv.own.owntv.provider.solcon

import java.io.File
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
}
