package com.hiveaxyl.sdk

import com.google.protobuf.Timestamp
import com.hiveng.v1.ClientPlatform
import com.hiveng.v1.GetLoginProvidersResponse
import com.hiveng.v1.IdentityProvider
import com.hiveng.v1.MailType
import java.util.Date

data class Player(
    val playerId: String,
    val projectId: String,
    val country: String,
    val email: String,
    val nickname: String,
    val lastLoginPlatform: String,
    val providers: List<String>,
    val createdAt: Date?,
    val lastLoginAt: Date?
)

data class LoginProviders(
    val providers: List<String>,
    val country: String
)

data class Notice(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String,
    val startsAt: Date?,
    val endsAt: Date?,
    val viewCount: Long
)

data class Mail(
    val id: String,
    val mailId: String,
    val projectId: String,
    val type: MailType,
    val title: String,
    val body: String,
    val sender: String,
    val rewardPreview: Map<String, String>,
    val claimed: Boolean,
    val claimableFrom: Date?,
    val expiresAt: Date?,
    val claimedAt: Date?,
    val createdAt: Date?
)

data class PaymentPurchase(
    val id: String,
    val projectId: String,
    val playerId: String,
    val market: String,
    val productType: String,
    val productId: String,
    val packageName: String,
    val purchaseIntentId: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val grantStatus: String,
    val consumeStatus: String,
    val marketOrderId: String,
    val purchasedAt: Date?,
    val verifiedAt: Date?
)

data class PaymentProduct(
    val productId: String,
    val productType: PaymentProductType,
    val appIdentifier: String,
    val marketStatus: String,
    val title: String,
    val description: String,
    val enabled: Boolean
)

data class PushTarget(
    val id: String,
    val projectId: String,
    val playerId: String,
    val fid: String,
    val tokenPreview: String,
    val platform: String,
    val appIdentifier: String,
    val language: String,
    val enabled: Boolean,
    val lastSeenAt: Date?,
    val createdAt: Date?,
    val updatedAt: Date?
)

internal fun com.hiveng.v1.Player.toSdkPlayer(): Player {
    return Player(
        playerId = playerId,
        projectId = projectId,
        country = country,
        email = email,
        nickname = nickname,
        lastLoginPlatform = platformName(lastLoginPlatform),
        providers = providersList.map { providerName(it) },
        createdAt = if (hasCreatedAt()) createdAt.toDate() else null,
        lastLoginAt = if (hasLastLoginAt()) lastLoginAt.toDate() else null
    )
}

internal fun GetLoginProvidersResponse.toSdkLoginProviders(): LoginProviders {
    return LoginProviders(
        providers = providersList.map { providerName(it) },
        country = country
    )
}

internal fun com.hiveng.v1.Notice.toSdkNotice(language: String): Notice {
    return Notice(
        id = id,
        projectId = projectId,
        title = resolveLocalized(titleMap, language),
        body = resolveLocalized(bodyMap, language),
        startsAt = if (hasStartsAt()) startsAt.toDate() else null,
        endsAt = if (hasEndsAt()) endsAt.toDate() else null,
        viewCount = viewCount
    )
}

internal fun com.hiveng.v1.Mail.toSdkMail(language: String): Mail {
    return Mail(
        id = id,
        mailId = mailId,
        projectId = projectId,
        type = type,
        title = resolveLocalized(titleMap, language),
        body = resolveLocalized(bodyMap, language),
        sender = sender,
        rewardPreview = rewardPreviewMap,
        claimed = claimed,
        claimableFrom = if (hasClaimableFrom()) claimableFrom.toDate() else null,
        expiresAt = if (hasExpiresAt()) expiresAt.toDate() else null,
        claimedAt = if (hasClaimedAt()) claimedAt.toDate() else null,
        createdAt = if (hasCreatedAt()) createdAt.toDate() else null
    )
}

