package com.hiveaxyl.sdk

import android.net.Uri
import com.hiveng.v1.ClientPlatform
import com.hiveng.v1.CompleteAppleLoginRequest
import com.hiveng.v1.CompleteAppleLoginResponse
import com.hiveng.v1.GetLoginProvidersRequest
import com.hiveng.v1.GetLoginProvidersResponse
import com.hiveng.v1.GetPlayerRequest
import com.hiveng.v1.GetPlayerResponse
import com.hiveng.v1.IdentityProvider
import com.hiveng.v1.LoginWithProviderRequest
import com.hiveng.v1.LoginWithProviderResponse
import com.hiveng.v1.LogoutRequest
import com.hiveng.v1.LogoutResponse
import com.hiveng.v1.StartAppleLoginRequest
import com.hiveng.v1.StartAppleLoginResponse
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class AuthApi internal constructor(
    private val session: Session,
    private val executor: ExecutorService,
    private val guestInstallation: GuestInstallation,
    private val appleLoginPendingStore: AppleLoginPendingStore
) {
    private val lock = Object()
    private var client: ConnectClient? = null
    private var player: Player? = null
    private val bannedCallbacks = mutableListOf<(BannedException) -> Unit>()

    internal fun bind(client: ConnectClient) {
        synchronized(lock) {
            this.client = client
        }
    }

    suspend fun getLoginProviders(countryOverride: String = ""): LoginProviders {
        return suspendCoroutine { continuation ->
            getLoginProviders(countryOverride, continuation.asCallback())
        }
    }

    fun getLoginProviders(
        countryOverride: String = "",
        callback: HiveAxylCallback<LoginProviders>
    ) {
        runAsync(callback) {
            val request = GetLoginProvidersRequest.newBuilder()
                .setCountryOverride(countryOverride)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_ANDROID)
                .build()
            requireClient().unary(
                "AuthService",
                "GetLoginProviders",
                request,
                GetLoginProvidersResponse.parser()
            ).toSdkLoginProviders()
        }
    }

    suspend fun loginWithGoogle(idToken: String): Player {
        return suspendCoroutine { continuation ->
            loginWithGoogle(idToken, continuation.asCallback())
        }
    }

    fun loginWithGoogle(idToken: String, callback: HiveAxylCallback<Player>) {
        if (idToken.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("idToken is required"))
            return
        }
        login(IdentityProvider.IDENTITY_PROVIDER_GOOGLE, idToken, callback)
    }

    suspend fun loginWithFacebook(accessToken: String): Player {
        return suspendCoroutine { continuation ->
            loginWithFacebook(accessToken, continuation.asCallback())
        }
    }

    fun loginWithFacebook(accessToken: String, callback: HiveAxylCallback<Player>) {
        if (accessToken.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("accessToken is required"))
            return
        }
        login(IdentityProvider.IDENTITY_PROVIDER_FACEBOOK, accessToken, callback)
    }

    suspend fun startAppleLogin(clientId: String, returnUrl: String): String {
        return suspendCoroutine { continuation ->
            startAppleLogin(clientId, returnUrl, continuation.asCallback())
        }
    }

    fun startAppleLogin(clientId: String, returnUrl: String, callback: HiveAxylCallback<String>) {
        if (clientId.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("clientId is required"))
            return
        }
        if (returnUrl.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("returnUrl is required"))
            return
        }

        runAsync(callback) {
            val verifier = ApplePkce.createVerifier()
            val request = StartAppleLoginRequest.newBuilder()
                .setClientId(clientId)
                .setReturnUrl(returnUrl)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_ANDROID)
                .setCodeChallenge(ApplePkce.createChallenge(verifier))
                .build()
            appleLoginPendingStore.save(verifier)
            val response = try {
                requireClient().unary(
                    "AuthService",
                    "StartAppleLogin",
                    request,
                    StartAppleLoginResponse.parser()
                )
            } catch (error: Throwable) {
                appleLoginPendingStore.clear(verifier)
                throw error
            }
            val authorizationUrl = response.authorizationUrl
            if (authorizationUrl.isEmpty()) {
                appleLoginPendingStore.clear(verifier)
                throw HiveAxylException.transport("apple authorizationUrl missing")
            }
            authorizationUrl
        }
    }

    suspend fun completeAppleLogin(uri: Uri): Player {
        return suspendCoroutine { continuation ->
            completeAppleLogin(uri, continuation.asCallback())
        }
    }

    fun completeAppleLogin(uri: Uri, callback: HiveAxylCallback<Player>) {
        runAsync(callback) {
            val status = uri.getQueryParameter("status").orEmpty()
            if (status == "error") {
                val code = uri.getQueryParameter("error_code").orEmpty()
                val message = uri.getQueryParameter("error_message").orEmpty()
                appleLoginPendingStore.clear()
                throw HiveAxylException.transport(appleCallbackErrorMessage(code, message))
            }
            if (status != "ok") {
                throw HiveAxylException.invalidArgument("apple login callback is invalid")
            }
            val authorizationCode = uri.getQueryParameter("code").orEmpty()
            if (authorizationCode.isEmpty()) {
                throw HiveAxylException.transport("apple login response missing authorization code")
            }
            val verifier = appleLoginPendingStore.load()
                ?: throw HiveAxylException.invalidArgument("apple login session is missing or expired")
            val request = CompleteAppleLoginRequest.newBuilder()
                .setAuthorizationCode(authorizationCode)
                .setCodeVerifier(verifier)
                .build()
            val response = requireClient().unary(
                "AuthService",
                "CompleteAppleLogin",
                request,
                CompleteAppleLoginResponse.parser()
            )
            if (!response.hasPlayer() || !response.hasTokenPair()) {
                throw HiveAxylException.transport("apple login response missing player or token pair")
            }
            session.save(response.tokenPair)
            val logged = response.player.toSdkPlayer()
            setPlayer(logged)
            appleLoginPendingStore.clear(verifier)
            logged
        }
    }

    suspend fun loginAsGuest(): Player {
        return suspendCoroutine { continuation ->
            loginAsGuest(continuation.asCallback())
        }
    }

    fun loginAsGuest(callback: HiveAxylCallback<Player>) {
        runAsync(callback) {
            val credential = guestInstallation.getOrCreateCredential()
            loginBlocking(IdentityProvider.IDENTITY_PROVIDER_GUEST, credential)
        }
    }

    suspend fun restoreSession(): Player? {
        return suspendCoroutine { continuation ->
            restoreSession(continuation.asCallback())
        }
    }

    fun restoreSession(callback: HiveAxylCallback<Player?>) {
        if (session.accessToken == null) {
            callback.onSuccess(null)
            return
        }

        runAsync(callback) {
            fetchNullablePlayer()
        }
    }

    suspend fun logout() {
        return suspendCoroutine { continuation ->
            logout(continuation.asCallback())
        }
    }

    fun logout(callback: HiveAxylCallback<Unit>) {
        runAsync(callback) {
            if (session.accessToken != null) {
                try {
                    requireClient().unary(
                        "AuthService",
                        "Logout",
                        LogoutRequest.getDefaultInstance(),
                        LogoutResponse.parser()
                    )
                } catch (_: Throwable) {
                }
            }
            session.clear()
            clearPlayer()
        }
    }

    fun currentPlayer(): Player? {
        synchronized(lock) {
            return player
        }
    }

    fun playerValidationToken(): String? {
        return session.playerValidationToken
    }

    fun onBanned(callback: (BannedException) -> Unit) {
        synchronized(lock) {
            bannedCallbacks.add(callback)
        }
    }

    internal fun emitIfBanned(error: HiveAxylException) {
        val banned = error as? BannedException ?: return
        val callbacks = synchronized(lock) {
            bannedCallbacks.toList()
        }
        callbacks.forEach { it(banned) }
    }

    internal fun clearPlayer() {
        synchronized(lock) {
            player = null
        }
    }

    private fun login(
        provider: IdentityProvider,
        providerToken: String,
        callback: HiveAxylCallback<Player>
    ) {
        runAsync(callback) {
            loginBlocking(provider, providerToken)
        }
    }

    private fun loginBlocking(provider: IdentityProvider, providerToken: String): Player {
        val request = LoginWithProviderRequest.newBuilder()
            .setProvider(provider)
            .setProviderToken(providerToken)
            .setPlatform(ClientPlatform.CLIENT_PLATFORM_ANDROID)
            .build()
        val response = requireClient().unary(
            "AuthService",
            "LoginWithProvider",
            request,
            LoginWithProviderResponse.parser()
        )
        if (!response.hasPlayer() || !response.hasTokenPair()) {
            throw HiveAxylException.transport("login response missing player or token pair")
        }

        session.save(response.tokenPair)
        val logged = response.player.toSdkPlayer()
        setPlayer(logged)
        return logged
    }

    private fun fetchNullablePlayer(): Player? {
        val response = requireClient().unary(
            "AuthService",
            "GetPlayer",
            GetPlayerRequest.getDefaultInstance(),
            GetPlayerResponse.parser()
        )
        if (!response.hasPlayer()) {
            return null
        }
        val restored = response.player.toSdkPlayer()
        setPlayer(restored)
        return restored
    }

    private fun setPlayer(value: Player) {
        synchronized(lock) {
            player = value
        }
    }

    private fun requireClient(): ConnectClient {
        synchronized(lock) {
            return client ?: throw HiveAxylException.notInitialized()
        }
    }

    private fun appleCallbackErrorMessage(code: String, message: String): String {
        val values = listOf(code, message).filter { it.isNotEmpty() }
        if (values.isEmpty()) {
            return "Apple login failed"
        }
        return values.joinToString(": ")
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
