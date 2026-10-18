package app.hatch.engine

import app.hatch.persona.Persona
import app.hatch.persona.Presets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ENG-02: the store's initial desktopMode mirrors the persona for the default preset and for real-device. */
class BrowserStateFactoryTest {
    @Test
    fun `default preset seeds desktopMode = true, real-device seeds false`() {
        val default = Presets.default()
        assertTrue(default.identity.desktop, "the default preset is a desktop identity")
        val seeded = BrowserStateFactory.initial(default)
        assertTrue(seeded.desktopMode)
        assertTrue(seeded.tabs.isEmpty())

        val real = Persona(Presets.realDevice(), default.region)
        assertFalse(BrowserStateFactory.initial(real).desktopMode)
        for (identity in Presets.identities()) {
            assertEquals(identity.desktop, BrowserStateFactory.initial(Persona(identity, default.region)).desktopMode, identity.id)
        }
    }
}
