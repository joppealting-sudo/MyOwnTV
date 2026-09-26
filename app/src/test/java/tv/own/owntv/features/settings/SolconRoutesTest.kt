package tv.own.owntv.features.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Solcon TV+ account screen once shipped compiled but with no way to reach it. These are its ways in —
 * Settings, setup's Add source, and Manage sources — checked in the source, as the settings search test
 * does, because each sits inside a composable that needs the whole app to run.
 */
class SolconRoutesTest {
    private fun source(path: String): String {
        val file = File("src/main/java/tv/own/owntv/$path")
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `settings has a Solcon row with the account screen behind it`() {
        val settings = source("features/shell/components/SettingsScreen.kt")
        assertTrue("no SOLCON settings tab", Regex("""private enum class SettingsTab \{[^}]*\bSOLCON\b""").containsMatchIn(settings))
        assertTrue("no Settings row opens the Solcon tab", settings.contains("onClick = { open(SettingsTab.SOLCON) }"))
        assertTrue("the Solcon tab shows no screen", Regex("""SettingsTab\.SOLCON -> \{\s*SolconTvPlusAccountScreen\(""").containsMatchIn(settings))
    }

    @Test
    fun `setup offers Solcon TV+ as a source and signs in on the account screen`() {
        assertTrue(source("features/setup/AddSourceChooserScreen.kt").contains("onClick = onSolcon"))
        val wizard = source("features/setup/SetupWizard.kt")
        assertTrue(wizard.contains("onSolcon = { step = Step.ADD_SOURCE_SOLCON }"))
        assertTrue(Regex("""Step\.ADD_SOURCE_SOLCON -> SolconTvPlusAccountScreen\(""").containsMatchIn(wizard))
    }

    @Test
    fun `manage sources adds a Solcon playlist and manages it on the account screen`() {
        val manage = source("features/settings/ManageSourcesScreen.kt")
        assertTrue(manage.contains("onSolcon = { addMode = AddMode.SOLCON }"))
        assertTrue(Regex("""AddMode\.SOLCON -> SolconTvPlusAccountScreen\(""").containsMatchIn(manage))
        assertTrue(Regex("""if \(managingSolcon\) \{\s*SolconTvPlusAccountScreen\(""").containsMatchIn(manage))
    }
}
