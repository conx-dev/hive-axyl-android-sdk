package com.hiveaxyl.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuestInstallationTest {

    @Test
    fun createsAndReusesCredential() {
        val storage = MemoryGuestInstallationStorage()
        val installation = GuestInstallation(storage)

        val first = installation.getOrCreateCredential()
        val second = installation.getOrCreateCredential()

        assertTrue(Regex("^g1_[A-Za-z0-9_-]{43}$").matches(first))
        assertEquals(first, second)
        assertEquals(first, storage.value)
    }

    @Test
    fun failsWhenPersistentStorageIsMissing() {
        val installation = GuestInstallation(null)

        assertFailsWith<HiveAxylException> {
            installation.getOrCreateCredential()
        }
    }

    @Test
    fun failsWhenPersistentWriteFails() {
        val installation = GuestInstallation(MemoryGuestInstallationStorage(canWrite = false))

        assertFailsWith<HiveAxylException> {
            installation.getOrCreateCredential()
        }
    }

    private class MemoryGuestInstallationStorage(
        private val canWrite: Boolean = true
    ) : GuestInstallationStorage {
        var value: String? = null

        override fun get(): String? {
            return value
        }

        override fun set(value: String): Boolean {
            if (!canWrite) {
                return false
            }
            this.value = value
            return true
        }
    }
}