internal fun com.hiveng.v1.Purchase.toSdkPaymentPurchase(): PaymentPurchase {
    return PaymentPurchase(
        id = id,
        projectId = projectId,
        playerId = playerId,
        market = market.name.removePrefix("MARKET_").lowercase(),
        productType = productType.name.removePrefix("PRODUCT_TYPE_").lowercase(),
        productId = productId,
        packageName = packageName,
        purchaseIntentId = purchaseIntentId,
        amountMinor = amountMinor,
        currency = currency,
        status = status.name.removePrefix("PURCHASE_STATUS_").lowercase(),
        grantStatus = grantStatus,
        consumeStatus = consumeStatus,
        marketOrderId = marketOrderId,
        purchasedAt = if (hasPurchasedAt()) purchasedAt.toDate() else null,
        verifiedAt = if (hasVerifiedAt()) verifiedAt.toDate() else null
    )
}

internal fun com.hiveng.v1.PaymentProduct.toSdkPaymentProduct(): PaymentProduct {
    return PaymentProduct(
        productId = productId,
        productType = productType.toSdkPaymentProductType(),
        appIdentifier = appIdentifier,
        marketStatus = marketStatus,
        title = title,
        description = description,
        enabled = enabled
    )
}

internal fun com.hiveng.v1.PushTarget.toSdkPushTarget(): PushTarget {
    return PushTarget(
        id = id,
        projectId = projectId,
        playerId = playerId,
        fid = fid,
        tokenPreview = tokenPreview,
        platform = platform,
        appIdentifier = appIdentifier,
        language = language,
        enabled = enabled,
        lastSeenAt = if (hasLastSeenAt()) lastSeenAt.toDate() else null,
        createdAt = if (hasCreatedAt()) createdAt.toDate() else null,
        updatedAt = if (hasUpdatedAt()) updatedAt.toDate() else null
    )
}

private fun com.hiveng.v1.ProductType.toSdkPaymentProductType(): PaymentProductType {
    return when (this) {
        com.hiveng.v1.ProductType.PRODUCT_TYPE_SUBSCRIPTION -> PaymentProductType.SUBSCRIPTION
        else -> PaymentProductType.ONE_TIME
    }
}

private fun Timestamp.toDate(): Date {
    return Date(seconds * 1000L + nanos / 1_000_000L)
}

private fun resolveLocalized(values: Map<String, String>, language: String): String {
    val normalized = language.trim()
    if (normalized.isNotEmpty()) {
        values[normalized]?.let { return it }
        val dash = normalized.indexOf('-')
        if (dash > 0) {
            values[normalized.substring(0, dash)]?.let { return it }
        }
    }
    values["en"]?.let { return it }
    values["ko"]?.let { return it }
    val firstKey = values.keys.sorted().firstOrNull() ?: return ""
    return values[firstKey] ?: ""
}

private fun providerName(provider: IdentityProvider): String {
    return when (provider) {
        IdentityProvider.IDENTITY_PROVIDER_KAKAO -> "kakao"
        IdentityProvider.IDENTITY_PROVIDER_NAVER -> "naver"
        IdentityProvider.IDENTITY_PROVIDER_GOOGLE -> "google"
        IdentityProvider.IDENTITY_PROVIDER_FACEBOOK -> "facebook"
        IdentityProvider.IDENTITY_PROVIDER_APPLE -> "apple"
        IdentityProvider.IDENTITY_PROVIDER_LINE -> "line"
        IdentityProvider.IDENTITY_PROVIDER_TRUECALLER -> "truecaller"
        IdentityProvider.IDENTITY_PROVIDER_PHONE_OTP -> "phone_otp"
        IdentityProvider.IDENTITY_PROVIDER_GUEST -> "guest"
        else -> "unspecified"
    }
}

private fun platformName(platform: ClientPlatform): String {
    return when (platform) {
        ClientPlatform.CLIENT_PLATFORM_WEB -> "web"
        ClientPlatform.CLIENT_PLATFORM_ANDROID -> "android"
        ClientPlatform.CLIENT_PLATFORM_IOS -> "ios"
        ClientPlatform.CLIENT_PLATFORM_DESKTOP -> "desktop"
        else -> "unspecified"
    }
}
