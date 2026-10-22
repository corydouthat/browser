package app.hatch.logins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * seats.aero, build 28: an app-services failure inside AC's logins delegates ("NSS has not initialized") was an
 * uncaught coroutine exception that killed the app. These lock both halves of the fix.
 */
class LoginsFailureContainmentTest {
    private fun source(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    @Test
    fun `a failing logins launch reaches the handler and the scope survives`() {
        val failures = mutableListOf<Throwable>()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scope = LoginsStorageProvider.contained(parent) { failures += it }
        // the shape of GeckoAutocompleteStorageDelegate.onLoginFetch: launch { delegate.async { storage }.await() }
        scope.launch { scope.async<Unit> { throw IllegalStateException("NSS has not initialized.") }.await() }
        scope.launch { throw IllegalStateException("second") }
        assertEquals(listOf("NSS has not initialized.", "second"), failures.map { it.message })
        assertTrue(parent.isActive)
        assertTrue(scope.isActive)
    }

    @Test
    fun `app-services is initialized before anything touches the components`() {
        val app = source("src/main/kotlin/app/hatch/HatchApplication.kt")
        val onCreate = app.substringAfter("override fun onCreate()")
        val init = onCreate.indexOf("AppServicesInitializer.init(")
        assertTrue(init >= 0, "HatchApplication.onCreate must call AppServicesInitializer.init")
        assertTrue(init < onCreate.indexOf("components."), "AppServicesInitializer.init must run before the first components access")
    }

    @Test
    fun `the Gecko storage delegate does not run on the unguarded application scope`() {
        val components = source("src/main/kotlin/app/hatch/Components.kt")
        assertFalse(components.contains("geckoStorageDelegate(applicationScope)"))
        val provider = source("src/main/kotlin/app/hatch/logins/LoginsStorageProvider.kt")
        for (call in listOf("GeckoAutocompleteStorageDelegate(", "GeckoLoginStorageDelegate(", "DefaultLoginValidationDelegate(")) {
            val args = provider.substringAfter(call).substringBefore("\n")
            assertTrue(args.contains("scope"), "$call must be given the contained scope: $args")
        }
    }
}
