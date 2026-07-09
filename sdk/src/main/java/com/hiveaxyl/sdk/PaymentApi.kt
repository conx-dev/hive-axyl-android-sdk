package com.hiveaxyl.sdk

import android.app.Activity
import com.hiveng.v1.Market
import com.hiveng.v1.ListProductsRequest
import com.hiveng.v1.ListProductsResponse
import com.hiveng.v1.ProductType
import com.hiveng.v1.PaymentServiceGetPurchaseRequest
import com.hiveng.v1.PaymentServiceGetPurchaseResponse
import com.hiveng.v1.StartPurchaseRequest
import com.hiveng.v1.StartPurchaseResponse
import com.hiveng.v1.VerifyPurchaseRequest
import com.hiveng.v1.VerifyPurchaseResponse
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

enum class PaymentProductType {
    ONE_TIME,
    SUBSCRIPTION
}

class PaymentApi internal constructor(
    private val executor: ExecutorService
) {
    private val grantPollDelaysMillis = longArrayOf(1_000L, 2_000L, 3_000L, 5_000L)
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

    fun createGooglePlayBillingClient(
        activity: Activity,
        packageName: String = activity.packageName,
        listener: GooglePlayBillingListener
    ): GooglePlayBillingClient {
        return GooglePlayBillingClient(activity, this, packageName, listener)
    }

    suspend fun listGooglePlayProducts(
        packageName: String = "",
        productType: PaymentProductType? = null
    ): List<PaymentProduct> {
        return suspendCoroutine { continuation ->
            listGooglePlayProducts(packageName, productType, continuation.asCallback())
        }
    }

    fun listGooglePlayProducts(
        packageName: String = "",
        productType: PaymentProductType? = null,
        callback: HiveAxylCallback<List<PaymentProduct>>
    ) {
        runAsync(callback) {
            val builder = ListProductsRequest.newBuilder()
                .setMarket(Market.MARKET_GOOGLE_PLAY)
                .setAppIdentifier(packageName)
            if (productType != null) {
                builder.setProductType(productType.toProto())
            }
            val response = requireClient().unary(
                "PaymentService",
                "ListProducts",
                builder.build(),
                ListProductsResponse.parser()
            )
            response.productsList.map { product -> product.toSdkPaymentProduct() }
        }
    }

    suspend fun startGooglePlayPurchase(
        productId: String,
        productType: PaymentProductType = PaymentProductType.ONE_TIME,
        packageName: String = ""
    ): String {
        return suspendCoroutine { continuation ->
            startGooglePlayPurchase(
                productId,
                productType,
                packageName,
                continuation.asCallback()
            )
        }
    }

    fun startGooglePlayPurchase(
        productId: String,
        productType: PaymentProductType = PaymentProductType.ONE_TIME,
        packageName: String = "",
        callback: HiveAxylCallback<String>
    ) {
        if (productId.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("productId is required"))
            return
        }
        runAsync(callback) {
            val request = StartPurchaseRequest.newBuilder()
                .setMarket(Market.MARKET_GOOGLE_PLAY)
                .setProductType(productType.toProto())
                .setProductId(productId)
                .setAppIdentifier(packageName)
                .build()
            val response = requireClient().unary(
                "PaymentService",
                "StartPurchase",
                request,
                StartPurchaseResponse.parser()
            )
            response.purchaseIntentId
        }
    }

    suspend fun verifyGooglePlayPurchase(
        productId: String,
        purchaseToken: String,
        productType: PaymentProductType = PaymentProductType.ONE_TIME,
        packageName: String = "",
        purchaseIntentId: String = ""
    ): PaymentPurchase {
        return suspendCoroutine { continuation ->
            verifyGooglePlayPurchase(
                productId,
                purchaseToken,
                productType,
                packageName,
                purchaseIntentId,
                continuation.asCallback()
            )
        }
    }

    fun verifyGooglePlayPurchase(
        productId: String,
        purchaseToken: String,
        productType: PaymentProductType = PaymentProductType.ONE_TIME,
        packageName: String = "",
        purchaseIntentId: String = "",
        callback: HiveAxylCallback<PaymentPurchase>
    ) {
        if (productId.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("productId is required"))
            return
        }
        if (purchaseToken.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("purchaseToken is required"))
            return
        }
        runAsync(callback) {
            val request = VerifyPurchaseRequest.newBuilder()
                .setMarket(Market.MARKET_GOOGLE_PLAY)
                .setProductType(productType.toProto())
                .setProductId(productId)
                .setPurchaseToken(purchaseToken)
                .setAppIdentifier(packageName)
                .setPurchaseIntentId(purchaseIntentId)
                .build()
            val response = requireClient().unary(
                "PaymentService",
                "VerifyPurchase",
                request,
                VerifyPurchaseResponse.parser()
            )
            if (!response.hasPurchase()) {
                throw HiveAxylException.transport("verify purchase response missing purchase")
            }
            response.purchase.toSdkPaymentPurchase()
        }
    }

    suspend fun getPurchase(purchaseId: String): PaymentPurchase {
        return suspendCoroutine { continuation ->
            getPurchase(purchaseId, continuation.asCallback())
        }
    }

    fun getPurchase(purchaseId: String, callback: HiveAxylCallback<PaymentPurchase>) {
        if (purchaseId.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("purchaseId is required"))
            return
        }
        runAsync(callback) {
            getPurchaseBlocking(purchaseId)
        }
    }

    suspend fun waitForPaymentGrant(
        purchaseId: String,
        timeoutMillis: Long = DEFAULT_GRANT_WAIT_TIMEOUT_MILLIS
    ): PaymentPurchase {
        return suspendCoroutine { continuation ->
            waitForPaymentGrant(purchaseId, timeoutMillis, continuation.asCallback())
        }
    }

    fun waitForPaymentGrant(
        purchaseId: String,
        callback: HiveAxylCallback<PaymentPurchase>
    ) {
        waitForPaymentGrant(purchaseId, DEFAULT_GRANT_WAIT_TIMEOUT_MILLIS, callback)
    }

    fun waitForPaymentGrant(
        purchaseId: String,
        timeoutMillis: Long = DEFAULT_GRANT_WAIT_TIMEOUT_MILLIS,
        callback: HiveAxylCallback<PaymentPurchase>
    ) {
        if (purchaseId.isEmpty()) {
            callback.onError(HiveAxylException.invalidArgument("purchaseId is required"))
            return
        }
        if (timeoutMillis <= 0) {
            callback.onError(HiveAxylException.invalidArgument("timeoutMillis must be positive"))
            return
        }
        runAsync(callback) {
            waitForPaymentGrantBlocking(purchaseId, timeoutMillis)
        }
    }

    private fun requireClient(): ConnectClient {
        synchronized(lock) {
            return client ?: throw HiveAxylException.transport(
                "discovery returned no endpoint for domain: payment"
            )
        }
    }

    private fun getPurchaseBlocking(purchaseId: String): PaymentPurchase {
        val request = PaymentServiceGetPurchaseRequest.newBuilder()
            .setPurchaseId(purchaseId)
            .build()
        val response = requireClient().unary(
            "PaymentService",
            "GetPurchase",
            request,
            PaymentServiceGetPurchaseResponse.parser()
        )
        if (!response.hasPurchase()) {
            throw HiveAxylException.transport("get purchase response missing purchase")
        }
        return response.purchase.toSdkPaymentPurchase()
    }

    private fun waitForPaymentGrantBlocking(
        purchaseId: String,
        timeoutMillis: Long
    ): PaymentPurchase {
        val startedAt = System.nanoTime()
        var purchase = getPurchaseBlocking(purchaseId)
        if (isGrantFinished(purchase) || isPurchaseFailed(purchase)) {
            return purchase
        }
        var delayIndex = 0
        var elapsed = elapsedMillis(startedAt)
        while (elapsed < timeoutMillis) {
            val remainingMillis = timeoutMillis - elapsed
            val delayMillis = nextDelayMillis(delayIndex).coerceAtMost(remainingMillis)
            sleep(delayMillis)
            purchase = getPurchaseBlocking(purchaseId)
            if (isGrantFinished(purchase) || isPurchaseFailed(purchase)) {
                return purchase
            }
            delayIndex += 1
            elapsed = elapsedMillis(startedAt)
        }
        return purchase
    }

    private fun nextDelayMillis(index: Int): Long {
        if (index < grantPollDelaysMillis.size) {
            return grantPollDelaysMillis[index]
        }
        return grantPollDelaysMillis.last()
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HiveAxylException.transport("payment grant wait interrupted")
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

    private fun elapsedMillis(startedAt: Long): Long {
        return (System.nanoTime() - startedAt) / 1_000_000L
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

    companion object {
        const val DEFAULT_GRANT_WAIT_TIMEOUT_MILLIS: Long = 30_000L
    }
}

private fun isGrantFinished(purchase: PaymentPurchase): Boolean {
    return purchase.grantStatus == "delivered" ||
        purchase.grantStatus == "not_required" ||
        purchase.grantStatus == "client_pending"
}

private fun isPurchaseFailed(purchase: PaymentPurchase): Boolean {
    return when (purchase.status) {
        "failed", "canceled", "refunded", "expired" -> true
        else -> false
    }
}

private fun PaymentProductType.toProto(): ProductType {
    return when (this) {
        PaymentProductType.ONE_TIME -> ProductType.PRODUCT_TYPE_ONE_TIME
        PaymentProductType.SUBSCRIPTION -> ProductType.PRODUCT_TYPE_SUBSCRIPTION
    }
}
