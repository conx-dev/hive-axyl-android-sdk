package com.hiveaxyl.sdk

import com.hiveng.v1.ListActiveNoticesRequest
import com.hiveng.v1.ListActiveNoticesResponse
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class NoticeApi internal constructor(
    private val executor: ExecutorService
) {
    private val lock = Object()
    private var client: ConnectClient? = null
    private var language: String = ""

    internal fun bind(client: ConnectClient, language: String) {
        synchronized(lock) {
            this.client = client
            this.language = language
        }
    }

    internal fun unbind() {
        synchronized(lock) {
            client = null
            language = ""
        }
    }

    suspend fun listActiveNotices(): List<Notice> {
        return suspendCoroutine { continuation ->
            listActiveNotices(continuation.asCallback())
        }
    }

    fun listActiveNotices(callback: HiveAxylCallback<List<Notice>>) {
        runAsync(callback) {
            val request = ListActiveNoticesRequest.newBuilder().build()
            val activeClient = requireClient()
            val selectedLanguage = currentLanguage()
            activeClient.unary(
                "NoticeService",
                "ListActiveNotices",
                request,
                ListActiveNoticesResponse.parser()
            ).noticesList.map { it.toSdkNotice(selectedLanguage) }
        }
    }

    private fun requireClient(): ConnectClient {
        synchronized(lock) {
            return client ?: throw HiveAxylException.transport(
                "discovery returned no endpoint for domain: notice"
            )
        }
    }

    private fun currentLanguage(): String {
        synchronized(lock) {
            return language
        }
    }

    private fun <T> runAsync(callback: HiveAxylCallback<T>, block: () -> T) {
        executor.execute {
            try {
                callback.onSuccess(block())
            } catch (error: Throwable) {
                callback.onError(error)
            }
        }
    }

    private fun <T> kotlin.coroutines.Continuation<T>.asCallback(): HiveAxylCallback<T> {
        val continuation = this
        return object : HiveAxylCallback<T> {
            override fun onSuccess(value: T) {
                continuation.resume(value)
            }

            override fun onError(error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
    }
}
