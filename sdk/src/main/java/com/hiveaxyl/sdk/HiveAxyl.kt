package com.hiveaxyl.sdk

import com.hiveng.v1.RefreshTokenRequest
import com.hiveng.v1.RefreshTokenResponse
import com.hiveng.v1.ResolveEndpointsRequest
import com.hiveng.v1.ResolveEndpointsResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class HiveAxyl private constructor(config: HiveAxylConfig) {
    val auth: AuthApi
    val notice: NoticeApi
    val mailbox: MailboxApi
    val payment: PaymentApi
    val push: PushApi

    private val resolvedConfig = ResolvedConfig.from(config)
    private val session = Session(resolveStorage(config))
    private val guestInstallation = GuestInstallation(resolveGuestInstallationStorage(config))
    private val appleLoginPendingStore = resolveAppleLoginPendingStore(config)
    private val httpClient = config.httpClient
    private val executor = config.executor
    private val lock = Object()
    private var ready = false

    init {
        auth = AuthApi(session, executor, guestInstallation, appleLoginPendingStore)
        notice = NoticeApi(executor)
        mailbox = MailboxApi(executor)
        payment = PaymentApi(executor)
        push = PushApi(executor)
        session.onCleared = { auth.clearPlayer() }
    }

    suspend fun initialize() {
        return suspendCoroutine { continuation ->
            initialize(continuation.asCallback())
        }
    }

    fun initialize(callback: HiveAxylCallback<Unit>) {
        executor.execute {
            try {
                initializeBlocking()
                callback.onSuccess(Unit)
            } catch (error: Throwable) {
                callback.onError(error)
            }
        }
    }

    fun isReady(): Boolean {
        synchronized(lock) {
            return ready
        }
    }

    private fun initializeBlocking() {
        val gateway = ConnectClient(
            baseUrl = resolvedConfig.gatewayUrl,
            apiKey = resolvedConfig.apiKey,
            language = resolvedConfig.language,
            session = session,
            httpClient = httpClient,
            debug = resolvedConfig.debug
        )
        val request = ResolveEndpointsRequest.newBuilder()
            .setClientVersion(resolvedConfig.clientVersion)
            .setProjectId(resolvedConfig.projectId)
            .build()
        val response = gateway.unary(
            "DiscoveryService",
            "ResolveEndpoints",
            request,
            ResolveEndpointsResponse.parser(),
            allowsSessionRefresh = false
        )

        val resolved = response.endpointsList.associate {
            it.domain to it.baseUrl.trimEnd('/')
        }
        val authBaseUrl = resolved["auth"]
        if (authBaseUrl.isNullOrEmpty()) {
            throw HiveAxylException.transport("discovery returned no endpoint for domain: auth")
        }

        val authClient = ConnectClient(
            baseUrl = authBaseUrl,
            apiKey = resolvedConfig.apiKey,
            language = resolvedConfig.language,
            session = session,
            httpClient = httpClient,
            debug = resolvedConfig.debug
        )
        session.refreshFn = { refreshToken ->
            val refreshRequest = RefreshTokenRequest.newBuilder()
                .setRefreshToken(refreshToken)
                .build()
            val refreshResponse = authClient.unary(
                "AuthService",
                "RefreshToken",
                refreshRequest,
                RefreshTokenResponse.parser(),
                allowsSessionRefresh = false
            )
            if (!refreshResponse.hasTokenPair()) {
                throw HiveAxylException.transport("refresh response missing token pair")
            }
            refreshResponse.tokenPair
        }
        authClient.onBannedError = { auth.emitIfBanned(it) }
        auth.bind(authClient)
        val noticeBaseUrl = resolved["notice"]
        if (!noticeBaseUrl.isNullOrEmpty()) {
            val noticeClient = ConnectClient(
                baseUrl = noticeBaseUrl,
                apiKey = resolvedConfig.apiKey,
                language = resolvedConfig.language,
                session = session,
                httpClient = httpClient,
                debug = resolvedConfig.debug
            )
            notice.bind(noticeClient, resolvedConfig.language)
        } else {
            notice.unbind()
        }

        val mailboxBaseUrl = resolved["mailbox"]
        if (!mailboxBaseUrl.isNullOrEmpty()) {
            val mailboxClient = ConnectClient(
                baseUrl = mailboxBaseUrl,
                apiKey = resolvedConfig.apiKey,
                language = resolvedConfig.language,
                session = session,
                httpClient = httpClient,
                debug = resolvedConfig.debug
            )
            mailbox.bind(mailboxClient, resolvedConfig.language)
        } else {
            mailbox.unbind()
        }

        val paymentBaseUrl = resolved["payment"]
        if (!paymentBaseUrl.isNullOrEmpty()) {
            val paymentClient = ConnectClient(
                baseUrl = paymentBaseUrl,
                apiKey = resolvedConfig.apiKey,
                language = resolvedConfig.language,
                session = session,
                httpClient = httpClient,
                debug = resolvedConfig.debug
            )
            payment.bind(paymentClient)
        } else {
            payment.unbind()
        }

        val remotePushBaseUrl = resolved["remote_push"]
        if (!remotePushBaseUrl.isNullOrEmpty()) {
            val remotePushClient = ConnectClient(
                baseUrl = remotePushBaseUrl,
                apiKey = resolvedConfig.apiKey,
                language = resolvedConfig.language,
                session = session,
                httpClient = httpClient,
                debug = resolvedConfig.debug
            )
            push.bind(remotePushClient)
        } else {
            push.unbind()
        }

        synchronized(lock) {
            ready = true
        }
    }

    private fun resolveStorage(config: HiveAxylConfig): TokenStorage {
        config.tokenStorage?.let { return it }
        val persist = config.persistSession ?: (config.context != null)
        if (persist) {
            val context = config.context
                ?: throw HiveAxylException.invalidArgument("context is required when persistSession is true")
            return SharedPreferencesTokenStorage(context)
        }
        return InMemoryTokenStorage()
    }

    private fun resolveGuestInstallationStorage(config: HiveAxylConfig): GuestInstallationStorage? {
        val context = config.context ?: return null
        return SharedPreferencesGuestInstallationStorage(context)
    }

    private fun resolveAppleLoginPendingStore(config: HiveAxylConfig): AppleLoginPendingStore {
        val context = config.context ?: return InMemoryAppleLoginPendingStore()
        return SharedPreferencesAppleLoginPendingStore(context)
    }

    private fun kotlin.coroutines.Continuation<Unit>.asCallback(): HiveAxylCallback<Unit> {
        val continuation = this
        return object : HiveAxylCallback<Unit> {
            override fun onSuccess(value: Unit) {
                continuation.resume(value)
            }

            override fun onError(error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
    }

    companion object {
        fun createHiveAxyl(config: HiveAxylConfig): HiveAxyl {
            return HiveAxyl(config)
        }
    }
}

object HiveAxylSdk {
    fun createHiveAxyl(config: HiveAxylConfig): HiveAxyl {
        return HiveAxyl.createHiveAxyl(config)
    }
}
