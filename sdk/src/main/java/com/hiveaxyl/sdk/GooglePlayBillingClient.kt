package com.hiveaxyl.sdk

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

data class GooglePlaySubscriptionInfo(
    val status: String,
    val orderId: String,
    val isAutoRenewing: Boolean,
    val purchasedAtMillis: Long
)

data class GooglePlayProduct(
    val productId: String,
    val productType: PaymentProductType,
    val title: String,
    val name: String,
    val description: String,
    val formattedPrice: String,
    val canPurchase: Boolean,
    val subscriptionInfo: GooglePlaySubscriptionInfo? = null
)

interface GooglePlayBillingListener {
    fun onStatus(message: String) {}

    fun onProductsFound(products: List<GooglePlayProduct>) {}

    fun onPurchaseVerified(purchase: PaymentPurchase) {}

    fun onError(error: Throwable) {}
}

class GooglePlayBillingClient(
    private val activity: Activity,
    private val payment: PaymentApi,
    private val packageName: String = activity.packageName,
    private val listener: GooglePlayBillingListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readyActions = mutableListOf<() -> Unit>()
    private var pendingProducts = emptyList<PaymentProduct>()
    private var productDetailsById = emptyMap<String, ProductDetails>()
    private var productQueryId = 0
    private var isBillingConnecting = false
    private val purchaseIntentIds = mutableMapOf<String, String>()
    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        onPurchasesUpdated(result, purchases)
    }

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    fun checkProducts(products: List<PaymentProduct>) {
        runOnMain {
            checkProductsOnMain(products)
        }
    }

    fun launchPurchase(
        productId: String,
        productType: PaymentProductType = PaymentProductType.ONE_TIME
    ) {
        runOnMain {
            launchPurchaseOnMain(productId, productType)
        }
    }

    fun syncUnfinishedPurchases(products: List<PaymentProduct> = pendingProducts) {
        runOnMain {
            syncUnfinishedPurchasesOnMain(products)
        }
    }

    fun close() {
        runOnMain {
            isBillingConnecting = false
            productQueryId += 1
            readyActions.clear()
            purchaseIntentIds.clear()
            mainHandler.removeCallbacksAndMessages(null)
            if (billingClient.isReady) {
                billingClient.endConnection()
            }
        }
    }

    private fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        runOnMain {
            Log.d(
                TAG,
                "onPurchasesUpdated code=${result.responseCode} message=${result.debugMessage} purchases=${purchases?.size ?: 0}"
            )
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> handlePurchaseSuccess(purchases)
                BillingClient.BillingResponseCode.USER_CANCELED -> listener.onStatus("결제가 취소되었습니다.")
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    listener.onStatus("이미 보유 중인 상품입니다. 미완료 결제 동기화 중...")
                    syncUnfinishedPurchasesOnMain(pendingProducts)
                }
                else -> listener.onError(billingException("결제 실패", result))
            }
        }
    }

    private fun checkProductsOnMain(products: List<PaymentProduct>) {
        if (products.isEmpty()) {
            listener.onError(HiveAxylException.invalidArgument("서버에서 노출 가능한 상품을 찾지 못했습니다."))
            return
        }
        pendingProducts = products
        productDetailsById = emptyMap()
        productQueryId += 1
        listener.onProductsFound(products.map { product -> productInfo(product) })
        runWhenBillingReady("Google Play Billing 연결 중...") {
            queryProducts()
        }
    }

    private fun launchPurchaseOnMain(
        productId: String,
        productType: PaymentProductType
    ) {
        val details = productDetailsById[productId]
        if (details == null) {
            listener.onError(HiveAxylException.invalidArgument("결제할 상품 정보가 없습니다. 상품 조회를 먼저 실행하세요."))
            return
        }
        if (!billingClient.isReady) {
            runWhenBillingReady("Google Play Billing 연결 중...") {
                launchPurchaseOnMain(productId, productType)
            }
            return
        }

        listener.onStatus("구매 시작 서버 기록 중...")
        payment.startGooglePlayPurchase(
            productId,
            productType,
            packageName,
            apiCallback(onSuccess = { purchaseIntentId ->
                purchaseIntentIds[productId] = purchaseIntentId
                openBillingFlow(productId, details)
            })
        )
    }

    private fun syncUnfinishedPurchasesOnMain(products: List<PaymentProduct>) {
        if (products.isNotEmpty()) {
            pendingProducts = products
        }
        if (pendingProducts.isEmpty()) {
            listener.onError(HiveAxylException.invalidArgument("동기화할 Google Play 상품 목록이 없습니다."))
            return
        }
        runWhenBillingReady("미완료 Google Play 결제 조회 중...") {
            queryUnfinishedPurchases()
        }
    }

    private fun runWhenBillingReady(message: String, action: () -> Unit) {
        listener.onStatus(message)
        if (billingClient.isReady) {
            isBillingConnecting = false
            action()
            return
        }
        readyActions.add(action)
        if (isBillingConnecting) {
            return
        }
        isBillingConnecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                runOnMain {
                    isBillingConnecting = false
                    if (!isOk(result)) {
                        readyActions.clear()
                        listener.onError(billingException("Billing 연결 실패", result))
                        return@runOnMain
                    }
                    val actions = readyActions.toList()
                    readyActions.clear()
                    actions.forEach { readyAction -> readyAction() }
                }
            }

            override fun onBillingServiceDisconnected() {
                runOnMain {
                    isBillingConnecting = false
                    listener.onError(HiveAxylException.transport("Billing 서비스 연결이 끊어졌습니다."))
                }
            }
        })
    }

    private fun openBillingFlow(
        productId: String,
        details: ProductDetails
    ) {
        listener.onStatus("Google Play 결제창 여는 중...")
        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        val offerToken = purchaseOfferToken(details)
        if (offerToken.isNullOrBlank()) {
            listener.onError(
                HiveAxylException.invalidArgument("${productTypeLabel(details)} 상품의 구매 offerToken을 찾지 못했습니다.")
            )
            return
        }
        productParamsBuilder.setOfferToken(offerToken)
        val productParams = productParamsBuilder.build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        Log.d(
            TAG,
            "launchBillingFlow product=$productId type=${details.productType} code=${result.responseCode} message=${result.debugMessage}"
        )
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            listener.onStatus("이미 보유 중인 상품입니다. 미완료 결제 동기화 중...")
            syncUnfinishedPurchasesOnMain(pendingProducts)
            return
        }
        if (!isOk(result)) {
            listener.onError(billingException("결제창 실행 실패", result))
        }
    }

    private fun handlePurchaseSuccess(purchases: MutableList<Purchase>?) {
        val completedPurchases = purchases.orEmpty()
            .filter { purchase -> purchase.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (completedPurchases.isEmpty()) {
            listener.onError(HiveAxylException.transport("구매 성공 응답에 구매 정보가 없습니다."))
            return
        }
        completedPurchases.forEach { purchase ->
            verifyPurchaseProducts(purchase, false)
        }
    }

    private fun verifyPurchaseProducts(
        purchase: Purchase,
        isSync: Boolean
    ) {
        if (purchase.purchaseToken.isBlank()) {
            listener.onError(HiveAxylException.transport("구매 응답에 purchaseToken이 없습니다."))
            return
        }
        purchase.products.forEach { productId ->
            val product = pendingProducts.firstOrNull { item -> item.productId == productId }
            if (product == null) {
                listener.onError(HiveAxylException.invalidArgument("구매 상품 타입을 찾지 못했습니다: $productId"))
                return@forEach
            }
            val intentId = purchaseIntentIds.remove(productId).orEmpty()
            verifyGooglePlayPurchase(
                product.productId,
                purchase.purchaseToken,
                product.productType,
                intentId,
                isSync
            )
        }
    }

    private fun verifyGooglePlayPurchase(
        productId: String,
        purchaseToken: String,
        productType: PaymentProductType,
        purchaseIntentId: String,
        isSync: Boolean
    ) {
        listener.onStatus("구매 성공 응답 수신. 서버 검증 중...")
        payment.verifyGooglePlayPurchase(
            productId,
            purchaseToken,
            productType,
            packageName,
            purchaseIntentId,
            apiCallback(
                { purchase -> listener.onPurchaseVerified(purchase) },
                { error ->
                    if (isSync && isDuplicateReceipt(error)) {
                        listener.onStatus("이미 서버 처리된 Google Play 결제입니다: $productId")
                        return@apiCallback
                    }
                    listener.onError(error)
                }
            )
        )
    }

    private fun queryProducts() {
        val queryId = productQueryId + 1
        productQueryId = queryId
        listener.onStatus("상품 조회 중: ${pendingProducts.size}개")
        mainHandler.postDelayed({
            handleProductQueryTimeout(queryId)
        }, PRODUCT_QUERY_TIMEOUT_MS)

        val detailsById = linkedMapOf<String, ProductDetails>()
        val groupedProducts = pendingProducts.groupBy { product -> product.productType }
        var remainingQueryCount = groupedProducts.size
        groupedProducts.forEach { (productType, products) ->
            queryProductsByType(queryId, productType, products, detailsById) {
                remainingQueryCount -= 1
                if (remainingQueryCount == 0) {
                    completeProductQuery(queryId, detailsById)
                }
            }
        }
    }

    private fun queryProductsByType(
        queryId: Int,
        productType: PaymentProductType,
        products: List<PaymentProduct>,
        detailsById: MutableMap<String, ProductDetails>,
        onComplete: () -> Unit
    ) {
        val billingType = billingProductType(productType)
        val queryProducts = products.map { product ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product.productId)
                .setProductType(billingType)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(queryProducts)
            .build()
        Log.d(
            TAG,
            "queryProductDetails type=$billingType ids=${products.joinToString { product -> product.productId }}"
        )
        billingClient.queryProductDetailsAsync(params) { result, response ->
            runOnMain {
                if (queryId != productQueryId) {
                    return@runOnMain
                }
                Log.d(
                    TAG,
                    "queryProductDetails result type=$billingType code=${result.responseCode} message=${result.debugMessage} fetched=${response.productDetailsList.size} unfetched=${response.unfetchedProductList.size}"
                )
                if (!isOk(result)) {
                    productQueryId += 1
                    listener.onError(billingException("상품 조회 실패($billingType)", result))
                    return@runOnMain
                }
                response.productDetailsList.forEach { item ->
                    detailsById[item.productId] = item
                }
                response.unfetchedProductList.forEach { item ->
                    Log.w(TAG, "unfetched product=${item.productId} status=${unfetchedStatus(item.statusCode)}")
                }
                onComplete()
            }
        }
    }

    private fun completeProductQuery(
        queryId: Int,
        detailsById: Map<String, ProductDetails>
    ) {
        if (queryId != productQueryId) {
            return
        }
        productQueryId += 1
        if (detailsById.isEmpty()) {
            listener.onProductsFound(pendingProducts.map { product ->
                productInfo(product, "가격 조회 실패")
            })
            listener.onError(HiveAxylException.transport("Google Play에서 판매 가능한 상품을 찾지 못했습니다."))
            return
        }

        productDetailsById = detailsById.toMap()
        queryCurrentSubscriptions(productQueryId, detailsById)
    }

    private fun queryCurrentSubscriptions(
        queryId: Int,
        detailsById: Map<String, ProductDetails>
    ) {
        val hasSubscription = pendingProducts.any { product ->
            product.productType == PaymentProductType.SUBSCRIPTION
        }
        if (!hasSubscription) {
            productQueryId += 1
            listener.onProductsFound(productsWithSubscriptionInfo(detailsById, emptyMap()))
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            runOnMain {
                if (queryId != productQueryId) {
                    return@runOnMain
                }
                productQueryId += 1
                if (!isOk(result)) {
                    listener.onProductsFound(productsWithSubscriptionInfo(detailsById, emptyMap()))
                    listener.onError(billingException("구독 조회 실패", result))
                    return@runOnMain
                }

                val subscriptions = purchases
                    .flatMap { purchase -> subscriptionInfos(purchase) }
                    .toMap()
                listener.onProductsFound(productsWithSubscriptionInfo(detailsById, subscriptions))
            }
        }
    }

    private fun queryUnfinishedPurchases() {
        val billingTypes = pendingProducts
            .map { product -> billingProductType(product.productType) }
            .distinct()
        if (billingTypes.isEmpty()) {
            listener.onError(HiveAxylException.invalidArgument("동기화할 Google Play 상품 타입이 없습니다."))
            return
        }

        var remainingQueryCount = billingTypes.size
        var purchasedCount = 0
        var pendingCount = 0
        billingTypes.forEach { billingType ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(billingType)
                .build()
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                runOnMain {
                    if (!isOk(result)) {
                        listener.onError(billingException("미완료 결제 조회 실패($billingType)", result))
                    } else {
                        purchases.forEach { purchase ->
                            when (purchase.purchaseState) {
                                Purchase.PurchaseState.PURCHASED -> {
                                    purchasedCount += 1
                                    verifyPurchaseProducts(purchase, true)
                                }
                                Purchase.PurchaseState.PENDING -> pendingCount += 1
                            }
                        }
                    }
                    remainingQueryCount -= 1
                    if (remainingQueryCount == 0 && purchasedCount == 0) {
                        if (pendingCount > 0) {
                            listener.onStatus("결제 대기 중인 Google Play 구매가 있습니다.")
                            return@runOnMain
                        }
                        listener.onStatus("미완료 Google Play 결제가 없습니다.")
                    }
                }
            }
        }
    }

    private fun productsWithSubscriptionInfo(
        detailsById: Map<String, ProductDetails>,
        subscriptions: Map<String, GooglePlaySubscriptionInfo>
    ): List<GooglePlayProduct> {
        return pendingProducts.map { product ->
            val details = detailsById[product.productId]
            val subscriptionInfo = subscriptions[product.productId]
            if (details == null) {
                productInfo(product, "가격 조회 실패", subscriptionInfo)
            } else {
                productInfo(details, subscriptionInfo)
                    ?: productInfo(product, "가격 조회 실패", subscriptionInfo)
            }
        }
    }

    private fun handleProductQueryTimeout(queryId: Int) {
        if (queryId != productQueryId) {
            return
        }
        productQueryId += 1
        listener.onProductsFound(pendingProducts.map { product ->
            productInfo(product, "가격 조회 실패")
        })
        listener.onError(
            HiveAxylException.transport(
                "Google Play 상품 상세 조회가 15초 동안 응답하지 않았습니다. Play Store 계정, 테스트 트랙, 상품 상태를 확인하세요."
            )
        )
    }

    private fun productInfo(
        details: ProductDetails,
        subscriptionInfo: GooglePlaySubscriptionInfo? = null
    ): GooglePlayProduct? {
        val product = pendingProducts.firstOrNull { item -> item.productId == details.productId }
            ?: return null
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
        val canPurchase = subscriptionInfo == null
        return GooglePlayProduct(
            details.productId,
            product.productType,
            details.title,
            details.name,
            details.description,
            offer?.formattedPrice ?: subscriptionPrice(details),
            canPurchase,
            subscriptionInfo
        )
    }

    private fun productInfo(
        product: PaymentProduct,
        formattedPrice: String = "가격 조회 중",
        subscriptionInfo: GooglePlaySubscriptionInfo? = null
    ): GooglePlayProduct {
        return GooglePlayProduct(
            product.productId,
            product.productType,
            product.title,
            product.productId,
            product.description,
            formattedPrice,
            false,
            subscriptionInfo
        )
    }

    private fun subscriptionInfos(purchase: Purchase): List<Pair<String, GooglePlaySubscriptionInfo>> {
        val info = GooglePlaySubscriptionInfo(
            purchaseStateLabel(purchase.purchaseState),
            purchase.orderId.orEmpty(),
            purchase.isAutoRenewing,
            purchase.purchaseTime
        )
        return purchase.products.map { productId -> productId to info }
    }

    private fun purchaseStateLabel(state: Int): String {
        return when (state) {
            Purchase.PurchaseState.PURCHASED -> "구독 중"
            Purchase.PurchaseState.PENDING -> "결제 대기"
            else -> "상태 미확인"
        }
    }

    private fun billingProductType(productType: PaymentProductType): String {
        return when (productType) {
            PaymentProductType.ONE_TIME -> BillingClient.ProductType.INAPP
            PaymentProductType.SUBSCRIPTION -> BillingClient.ProductType.SUBS
        }
    }

    private fun productTypeLabel(details: ProductDetails): String {
        return when (details.productType) {
            BillingClient.ProductType.INAPP -> "일회성"
            BillingClient.ProductType.SUBS -> "구독"
            else -> details.productType
        }
    }

    private fun purchaseOfferToken(details: ProductDetails): String? {
        return when (details.productType) {
            BillingClient.ProductType.INAPP -> details.oneTimePurchaseOfferDetailsList
                ?.firstOrNull()
                ?.offerToken
            BillingClient.ProductType.SUBS -> details.subscriptionOfferDetails
                ?.firstOrNull()
                ?.offerToken
            else -> null
        }
    }

    private fun subscriptionPrice(details: ProductDetails): String {
        val phase = details.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
        return phase?.formattedPrice.orEmpty()
    }

    private fun isOk(result: BillingResult): Boolean {
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun billingMessage(result: BillingResult): String {
        val message = result.debugMessage
        if (message.isNotBlank()) {
            return "${result.responseCode}: $message"
        }
        return result.responseCode.toString()
    }

    private fun billingException(prefix: String, result: BillingResult): HiveAxylException {
        return HiveAxylException.transport("$prefix: ${billingMessage(result)}")
    }

    private fun isDuplicateReceipt(error: Throwable): Boolean {
        val sdkError = error as? HiveAxylException ?: return false
        return sdkError.code == "DUPLICATE_RECEIPT"
    }

    private fun unfetchedStatus(status: Int): String {
        return when (status) {
            0 -> "UNKNOWN"
            2 -> "INVALID_PRODUCT_ID_FORMAT"
            3 -> "PRODUCT_NOT_FOUND"
            4 -> "NO_ELIGIBLE_OFFER"
            else -> "status=$status"
        }
    }

    private fun <T> apiCallback(
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit = { error -> listener.onError(error) }
    ): HiveAxylCallback<T> {
        return object : HiveAxylCallback<T> {
            override fun onSuccess(value: T) {
                runOnMain {
                    onSuccess(value)
                }
            }

            override fun onError(error: Throwable) {
                runOnMain {
                    onFailure(error)
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        mainHandler.post(block)
    }

    private companion object {
        const val TAG = "HiveAxylBilling"
        const val PRODUCT_QUERY_TIMEOUT_MS = 15_000L
    }
}
