package com.hiveaxyl.sdk

import com.hiveng.v1.TokenPair

internal class Session(private val storage: TokenStorage) {
    var refreshFn: ((String) -> TokenPair)? = null
    var onCleared: (() -> Unit)? = null

    private val lock = Object()
    private var refreshError: Throwable? = null

    val accessToken: String?
        get() = storage.get(TokenStorageKeys.ACCESS_TOKEN)

    val refreshToken: String?
        get() = storage.get(TokenStorageKeys.REFRESH_TOKEN)

    val playerValidationToken: String?
        get() {
            val token = storage.get(TokenStorageKeys.PLAYER_VALIDATION_TOKEN)
            val expiresAt = storage.get(TokenStorageKeys.PLAYER_VALIDATION_TOKEN_EXPIRES_AT)?.toLongOrNull()
            if (token.isNullOrEmpty() || expiresAt == null) {
                return null
            }
            if (expiresAt <= System.currentTimeMillis()) {
                storage.remove(TokenStorageKeys.PLAYER_VALIDATION_TOKEN)
                storage.remove(TokenStorageKeys.PLAYER_VALIDATION_TOKEN_EXPIRES_AT)
                return null
            }
            return token
        }

    fun save(pair: TokenPair) {
        storage.set(TokenStorageKeys.ACCESS_TOKEN, pair.accessToken)
        storage.set(TokenStorageKeys.REFRESH_TOKEN, pair.refreshToken)
        if (pair.playerValidationToken.isNotEmpty() && pair.hasPlayerValidationTokenExpiresAt()) {
            val expiresAt = pair.playerValidationTokenExpiresAt.seconds * 1000L +
                pair.playerValidationTokenExpiresAt.nanos / 1_000_000L
            savePlayerValidationToken(pair.playerValidationToken, expiresAt)
        } else {
            clearPlayerValidationToken()
        }
    }

    fun save(
        accessToken: String,
        refreshToken: String,
        playerValidationToken: String,
        playerValidationTokenExpiresAt: Long?
    ) {
        storage.set(TokenStorageKeys.ACCESS_TOKEN, accessToken)
        storage.set(TokenStorageKeys.REFRESH_TOKEN, refreshToken)
        if (playerValidationToken.isNotEmpty() && playerValidationTokenExpiresAt != null) {
            savePlayerValidationToken(playerValidationToken, playerValidationTokenExpiresAt)
        } else {
            clearPlayerValidationToken()
        }
    }

    fun clear() {
        storage.remove(TokenStorageKeys.ACCESS_TOKEN)
        storage.remove(TokenStorageKeys.REFRESH_TOKEN)
        clearPlayerValidationToken()
        onCleared?.invoke()
    }

    fun tryRefresh(): Boolean {
        synchronized(lock) {
            refreshError = null
            val token = refreshToken
            val fn = refreshFn
            if (token.isNullOrEmpty() || fn == null) {
                return false
            }

            return try {
                save(fn(token))
                true
            } catch (error: Throwable) {
                refreshError = error
                clear()
                false
            }
        }
    }

    fun consumeRefreshError(): Throwable? {
        synchronized(lock) {
            val error = refreshError
            refreshError = null
            return error
        }
    }

    private fun savePlayerValidationToken(token: String, expiresAt: Long) {
        storage.set(TokenStorageKeys.PLAYER_VALIDATION_TOKEN, token)
        storage.set(TokenStorageKeys.PLAYER_VALIDATION_TOKEN_EXPIRES_AT, expiresAt.toString())
    }

    private fun clearPlayerValidationToken() {
        storage.remove(TokenStorageKeys.PLAYER_VALIDATION_TOKEN)
        storage.remove(TokenStorageKeys.PLAYER_VALIDATION_TOKEN_EXPIRES_AT)
    }
}
