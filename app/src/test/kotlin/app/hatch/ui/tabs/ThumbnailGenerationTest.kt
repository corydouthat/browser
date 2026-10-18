package app.hatch.ui.tabs

import mozilla.components.concept.base.images.ImageLoadRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** ENG-06 / UXP-14: a completed capture changes the load key of every card showing that tab. */
class ThumbnailGenerationTest {
    @Test
    fun `the key changes with the generation and with the request`() {
        val r = ImageLoadRequest("tab", 400, false)
        assertEquals(ThumbnailGeneration.thumbnailKey(r, 1), ThumbnailGeneration.thumbnailKey(ImageLoadRequest("tab", 400, false), 1))
        assertNotEquals(ThumbnailGeneration.thumbnailKey(r, 1), ThumbnailGeneration.thumbnailKey(r, 2))
        assertNotEquals(ThumbnailGeneration.thumbnailKey(r, 1), ThumbnailGeneration.thumbnailKey(ImageLoadRequest("tab", 500, false), 1))
    }

    @Test
    fun `bump increments the generation`() {
        val g = ThumbnailGeneration()
        assertEquals(0L, g.generation.value)
        g.bump(); g.bump()
        assertEquals(2L, g.generation.value)
    }
}
