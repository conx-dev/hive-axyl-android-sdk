package com.hiveaxyl.sdk

import android.content.Context
import java.security.SecureRandom

internal interface GuestInstallationStorage {
    fun get(): String?
    fun set(value: String): Boolean
}

internal class SharedPreferencesGuestInstallationStorage(context: Context) : GuestInstallationStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hiveng.sdk",
        Context.MODE_PRIVATE
    )

    override fun get(): String? {
        return preferences.getString(KEY, null)
    }

    override fun set(value: String): Boolean {
        return preferences.edit().putString(KEY, value).commit()
    }

    private companion object {
        const val KEY = "hive-ng.device.id"
    }
}

internal class GuestInstallation(private val storage: GuestInstallationStorage?) {
    private val lock = Object()

    fun getOrCreateCredential(): String {
        synchronized(lock) {
            val target = storage ?: throw unavailable()
            try {
                val existing = target.get()
                if (existing != null && CREDENTIAL_PATTERN.matches(existing)) {
                    return existing
                }
                val bytes = ByteArray(RANDOM_BYTES)
                SecureRandom().nextBytes(bytes)
                val encoded = Base64Url.encode(bytes)
                val credential = PREFIX + encoded
                if (!target.set(credential) || target.get() != credential) {
                    throw unavailable()
                }
                return credential
            } catch (error: HiveAxylException) {
                throw error
            } catch (_: Exception) {
                throw unavailable()
            }
        }
    }

    private fun unavailable(): HiveAxylException {
        return HiveAxylException(
            com.hiveng.v1.ErrorCode.ERROR_CODE_INTERNAL,
            "Guest login requires persistent app storage and secure randomness"
        )
    }

    private companion object {
        const val PREFIX = "g1_"
        const val RANDOM_BYTES = 32
        val CREDENTIAL_PATTERN = Regex("^g1_[A-Za-z0-9_-]{43}$")
    }
}
