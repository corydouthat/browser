package app.hatch.logins

import kotlinx.coroutines.runBlocking
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import mozilla.components.concept.storage.LoginValidationDelegate
import mozilla.components.concept.storage.LoginsStorage
import mozilla.components.service.sync.logins.DefaultLoginValidationDelegate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `PromptFeature.loginValidationDelegate` is AC's `DefaultLoginValidationDelegate` over Hatch's lazy storage.
 * This pins the mapping Hatch relies on (service-sync-logins 158 `DefaultLoginValidationDelegate.kt`):
 * `findLoginToUpdate == null` -> CanBeCreated (save dialog), otherwise CanBeUpdated(found) (update dialog),
 * and the storage is only opened on first use.
 */
class LoginValidationDelegateTest {
    private class FakeStorage(private val existing: Login?) : LoginsStorage {
        val queries = mutableListOf<LoginEntry>()
        override suspend fun findLoginToUpdate(entry: LoginEntry): Login? { queries += entry; return existing }
        override suspend fun wipeLocal() = Unit
        override suspend fun delete(guid: String) = false
        override suspend fun get(guid: String): Login? = null
        override suspend fun touch(guid: String) = Unit
        override suspend fun list(): List<Login> = listOfNotNull(existing)
        override suspend fun count(): Long = if (existing == null) 0 else 1
        override suspend fun add(entry: LoginEntry): Login = error("unused")
        override suspend fun addMany(entries: List<LoginEntry>): List<Result<Login>> = error("unused")
        override suspend fun update(guid: String, entry: LoginEntry): Login = error("unused")
        override suspend fun addOrUpdate(entry: LoginEntry): Login = error("unused")
        override suspend fun getByBaseDomain(origin: String): List<Login> = listOfNotNull(existing)
        override fun cancelReads() = Unit
        override fun cancelReads(nextQuery: String) = Unit
        override fun cancelWrites() = Unit
        override fun close() = Unit
    }

    private val entry = LoginEntry(origin = "https://example.com", username = "cory", password = "new")

    @Test
    fun `no stored match means create`() = runBlocking {
        var opened = false
        val delegate: LoginValidationDelegate = DefaultLoginValidationDelegate(lazy { opened = true; FakeStorage(null) })
        assertFalse(opened, "storage is lazy until the first prompt")
        val result = delegate.shouldUpdateOrCreateAsync(entry).await()
        assertIs<LoginValidationDelegate.Result.CanBeCreated>(result)
        assertTrue(opened)
    }

    @Test
    fun `a stored match means update with that login`() = runBlocking {
        val stored = Login(guid = "g1", username = "cory", password = "old", origin = "https://example.com")
        val storage = FakeStorage(stored)
        val result = DefaultLoginValidationDelegate(lazy { storage }).shouldUpdateOrCreateAsync(entry).await()
        val update = assertIs<LoginValidationDelegate.Result.CanBeUpdated>(result)
        assertEquals(stored, update.foundLogin)
        assertEquals(listOf(entry), storage.queries)
    }
}
