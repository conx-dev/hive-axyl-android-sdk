package com.hiveaxyl.sdk

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import com.hiveng.v1.ErrorCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

internal class ConnectClient(
    baseUrl: String,
    private val apiKey: String?,
    private val language: String,
    private val session: Session,
    private val httpClient: OkHttpClient,
    private val debug: Boolean
) {
    var onBannedError: ((HiveAxylException) -> Unit)? = null

    private val baseUrl = baseUrl.trimEnd('/')
    private val protoMediaType = "application/proto".toMediaType()
    private val jsonMediaType = "application/json".toMediaType()

    fun <T : MessageLite> unary(
        service: String,
        method: String,
        request: MessageLite,
        parser: Parser<T>,
        allowsSessionRefresh: Boolean = true
    ): T {
        return try {
            sendOnce(service, method, request, parser)
        } catch (error: HiveAxylException) {
            if (!allowsSessionRefresh || error.errorCode != ErrorCode.ERROR_CODE_SESSION_EXPIRED) {
                throw error
            }

            val refreshed = session.tryRefresh()
            if (!refreshed) {
                val refreshError = session.consumeRefreshError()
                if (refreshError != null) {
                    throw refreshError
                }
                throw error
            }
            sendOnce(service, method, request, parser)
        }
    }

    fun postJson(path: String, body: String): String {
        val requestBody = body.toRequestBody(jsonMediaType)
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        applyApiHeaders(builder)

        val response = try {
            httpClient.newCall(builder.build()).execute()
        } catch (error: IOException) {
            throw HiveAxylException.transport(error.message ?: "network error")
        }

        response.use {
            val responseBody = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw HiveAxylException.transport(jsonErrorMessage(it.code, it.message, responseBody))
            }
            return responseBody
        }
    }

    private fun <T : MessageLite> sendOnce(
        service: String,
        method: String,
        message: MessageLite,
        parser: Parser<T>
    ): T {
        val body = message.toByteArray().toRequestBody(protoMediaType)
        val builder = Request.Builder()
            .url("$baseUrl/hiveng.v1.$service/$method")
            .post(body)
            .header("Content-Type", "application/proto")
            .header("Accept", "application/proto")
        applyAuthHeaders(builder, method)

        val response = try {
            httpClient.newCall(builder.build()).execute()
        } catch (error: IOException) {
            throw HiveAxylException.transport(error.message ?: "network error")
        }

        response.use {
            val responseBody = it.body?.bytes() ?: ByteArray(0)
            if (!it.isSuccessful) {
                val error = ConnectErrorParser.parse(it.code, responseBody)
                log("$service/$method failed: ${error.code} ${error.message}")
                if (error is BannedException) {
                    onBannedError?.invoke(error)
                }
                throw error
            }

            return try {
                parser.parseFrom(responseBody)
            } catch (_: Exception) {
                throw HiveAxylException.transport("invalid response body for $service/$method")
            }
        }
    }

    private fun applyApiHeaders(builder: Request.Builder) {
        val key = apiKey
        if (key.isNullOrEmpty()) {
            return
        }

        builder.header("Authorization", "Bearer $key")
        if (language.isNotEmpty()) {
            builder.header("X-Hive-Ng-Language", language)
        }
    }

    private fun applyAuthHeaders(builder: Request.Builder, method: String) {
        val key = apiKey
        if (key.isNullOrEmpty()) {
            return
        }

        builder.header("Authorization", "Bearer $key")
        if (language.isNotEmpty()) {
            builder.header("X-Hive-Ng-Language", language)
        }
        val token = session.accessToken
        if (!token.isNullOrEmpty() && method != "RefreshToken") {
            builder.header("X-Player-Token", token)
        }
    }

    private fun log(message: String) {
        if (!debug) {
            return
        }
        android.util.Log.d("HiveAxyl", message)
    }

    private fun jsonErrorMessage(statusCode: Int, statusMessage: String, body: String): String {
        if (body.isBlank()) {
            return "HTTP_$statusCode: $statusMessage"
        }
        return try {
            val json = JSONObject(body)
            val code = json.optString("code", "HTTP_$statusCode")
            val message = json.optString("message", statusMessage)
            "$code: $message"
        } catch (_: Exception) {
            "HTTP_$statusCode: $statusMessage"
        }
    }
}
