package com.hiveaxyl.sdk

internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val encoded = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val first = byteValue(bytes, index)
            val second = byteValue(bytes, index + 1)
            val third = byteValue(bytes, index + 2)
            val value = (first shl 16) or (second shl 8) or third
            encoded.append(ALPHABET[(value shr 18) and 63])
            encoded.append(ALPHABET[(value shr 12) and 63])
            if (index + 1 < bytes.size) {
                encoded.append(ALPHABET[(value shr 6) and 63])
            }
            if (index + 2 < bytes.size) {
                encoded.append(ALPHABET[value and 63])
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
}
