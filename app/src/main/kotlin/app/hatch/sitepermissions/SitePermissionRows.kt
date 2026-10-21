package app.hatch.sitepermissions

import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissions.AutoplayStatus
import mozilla.components.concept.engine.permission.SitePermissions.Status
import mozilla.components.concept.engine.permission.SitePermissionsStorage.Permission

/**
 * Pure helpers behind `ui/SitePermissionsScreen.kt` (JVM-tested): the per-permission rows of one
 * `SitePermissions` entry, status labels and the copy-with-status used by the Allow / Block / Ask chips.
 *
 * `SitePermissions` (`concept/engine/permission/SitePermissions.kt` at FIREFOX_158_0_RELEASE) carries twelve
 * decisions keyed by `SitePermissionsStorage.Permission`; `Status.NO_DECISION` means "ask again" and the two
 * autoplay fields are `AutoplayStatus` (no "ask" state; defaults BLOCKED / ALLOWED).
 */
object SitePermissionRows {
    data class Row(val permission: Permission, val label: String, val status: Status, val autoplay: Boolean)

    /**
     * Display order: the mockup's five rows first (Camera, Microphone, Location, Notifications, Storage; DF-10), then
     * the rest of Gecko's permissions. The mockup's "Storage" row is `LOCAL_STORAGE`, labelled "Persistent storage"
     * because that is what the permission grants (`navigator.storage.persist()`); a bare "Storage" reads like cookies.
     */
    val ORDER: List<Permission> = listOf(
        Permission.CAMERA, Permission.MICROPHONE, Permission.LOCATION, Permission.NOTIFICATION, Permission.LOCAL_STORAGE,
        Permission.AUTOPLAY_AUDIBLE, Permission.AUTOPLAY_INAUDIBLE, Permission.MEDIA_KEY_SYSTEM_ACCESS, Permission.STORAGE_ACCESS, Permission.LOCAL_DEVICE_ACCESS,
        Permission.LOCAL_NETWORK_ACCESS, Permission.BLUETOOTH,
    )

    fun label(p: Permission): String = when (p) {
        Permission.LOCATION -> "Location"
        Permission.NOTIFICATION -> "Notifications"
        Permission.CAMERA -> "Camera"
        Permission.MICROPHONE -> "Microphone"
        Permission.AUTOPLAY_AUDIBLE -> "Autoplay with sound"
        Permission.AUTOPLAY_INAUDIBLE -> "Autoplay muted"
        Permission.LOCAL_STORAGE -> "Persistent storage"
        Permission.MEDIA_KEY_SYSTEM_ACCESS -> "DRM (protected media)"
        Permission.STORAGE_ACCESS -> "Cross-site cookies (storage access)"
        Permission.LOCAL_DEVICE_ACCESS -> "Local device access"
        Permission.LOCAL_NETWORK_ACCESS -> "Local network access"
        Permission.BLUETOOTH -> "Bluetooth"
    }

    fun isAutoplay(p: Permission): Boolean = p == Permission.AUTOPLAY_AUDIBLE || p == Permission.AUTOPLAY_INAUDIBLE

    fun statusLabel(s: Status): String = when (s) {
        Status.ALLOWED -> "Allowed"
        Status.BLOCKED -> "Blocked"
        Status.NO_DECISION -> "Ask"
    }

    /** The value pill of a permission row (mockup "Site permissions"): Allow / Ask / Block. */
    fun pillLabel(s: Status): String = when (s) {
        Status.ALLOWED -> "Allow"
        Status.BLOCKED -> "Block"
        Status.NO_DECISION -> "Ask"
    }

    /** The values a row's pill offers: autoplay has no "ask" state. */
    fun options(row: Row): List<Status> =
        if (row.autoplay) listOf(Status.ALLOWED, Status.BLOCKED) else listOf(Status.ALLOWED, Status.NO_DECISION, Status.BLOCKED)

    /**
     * The one-line summary of a collapsed origin ("Location allowed", "Notifications blocked · +2 more"): the first
     * [decided] row with its status in words, then how many more decisions there are.
     */
    fun summary(sp: SitePermissions): String {
        val decided = decided(sp)
        val first = decided.firstOrNull() ?: return "Every permission set to Ask"
        val head = "${first.label} ${statusLabel(first.status).lowercase()}"
        return if (decided.size > 1) "$head · +${decided.size - 1} more" else head
    }

    /** The origin header's host: [displayOrigin] without `https://` (other schemes and ports stay visible). */
    fun displayHost(origin: String): String = displayOrigin(origin).removePrefix("https://")

    /** The twelve rows, in [ORDER]. */
    fun rows(sp: SitePermissions): List<Row> = ORDER.map { Row(it, label(it), sp[it], isAutoplay(it)) }

    /**
     * Rows worth showing in the list: a real decision (not "ask"), or an autoplay value that differs from the
     * `SitePermissionsRules` defaults Hatch applies (audible BLOCKED, inaudible ALLOWED, `MainActivity`).
     */
    fun decided(sp: SitePermissions): List<Row> = rows(sp).filter { r ->
        when (r.permission) {
            Permission.AUTOPLAY_AUDIBLE -> sp.autoplayAudible != AutoplayStatus.BLOCKED
            Permission.AUTOPLAY_INAUDIBLE -> sp.autoplayInaudible != AutoplayStatus.ALLOWED
            else -> r.status != Status.NO_DECISION
        }
    }

    /** [sp] with [p] set to [status]; autoplay fields take the `AutoplayStatus` form (NO_DECISION → BLOCKED). */
    fun withStatus(sp: SitePermissions, p: Permission, status: Status): SitePermissions = when (p) {
        Permission.LOCATION -> sp.copy(location = status)
        Permission.NOTIFICATION -> sp.copy(notification = status)
        Permission.CAMERA -> sp.copy(camera = status)
        Permission.MICROPHONE -> sp.copy(microphone = status)
        Permission.AUTOPLAY_AUDIBLE -> sp.copy(autoplayAudible = status.toAutoplayStatus())
        Permission.AUTOPLAY_INAUDIBLE -> sp.copy(autoplayInaudible = status.toAutoplayStatus())
        Permission.LOCAL_STORAGE -> sp.copy(localStorage = status)
        Permission.MEDIA_KEY_SYSTEM_ACCESS -> sp.copy(mediaKeySystemAccess = status)
        Permission.STORAGE_ACCESS -> sp.copy(crossOriginStorageAccess = status)
        Permission.LOCAL_DEVICE_ACCESS -> sp.copy(localDeviceAccess = status)
        Permission.LOCAL_NETWORK_ACCESS -> sp.copy(localNetworkAccess = status)
        Permission.BLUETOOTH -> sp.copy(bluetooth = status)
    }

    /** Stored origins carry the default port (`String.getOrigin()` → `https://host:443`); hide it for display. */
    fun displayOrigin(origin: String): String = when {
        origin.startsWith("https://") && origin.endsWith(":443") -> origin.dropLast(4)
        origin.startsWith("http://") && origin.endsWith(":80") -> origin.dropLast(3)
        else -> origin
    }

    /** Sorted for the list: by display origin, case-insensitive. */
    fun sorted(all: List<SitePermissions>): List<SitePermissions> =
        all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { displayOrigin(it.origin).substringAfter("://") })
}
