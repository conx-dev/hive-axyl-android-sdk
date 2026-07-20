# Hive Axyl Android SDK

Hive Axyl Android SDK is a Kotlin Android library for game clients. It provides authentication, session persistence, notices, mailbox, payments, and remote push APIs over Hive Axyl platform services.

## Requirements

- Android `minSdk` 23 or higher
- Android Gradle Plugin 8.x or higher
- AndroidX enabled
- Java 17 for compilation
- Kotlin app code, or Java code through callback APIs

## Installation

Add Maven Central to your Gradle repositories:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the SDK dependency to your app module:

```kotlin
dependencies {
    implementation("io.github.conx-dev:hive-axyl-android-sdk:0.2.0")
}
```

## Initialize

Create a client with `HiveAxylSdk.createHiveAxyl` and initialize it once before calling domain APIs.

```kotlin
import com.hiveaxyl.sdk.HiveAxylConfig
import com.hiveaxyl.sdk.HiveAxylSdk

val hive = HiveAxylSdk.createHiveAxyl(
    HiveAxylConfig(
        projectId = "PROJECT_ID",
        apiKey = "CLIENT_API_KEY",
        context = applicationContext,
        clientVersion = BuildConfig.VERSION_NAME
    )
)

hive.initialize()
```

Every API supports both coroutine and callback styles:

```kotlin
hive.initialize(object : HiveAxylCallback<Unit> {
    override fun onSuccess(value: Unit) {
    }

    override fun onError(error: Throwable) {
    }
})
```

## Configuration

| Option | Required | Description |
| --- | --- | --- |
| `projectId` | Yes | Hive Axyl project ID. |
| `apiKey` | Yes | Client API key issued for the project. |
| `gatewayUrl` | No | Discovery gateway URL. Empty values fall back to the SDK default gateway. |
| `context` | No | Enables the default persisted session storage and is required for guest login. |
| `clientVersion` | No | Client version reported during discovery. |
| `language` | No | Language tag used for localized platform content. |
| `debug` | No | Enables SDK debug logging. |
| `persistSession` | No | `false` forces in-memory session storage. |
| `tokenStorage` | No | Custom token storage implementation. |
| `httpClient` | No | Shared OkHttp client. |
| `executor` | No | Executor for callback-style calls. |

If `context` is provided and `persistSession` is not set, the SDK stores sessions in `SharedPreferences`. If `context` is omitted, sessions are kept in memory.

## Authentication

Fetch enabled login providers before showing login UI:

```kotlin
val providers = hive.auth.getLoginProviders()
```

Supported auth entry points:

- `hive.auth.loginAsGuest()`
- `hive.auth.loginWithGoogle(idToken)`
- `hive.auth.loginWithFacebook(accessToken)`
- `hive.auth.startAppleLogin(clientId, returnUrl)`
- `hive.auth.completeAppleLogin(uri)`
- `hive.auth.restoreSession()`
- `hive.auth.logout()`
- `hive.auth.currentPlayer()`

Google and Facebook tokens are obtained by your app through their provider SDKs. For Apple, open the URL returned by `startAppleLogin`, then pass the callback `Uri` unchanged to `completeAppleLogin`. Provide `context` in `HiveAxylConfig` so the pending Apple login survives app process recreation.

On the first guest login, the SDK creates a cryptographically random installation credential in `SharedPreferences`. It is stored separately from session tokens, remains after `logout()`, and is unaffected by `persistSession`. Identity-provider login neither creates nor uses it. Guest login fails before sending a request when durable storage is unavailable. Clearing app storage can create a new guest account, and the previous guest account may not be recoverable.

## Payments

Use `hive.payment` for product listing, purchase start, and receipt verification. The SDK also includes `GooglePlayBillingClient` as a helper for Google Play Billing integration.

Google Play receipt validation credentials must be configured in the Hive Axyl console. They are not stored in the client SDK.

## Notices, Mailbox, and Push

After `initialize()`, the same client exposes:

- `hive.notice` for active notices
- `hive.mailbox` for player mailbox operations
- `hive.push` for remote push target registration

Remote push delivery still requires your app to integrate Firebase Cloud Messaging and pass the device token to Hive Axyl.

## Error Handling

Domain errors are surfaced as SDK exceptions. Branch on exception type or error code instead of parsing messages.

```kotlin
try {
    val player = hive.auth.loginAsGuest()
} catch (banned: BannedException) {
} catch (maintenance: MaintenanceException) {
} catch (error: HiveAxylException) {
}
```

## R8 and ProGuard

The SDK publishes consumer R8 rules with the AAR. Apps normally do not need additional keep rules for Hive Axyl SDK classes.

## Release Policy

Use a fixed SDK version in production builds. Maven Central artifacts are immutable, so fixes are released as new versions.

## License and Support

Use of this SDK is governed by the Hive Axyl license or service agreement for your project. For support, contact your Hive Axyl representative or support channel.
