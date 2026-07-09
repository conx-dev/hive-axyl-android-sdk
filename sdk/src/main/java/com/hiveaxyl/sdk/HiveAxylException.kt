package com.hiveaxyl.sdk

import com.hiveng.v1.ErrorCode
import java.util.Date

open class HiveAxylException(
    val errorCode: ErrorCode,
    override val message: String,
    val metadata: Map<String, String> = emptyMap(),
    val isTransport: Boolean = false
) : Exception(message) {
    val code: String = ErrorCodeName.of(errorCode)

    companion object {
        fun invalidArgument(message: String): HiveAxylException {
            return HiveAxylException(ErrorCode.ERROR_CODE_INVALID_ARGUMENT, message)
        }

        fun notInitialized(): HiveAxylException {
            return HiveAxylException(
                ErrorCode.ERROR_CODE_UNSPECIFIED,
                "HiveAxyl not initialized - call initialize() first"
            )
        }

        fun transport(message: String): HiveAxylException {
            return HiveAxylException(
                ErrorCode.ERROR_CODE_UNSPECIFIED,
                message,
                isTransport = true
            )
        }
    }
}

class BannedException(
    val reason: String,
    val until: Date?,
    val permanent: Boolean,
    message: String,
    metadata: Map<String, String>
) : HiveAxylException(ErrorCode.ERROR_CODE_PLAYER_BANNED, message, metadata)

class MaintenanceException(
    val maintenanceMessage: String,
    val startsAt: Date?,
    val endsAt: Date?,
    message: String,
    metadata: Map<String, String>
) : HiveAxylException(ErrorCode.ERROR_CODE_MAINTENANCE_IN_PROGRESS, message, metadata)

internal object ErrorCodeName {
    fun of(code: ErrorCode): String {
        return when (code) {
            ErrorCode.ERROR_CODE_INTERNAL -> "INTERNAL"
            ErrorCode.ERROR_CODE_INVALID_ARGUMENT -> "INVALID_ARGUMENT"
            ErrorCode.ERROR_CODE_NOT_FOUND -> "NOT_FOUND"
            ErrorCode.ERROR_CODE_ALREADY_EXISTS -> "ALREADY_EXISTS"
            ErrorCode.ERROR_CODE_PERMISSION_DENIED -> "PERMISSION_DENIED"
            ErrorCode.ERROR_CODE_UNAUTHENTICATED -> "UNAUTHENTICATED"
            ErrorCode.ERROR_CODE_MAINTENANCE_IN_PROGRESS -> "MAINTENANCE_IN_PROGRESS"
            ErrorCode.ERROR_CODE_GEO_BLOCKED -> "GEO_BLOCKED"
            ErrorCode.ERROR_CODE_CLIENT_VERSION_UNSUPPORTED -> "CLIENT_VERSION_UNSUPPORTED"
            ErrorCode.ERROR_CODE_PLAYER_BANNED -> "PLAYER_BANNED"
            ErrorCode.ERROR_CODE_INVALID_PROVIDER_TOKEN -> "INVALID_PROVIDER_TOKEN"
            ErrorCode.ERROR_CODE_PROVIDER_NOT_ENABLED -> "PROVIDER_NOT_ENABLED"
            ErrorCode.ERROR_CODE_CREDENTIAL_NOT_CONFIGURED -> "CREDENTIAL_NOT_CONFIGURED"
            ErrorCode.ERROR_CODE_SESSION_EXPIRED -> "SESSION_EXPIRED"
            ErrorCode.ERROR_CODE_PLAYER_NOT_FOUND -> "PLAYER_NOT_FOUND"
            ErrorCode.ERROR_CODE_DUPLICATE_RECEIPT -> "DUPLICATE_RECEIPT"
            ErrorCode.ERROR_CODE_RECEIPT_VERIFICATION_FAILED -> "RECEIPT_VERIFICATION_FAILED"
            ErrorCode.ERROR_CODE_MARKET_NOT_SUPPORTED -> "MARKET_NOT_SUPPORTED"
            ErrorCode.ERROR_CODE_API_KEY_INVALID -> "API_KEY_INVALID"
            ErrorCode.ERROR_CODE_API_KEY_REVOKED -> "API_KEY_REVOKED"
            ErrorCode.ERROR_CODE_SERVER_KEY_INVALID -> "SERVER_KEY_INVALID"
            ErrorCode.ERROR_CODE_SERVER_KEY_REVOKED -> "SERVER_KEY_REVOKED"
            ErrorCode.ERROR_CODE_ADMIN_EMAIL_EXISTS -> "ADMIN_EMAIL_EXISTS"
            ErrorCode.ERROR_CODE_ADMIN_INVALID_CREDENTIALS -> "ADMIN_INVALID_CREDENTIALS"
            ErrorCode.ERROR_CODE_PACKAGE_NAME_EXISTS -> "PACKAGE_NAME_EXISTS"
            else -> "UNSPECIFIED"
        }
    }
}
