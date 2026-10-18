package app.hatch.logins

/**
 * SEC-6: `SyncableLoginsStorage` wipes an existing database when it has to generate a new key
 * (`SyncableLoginsStorage.kt:110-112` at FIREFOX_158_0_RELEASE), and the only signal is a Glean event Hatch does
 * not collect. [detect] is evaluated before the first open: a database file without its wrapped key in the secure
 * preferences means the KeyStore key is gone (OEM KeyStore reset, signing change) and every saved login is about to
 * be lost. The caller sets the undecryptable file aside and tells the user.
 */
object KeyLossDetector {
    fun detect(dbExists: Boolean, keyPresent: Boolean): Boolean = dbExists && !keyPresent

    /** Name of the copy kept next to the database (`logins2.sqlite.undecryptable-<millis>`). */
    fun asideName(dbName: String, nowMillis: Long): String = "$dbName.undecryptable-$nowMillis"

    const val NOTICE = "Saved logins could not be unlocked (device key lost) and were reset; the old file was kept aside."
}
