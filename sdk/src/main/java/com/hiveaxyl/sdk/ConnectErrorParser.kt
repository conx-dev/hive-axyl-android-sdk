package com.hiveaxyl.sdk

import android.util.Base64
import com.hiveng.v1.ErrorCode
import com.hiveng.v1.ErrorDetail
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object ConnectErrorParser {
    private val fractionalDate = Regex("^(.*\\.)(\\d+)(Z|[+-]\\d{2}:?\\d{2})$")

    fun parse(statusCode: Int, body: ByteArray): HiveAxylException {
        val json = String(body, StandardCharsets.UTF_8)
        val envelope = try {
            JSONObject(json)
        } catch (_: Exception) {
            return HiveAxylException.transport("HTTP $statusCode")
        }

        val message = envelope.optString("message", "")
        val detail = errorDetailOf(envelope)
        if (detail == null) {
            val connectCode = envelope.optString("code", "")
            if (connectCode == "unavailable" || connectCode == "deadline_exceeded") {
                return HiveAxylException.transport(message.ifEmpty { "HTTP $statusCode" })
            }
            return HiveAxylException(ErrorCode.ERROR_CODE_UNSPECIFIED, message)
        }
        return mapDetail(detail, message)
    }

    private fun errorDetailOf(envelope: JSONObject): ErrorDetail? {
        val details = envelope.optJSONArray("details") ?: return null
        for (index in 0 until details.length()) {
            val candidate = details.optJSONObject(index) ?: continue
            val type = normalizeType(candidate.optString("type", ""))
            if (type != "hiveng.v1.ErrorDetail") {
                continue
            }

            val bytes = decodeBase64(candidate.optString("value", ""))
            if (bytes.isEmpty()) {
                continue
            }

            return try {
                ErrorDetail.parseFrom(bytes)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun normalizeType(value: String): String {
        val index = value.lastIndexOf('/')
        if (index < 0 || index == value.length - 1) {
            return value
        }
        return value.substring(index + 1)
    }

    private fun decodeBase64(value: String): ByteArray {
        if (value.isEmpty()) {
            return ByteArray(0)
        }
        val normalized = value.replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        val padded = normalized.padEnd(normalized.length + padding, '=')
        return try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun mapDetail(detail: ErrorDetail, message: String): HiveAxylException {
        val metadata = detail.metadataMap.toMap()
        if (detail.code == ErrorCode.ERROR_CODE_PLAYER_BANNED) {
            val reason = metadata["reason"] ?: message
            val untilRaw = metadata["until"] ?: metadata["banned_until"]
            val permanent = metadata["permanent"] == "true"
            return BannedException(
                reason = reason,
                until = untilRaw?.let { parseDate(it) },
                permanent = permanent,
                message = message,
                metadata = metadata
            )
        }
        if (detail.code == ErrorCode.ERROR_CODE_MAINTENANCE_IN_PROGRESS) {
            val maintenanceMessage = metadata["message"] ?: message
            return MaintenanceException(
                maintenanceMessage = maintenanceMessage,
                startsAt = metadata["starts_at"]?.let { parseDate(it) },
                endsAt = metadata["ends_at"]?.let { parseDate(it) },
                message = maintenanceMessage,
                metadata = metadata
            )
        }
        return HiveAxylException(detail.code, message, metadata)
    }

    private fun parseDate(value: String): java.util.Date? {
        val normalized = normalizeDate(value)
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        for (pattern in patterns) {
            val parsed = parseDateWithPattern(normalized, pattern)
            if (parsed != null) {
                return parsed
            }
        }
        return null
    }

    private fun parseDateWithPattern(value: String, pattern: String): java.util.Date? {
        val format = SimpleDateFormat(pattern, Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            format.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeDate(value: String): String {
        val match = fractionalDate.matchEntire(value) ?: return value
        val prefix = match.groupValues[1]
        val fraction = match.groupValues[2].padEnd(3, '0').take(3)
        val suffix = match.groupValues[3]
        return "$prefix$fraction$suffix"
    }
}
