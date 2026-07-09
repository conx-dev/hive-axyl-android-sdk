package com.hiveaxyl.sdk

import android.content.Context
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val HIVE_AXYL_DEFAULT_GATEWAY_URL = "https://gw-test-gcl.c2xstation.net:8081"

data class HiveAxylConfig(
    val gatewayUrl: String = HIVE_AXYL_DEFAULT_GATEWAY_URL,
    val projectId: String,
    val apiKey: String,
    val context: Context? = null,
    val clientVersion: String = "",
    val language: String = Locale.getDefault().toLanguageTag(),
    val debug: Boolean = false,
    // 미지정(null)이면 context가 있을 때 영속 저장을 사용한다. false만 인메모리 강제.
    val persistSession: Boolean? = null,
    val tokenStorage: TokenStorage? = null,
    val httpClient: OkHttpClient = OkHttpClient(),
    val executor: ExecutorService = Executors.newCachedThreadPool()
)

internal data class ResolvedConfig(
    val gatewayUrl: String,
    val projectId: String,
    val apiKey: String,
    val clientVersion: String,
    val language: String,
    val debug: Boolean
) {
    companion object {
        fun from(config: HiveAxylConfig): ResolvedConfig {
            var gatewayUrl = config.gatewayUrl.trim().trimEnd('/')
            val projectId = config.projectId.trim()
            if (gatewayUrl.isEmpty()) {
                gatewayUrl = HIVE_AXYL_DEFAULT_GATEWAY_URL
            }
            if (config.apiKey.isEmpty()) {
                throw HiveAxylException.invalidArgument("apiKey is required")
            }
            if (projectId.isEmpty()) {
                throw HiveAxylException.invalidArgument("projectId is required")
            }

            return ResolvedConfig(
                gatewayUrl = gatewayUrl,
                projectId = projectId,
                apiKey = config.apiKey,
                clientVersion = config.clientVersion,
                language = config.language,
                debug = config.debug
            )
        }
    }
}
