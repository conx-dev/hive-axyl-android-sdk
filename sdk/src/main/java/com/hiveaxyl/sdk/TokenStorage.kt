package com.hiveaxyl.sdk

import android.content.Context

interface TokenStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

class InMemoryTokenStorage : TokenStorage {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? {
        return values[key]
    }

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

// prefs 파일명·키는 리브랜드(Hive Axyl) 전 값 유지 — 변경 시 기존 설치 기기의 세션이 유실된다.
class SharedPreferencesTokenStorage(context: Context) : TokenStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hiveng.sdk",
        Context.MODE_PRIVATE
    )

    override fun get(key: String): String? {
        return preferences.getString(key, null)
    }

    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

internal object TokenStorageKeys {
    const val ACCESS_TOKEN = "hiveng.accessToken"
    const val REFRESH_TOKEN = "hiveng.refreshToken"
    const val PLAYER_VALIDATION_TOKEN = "hiveng.playerValidationToken"
    const val PLAYER_VALIDATION_TOKEN_EXPIRES_AT = "hiveng.playerValidationTokenExpiresAt"
}
