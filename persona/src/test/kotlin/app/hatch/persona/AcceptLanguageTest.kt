package app.hatch.persona

import kotlin.test.Test
import kotlin.test.assertEquals

/** Table reproduced from rust_prepare_accept_languages (netwerk/base/rust-helper/src/lib.rs, FIREFOX_158_0_RELEASE). */
class AcceptLanguageTest {
    @Test
    fun `header table`() {
        val table = listOf(
            "" to "",
            "en" to "en",
            "en, ja" to "en,ja;q=0.9",
            "en, ja, fr_CA" to "en,ja;q=0.9,fr_ca;q=0.8",
            "en-US, en" to "en-US,en;q=0.9",
            "en-us,en" to "en-US,en;q=0.9",
            "EN-us ,\ten" to "en-US,en;q=0.9",
            "en-GB, en" to "en-GB,en;q=0.9",
            "de-DE, de, en" to "de-DE,de;q=0.9,en;q=0.8",
            "ja-JP, ja, en" to "ja-JP,ja;q=0.9,en;q=0.8",
            "en-US;q=0.8, en;q=0.3" to "en-US,en;q=0.9",
            "en, , ja" to "en,ja;q=0.9",
            ",en" to "en",
            "zh-hant-tw" to "zh-Hant-TW",
            "man-nkoo-gn" to "man-Nkoo-GN",
            "x-pig-latin" to "x-pig-latin",
            "AZ-ARAB" to "az-Arab",
            "es-419" to "es-419",
            "i-klingon-us" to "i-klingon-US",
            "*" to "*",
            "a,b,c,d,e,f,g,h,i,j,k,l" to "a,b;q=0.9,c;q=0.8,d;q=0.7,e;q=0.6,f;q=0.5,g;q=0.4,h;q=0.3,i;q=0.2,j;q=0.1,k;q=0.1,l;q=0.1",
        )
        for ((input, expected) in table) {
            assertEquals(expected, GeckoAcceptLanguage.header(input), "input=<$input>")
        }
    }

    @Test
    fun `navigator languages and pref value`() {
        assertEquals(listOf("en-US", "en"), GeckoAcceptLanguage.navigatorLanguages("en-us, en"))
        assertEquals(emptyList(), GeckoAcceptLanguage.navigatorLanguages(""))
        assertEquals("ja-JP, ja, en", GeckoAcceptLanguage.prefValue(listOf("ja-JP", "ja", "en")))
    }

    @Test
    fun `expectedReport uses the Gecko header for preset regions`() {
        val p = Persona(Presets.identity("win-firefox")!!, Presets.region("us-east")!!)
        assertEquals("en-US,en;q=0.9", p.expectedReport().acceptLanguage)
        val de = Persona(Presets.identity("win-firefox")!!, Presets.region("de")!!)
        assertEquals("de-DE,de;q=0.9,en;q=0.8", de.expectedReport().acceptLanguage)
    }
}
