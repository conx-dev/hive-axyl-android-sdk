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
                val encoded = encodeBase64Url(bytes)
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

    private fun encodeBase64Url(bytes: ByteArray): String {
        val encoded = StringBuilder(43)
        var index = 0
        while (index < bytes.size) {
            val first = byteValue(bytes, index)
            val second = byteValue(bytes, index + 1)
            val third = byteValue(bytes, index + 2)
            val value = (first shl 16) or (second shl 8) or third
            encoded.append(BASE64_URL[(value shr 18) and 63])
            encoded.append(BASE64_URL[(value shr 12) and 63])
            if (index + 1 < bytes.size) {
                encoded.append(BASE64_URL[(value shr 6) and 63])
            }
            if (index + 2 < bytes.size) {
                encoded.append(BASE64_URL[value and 63])
            }
            index += 3
        }
        return encoded.toString()
    }

    private fun byteValue(bytes: ByteArray, index: Int): Int {
        if (index >= bytes.size) {
            return 0
        }
        return bytes[index].toInt() and 0xff
    }

    private companion object {
        const val PREFIX = "g1_"
        const val RANDOM_BYTES = 32
        const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val CREDENTIAL_PATTERN = Regex("^g1_[A-Za-z0-9_-]{43}$")
    }
}
