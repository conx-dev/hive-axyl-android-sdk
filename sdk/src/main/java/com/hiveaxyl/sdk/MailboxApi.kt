package com.hiveaxyl.sdk

import com.hiveng.v1.CheckNewMailRequest
import com.hiveng.v1.CheckNewMailResponse
import com.hiveng.v1.ClaimMailRequest
import com.hiveng.v1.ClaimMailResponse
import com.hiveng.v1.ListMailRequest
import com.hiveng.v1.ListMailResponse
import com.hiveng.v1.PageRequest
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class ListMailResult(
    val mail: List<Mail>,
    val nextPageToken: String,
    val total: Long
)

data class CheckNewMailResult(
    val hasNewMail: Boolean
)

class MailboxApi internal constructor(
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

    suspend fun listMail(
        pageSize: Int = 20,
        pageToken: String = "",
        includeClaimed: Boolean = false
    ): ListMailResult {
        return suspendCoroutine { continuation ->
            listMail(pageSize, pageToken, includeClaimed, continuation.asCallback())
        }
    }

    fun listMail(
        pageSize: Int = 20,
        pageToken: String = "",
        includeClaimed: Boolean = false,
        callback: HiveAxylCallback<ListMailResult>
    ) {
        runAsync(callback) {
            val page = PageRequest.newBuilder()
                .setPageSize(pageSize)
                .setPageToken(pageToken)
                .build()
            val request = ListMailRequest.newBuilder()
                .setPage(page)
                .setIncludeClaimed(includeClaimed)
                .build()
            val activeClient = requireClient()
            val selectedLanguage = currentLanguage()
            val response = activeClient.unary(
                "MailboxService",
                "ListMail",
                request,
                ListMailResponse.parser()
            )
            ListMailResult(
                mail = response.mailList.map { it.toSdkMail(selectedLanguage) },
                nextPageToken = response.page.nextPageToken,
                total = response.page.total
            )
        }
    }

    suspend fun checkNewMail(): CheckNewMailResult {
        return suspendCoroutine { continuation ->
            checkNewMail(continuation.asCallback())
        }
    }

    fun checkNewMail(callback: HiveAxylCallback<CheckNewMailResult>) {
        runAsync(callback) {
            val request = CheckNewMailRequest.newBuilder().build()
            val activeClient = requireClient()
            val response = activeClient.unary(
                "MailboxService",
                "CheckNewMail",
                request,
                CheckNewMailResponse.parser()
            )
            CheckNewMailResult(
                hasNewMail = response.hasNewMail
            )
        }
    }

    suspend fun claimMail(mailId: String): Mail {
        return suspendCoroutine { continuation ->
            claimMail(mailId, continuation.asCallback())
        }
    }

    fun claimMail(mailId: String, callback: HiveAxylCallback<Mail>) {
        runAsync(callback) {
            val request = ClaimMailRequest.newBuilder()
                .setMailId(mailId)
                .build()
            val activeClient = requireClient()
            val selectedLanguage = currentLanguage()
            val response = activeClient.unary(
                "MailboxService",
                "ClaimMail",
                request,
                ClaimMailResponse.parser()
            )
            if (!response.hasMail()) {
                throw HiveAxylException.transport("claim response missing mail")
            }
            response.mail.toSdkMail(selectedLanguage)
        }
    }

    private fun requireClient(): ConnectClient {
        synchronized(lock) {
            return client ?: throw HiveAxylException.transport(
                "discovery returned no endpoint for domain: mailbox"
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
