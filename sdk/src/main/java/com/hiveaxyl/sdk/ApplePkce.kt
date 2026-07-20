package com.hiveaxyl.sdk

import java.security.MessageDigest
import java.security.SecureRandom

internal object ApplePkce {
    private const val VERIFIER_LENGTH = 64
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val random = SecureRandom()

    fun createVerifier(): String {
        return buildString(VERIFIER_LENGTH) {
            repeat(VERIFIER_LENGTH) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }
    }

    fun createChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64Url.encode(digest)
    }
}
