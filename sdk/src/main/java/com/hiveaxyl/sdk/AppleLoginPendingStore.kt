package com.hiveaxyl.sdk

import android.content.Context

internal interface AppleLoginPendingStore {
    fun save(verifier: String)
    fun load(): String?
    fun clear(expectedVerifier: String? = null)
}

internal class InMemoryAppleLoginPendingStore : AppleLoginPendingStore {
    private var verifier: String? = null
    private var createdAtMillis: Long = 0

    @Synchronized
    override fun save(verifier: String) {
        this.verifier = verifier
        createdAtMillis = System.currentTimeMillis()
    }

    @Synchronized
    override fun load(): String? {
        if (isExpired(createdAtMillis)) {
            clear()
            return null
        }
        return verifier
    }

    @Synchronized
    override fun clear(expectedVerifier: String?) {
        if (expectedVerifier != null && expectedVerifier != verifier) {
            return
        }
        verifier = null
        createdAtMillis = 0
    }
}

internal class SharedPreferencesAppleLoginPendingStore(context: Context) : AppleLoginPendingStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun save(verifier: String) {
        val saved = preferences.edit()
            .putString(KEY_VERIFIER, verifier)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .commit()
        if (!saved) {
            throw HiveAxylException.transport("apple login state could not be saved")
        }
    }

    @Synchronized
    override fun load(): String? {
        val createdAtMillis = preferences.getLong(KEY_CREATED_AT, 0)
        if (isExpired(createdAtMillis)) {
            clear()
            return null
        }
        return preferences.getString(KEY_VERIFIER, null)
    }

    @Synchronized
    override fun clear(expectedVerifier: String?) {
        val current = preferences.getString(KEY_VERIFIER, null)
        if (expectedVerifier != null && expectedVerifier != current) {
            return
        }
        preferences.edit()
            .remove(KEY_VERIFIER)
            .remove(KEY_CREATED_AT)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "hive_axyl_apple_login"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_CREATED_AT = "created_at"
    }
}

private const val MAX_AGE_MILLIS = 10 * 60 * 1000L

private fun isExpired(createdAtMillis: Long): Boolean {
    if (createdAtMillis <= 0) {
        return true
    }
    val ageMillis = System.currentTimeMillis() - createdAtMillis
    return ageMillis < 0 || ageMillis > MAX_AGE_MILLIS
}
