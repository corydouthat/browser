package app.hatch.sitepermissions

import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissions.AutoplayStatus
import mozilla.components.concept.engine.permission.SitePermissions.Status
import mozilla.components.concept.engine.permission.SitePermissionsStorage.Permission
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SitePermissionRowsTest {
    private val sp = SitePermissions(origin = "https://maps.example.com:443", location = Status.ALLOWED, camera = Status.BLOCKED, savedAt = 1L)

    @Test
    fun `rows cover every permission once, in display order, with labels`() {
        val rows = SitePermissionRows.rows(sp)
        assertEquals(Permission.entries.size, rows.size)
        assertEquals(Permission.entries.toSet(), rows.map { it.permission }.toSet())
        // DF-10: the mockup's five rows first, in its order.
        assertEquals(
            listOf(Permission.CAMERA, Permission.MICROPHONE, Permission.LOCATION, Permission.NOTIFICATION, Permission.LOCAL_STORAGE),
            rows.take(5).map { it.permission },
        )
        assertEquals(listOf("Camera", "Microphone", "Location", "Notifications", "Persistent storage"), rows.take(5).map { it.label })
        assertEquals(Status.BLOCKED, rows.first().status)
        assertEquals(Status.ALLOWED, rows.first { it.permission == Permission.LOCATION }.status)
        assertTrue(rows.first { it.permission == Permission.AUTOPLAY_AUDIBLE }.autoplay)
        assertEquals(Status.BLOCKED, rows.first { it.permission == Permission.AUTOPLAY_AUDIBLE }.status)
    }

    @Test
    fun `decided hides ask rows and default autoplay values`() {
        assertEquals(listOf(Permission.CAMERA, Permission.LOCATION), SitePermissionRows.decided(sp).map { it.permission })
        val withAutoplay = sp.copy(autoplayAudible = AutoplayStatus.ALLOWED, autoplayInaudible = AutoplayStatus.BLOCKED)
        assertEquals(
            listOf(Permission.CAMERA, Permission.LOCATION, Permission.AUTOPLAY_AUDIBLE, Permission.AUTOPLAY_INAUDIBLE),
            SitePermissionRows.decided(withAutoplay).map { it.permission },
        )
    }

    @Test
    fun `withStatus writes the matching field, autoplay maps ask to blocked`() {
        Permission.entries.forEach { p ->
            val updated = SitePermissionRows.withStatus(sp, p, Status.ALLOWED)
            assertEquals(Status.ALLOWED, updated[p], p.name)
        }
        val blocked = SitePermissionRows.withStatus(sp, Permission.NOTIFICATION, Status.BLOCKED)
        assertEquals(Status.BLOCKED, blocked.notification)
        assertEquals(Status.ALLOWED, blocked.location, "other fields untouched")
        val ask = SitePermissionRows.withStatus(sp, Permission.LOCATION, Status.NO_DECISION)
        assertEquals(Status.NO_DECISION, ask.location)
        assertEquals(AutoplayStatus.BLOCKED, SitePermissionRows.withStatus(sp.copy(autoplayInaudible = AutoplayStatus.ALLOWED), Permission.AUTOPLAY_INAUDIBLE, Status.NO_DECISION).autoplayInaudible)
    }

    @Test
    fun `display origin drops default ports only, sorting is by host`() {
        assertEquals("https://maps.example.com", SitePermissionRows.displayOrigin("https://maps.example.com:443"))
        assertEquals("http://a.example", SitePermissionRows.displayOrigin("http://a.example:80"))
        assertEquals("https://a.example:8443", SitePermissionRows.displayOrigin("https://a.example:8443"))
        val sorted = SitePermissionRows.sorted(listOf(sp, SitePermissions(origin = "https://Alpha.example:443", savedAt = 1L), SitePermissions(origin = "http://beta.example:80", savedAt = 1L)))
        assertEquals(listOf("https://Alpha.example:443", "http://beta.example:80", "https://maps.example.com:443"), sorted.map { it.origin })
        assertEquals("Ask", SitePermissionRows.statusLabel(Status.NO_DECISION))
        assertEquals("Blocked", SitePermissionRows.statusLabel(Status.BLOCKED))
    }

    @Test
    fun `pill labels, options, summary and host for the redesigned screen`() {
        assertEquals("Allow", SitePermissionRows.pillLabel(Status.ALLOWED))
        assertEquals("Ask", SitePermissionRows.pillLabel(Status.NO_DECISION))
        assertEquals("Block", SitePermissionRows.pillLabel(Status.BLOCKED))
        val rows = SitePermissionRows.rows(sp)
        assertEquals(listOf(Status.ALLOWED, Status.NO_DECISION, Status.BLOCKED), SitePermissionRows.options(rows.first { it.permission == Permission.CAMERA }))
        assertEquals(listOf(Status.ALLOWED, Status.BLOCKED), SitePermissionRows.options(rows.first { it.permission == Permission.AUTOPLAY_AUDIBLE }))
        assertEquals("Camera blocked · +1 more", SitePermissionRows.summary(sp))
        assertEquals("Notifications blocked", SitePermissionRows.summary(SitePermissions(origin = "https://a.example:443", notification = Status.BLOCKED, savedAt = 1L)))
        assertEquals("Every permission set to Ask", SitePermissionRows.summary(SitePermissions(origin = "https://a.example:443", savedAt = 1L)))
        assertEquals("maps.example.com", SitePermissionRows.displayHost(sp.origin))
        assertEquals("http://a.example", SitePermissionRows.displayHost("http://a.example:80"))
    }
}
