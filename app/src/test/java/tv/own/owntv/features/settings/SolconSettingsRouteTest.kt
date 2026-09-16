package tv.own.owntv.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.R

class SolconSettingsRouteTest {
    @Test
    fun `settings exposes Solcon as a real app destination`() {
        assertEquals("solcon_tvplus", SolconSettingsRoute.key)
        assertEquals(R.string.solcon_tvplus_title, SolconSettingsRoute.titleRes)
        assertEquals(R.string.solcon_tvplus_settings_description, SolconSettingsRoute.descriptionRes)
        assertTrue(SolconSettingsRoute.reachableFromSettings)
    }

    @Test
    fun `no source chooser exposes the same Solcon destination`() {
        assertTrue(SolconSettingsRoute.reachableFromSourceSetup)
    }
}
