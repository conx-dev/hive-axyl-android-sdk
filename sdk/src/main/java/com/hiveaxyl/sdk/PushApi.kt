package com.hiveaxyl.sdk

import com.hiveng.v1.DeletePushTargetRequest
import com.hiveng.v1.DeletePushTargetResponse
import com.hiveng.v1.RegisterPushTargetRequest
import com.hiveng.v1.RegisterPushTargetResponse
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class PushApi internal constructor(
    private val executor: ExecutorService
) {
    private val lock = Object()
    private var client: ConnectClient? = null

    internal fun bind(client: ConnectClient) {
        synchronized(lock) {
            this.client = client
        }
    }

    internal fun unbind() {
        synchronized(lock) {
            client = null
        }
    }

    suspend fun registerPushTarget(
        fid: String,
        fcmToken: String = "",
        appIdentifier: String = ""
    ): PushTarget {
        return suspendCoroutine { continuation ->
            registerPushTarget(fid, fcmToken, appIdentifier, continuation.asCallback())
        }
    }

    fun registerPushTarget(
        fid: String,
        fcmToken: String = "",
        appIdentifier: String = "",
        callback: HiveAxylCallback<PushTarget>
    ) {
        if (fid.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("fid is required"))
            return
        }
        runAsync(callback) {
            val request = RegisterPushTargetRequest.newBuilder()
                .setFid(fid)
                .setFcmToken(fcmToken)
                .setAppIdentifier(appIdentifier)
                .setPlatform("android")
                .build()
            val response = requireClient().unary(
                "RemotePushService",
                "RegisterPushTarget",
                request,
                RegisterPushTargetResponse.parser()
            )
            if (!response.hasTarget()) {
                throw HiveAxylException.transport("register push target response missing target")
            }
            response.target.toSdkPushTarget()
        }
    }

    suspend fun deletePushTarget(fid: String) {
        return suspendCoroutine { continuation ->
            deletePushTarget(fid, continuation.asCallback())
        }
    }

    fun deletePushTarget(fid: String, callback: HiveAxylCallback<Unit>) {
        if (fid.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("fid is required"))
            return
        }
        runAsync(callback) {
            val request = DeletePushTargetRequest.newBuilder()
                .setFid(fid)
                .build()
            requireClient().unary(
                "RemotePushService",
                "DeletePushTarget",
                request,
                DeletePushTargetResponse.parser()
            )
            Unit
        }
    }

    private fun requireClient(): ConnectClient {
        synchronized(lock) {
            return client ?: throw HiveAxylException.transport(
                "discovery returned no endpoint for domain: remote_push"
            )
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
