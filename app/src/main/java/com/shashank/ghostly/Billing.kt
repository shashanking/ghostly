package com.shashank.ghostly

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * A pack of tokens, sold for money.
 *
 * [productId] must match the in-app product id typed into the Play Console **exactly**, and the id
 * can never be changed once it has been published — Play keys every purchase ever made off it. The
 * price is not here on purpose: Play owns it, per country and per currency, and the Shop shows
 * whatever [Billing.priceOf] hands back rather than a number baked into the build.
 */
data class TokenPack(val productId: String, val tokens: Int, val name: String, val blurb: String)

object TokenPacks {
    val ALL = listOf(
        TokenPack("tokens_handful", 60, "A handful", "Enough for a hat and a few treats"),
        TokenPack("tokens_pocketful", 200, "A pocketful", "A wardrobe's worth"),
        TokenPack("tokens_hoard", 600, "A hoard", "Everything in the Shop, twice over"),
    )

    private val byId = ALL.associateBy { it.productId }

    fun find(productId: String): TokenPack? = byId[productId]
}

/**
 * Tokens for money, through Play Billing.
 *
 * Deliberately small. One product type (consumable one-time products), one thing to sell, and no
 * state of its own that outlives the screen — what was bought lives in the wallet, and what has
 * already been paid out lives in [Prefs.creditedPurchases].
 *
 * The order every purchase goes through, and why it is this order:
 *
 * 1. Play reports a purchase — through [onPurchasesUpdated] if it happened just now, or through
 *    [refresh] if it completed while the app was dead, which is the case a card-declined-then-
 *    approved purchase actually takes.
 * 2. Anything not [Purchase.PurchaseState.PURCHASED] is left alone. A pending purchase (cash at a
 *    counter, a parent's approval) is not money yet and must not pay out.
 * 3. The purchase token is written to [Prefs.addCreditedPurchase] **before** the tokens are added,
 *    so a crash mid-flight loses a credit rather than repeating one forever.
 * 4. The tokens go in, then the purchase is consumed so the same pack can be bought again.
 *
 * ponytail: the purchase is trusted as Play reports it, with no server check. Everything it buys is
 * cosmetic and lives on this phone, so the worst a forged purchase gets is a hat. The upgrade path
 * when that stops being true is to POST the purchase token to `server/api` and verify it against
 * the Play Developer API there before [credit] runs.
 */
class Billing(
    private val activity: Activity,
    /** Called whenever the balance or the list of prices has changed, so the Shop can redraw. */
    private val onChanged: () -> Unit,
) : PurchasesUpdatedListener {

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    /** Play's own localised price strings, by product id. Empty until the connection is up. */
    private val details = mutableMapOf<String, ProductDetails>()

    /** True once Play has answered with prices — the Shop hides the shelf until then, because a
     *  buy button that cannot say what it costs is worse than no buy button. */
    var ready: Boolean = false
        private set

    private var reconnects = 0

    fun start() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                reconnects = 0
                queryProducts()
                refresh()
            }

            // Play services restarting, or an update to the Play Store itself. Bounded, because a
            // device where billing is simply unavailable would otherwise retry for ever.
            override fun onBillingServiceDisconnected() {
                if (reconnects++ < MAX_RECONNECTS) start()
            }
        })
    }

    fun stop() {
        ready = false
        runCatching { client.endConnection() }
    }

    private fun queryProducts() {
        val products = TokenPacks.ALL.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, queried ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            queried.productDetailsList.forEach { details[it.productId] = it }
            ready = details.isNotEmpty()
            activity.runOnUiThread { onChanged() }
        }
    }

    /**
     * Pays out anything Play is still holding. Called on every start, not only after a purchase:
     * a purchase that completed while the app was closed is reported here and nowhere else.
     */
    fun refresh() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.forEach { settle(it) }
        }
    }

    /** What one pack costs, in the buyer's own currency, as Play formats it. Null until [ready]. */
    fun priceOf(pack: TokenPack): String? =
        details[pack.productId]?.oneTimePurchaseOfferDetails?.formattedPrice

    /** Opens Play's own buy sheet. Does nothing if prices have not arrived yet. */
    fun buy(pack: TokenPack) {
        val product = details[pack.productId] ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return
        purchases?.forEach { settle(it) }
    }

    /**
     * One purchase, from whatever state Play found it in to tokens in the wallet.
     *
     * A purchase can carry more than one product — Play allows a multi-quantity or multi-line
     * order — so every id on it is paid out, not just the first.
     */
    private fun settle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.purchaseToken in Prefs.creditedPurchases(activity)) {
            consume(purchase)
            return
        }

        val packs = purchase.products.mapNotNull { TokenPacks.find(it) }
        if (packs.isEmpty()) return
        val quantity = purchase.quantity.coerceAtLeast(1)

        // Written first, and with commit() rather than apply(), so it is on disk before a token
        // exists that it is meant to account for.
        Prefs.addCreditedPurchase(activity, purchase.purchaseToken)
        packs.forEach { Emotions.addTokens(activity, it.tokens * quantity) }

        // Acknowledging is what stops Play refunding it after three days. Consuming acknowledges
        // too, so this is only for the case where the consume call never lands.
        if (!purchase.isAcknowledged) {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
            ) { }
        }
        consume(purchase)
        activity.runOnUiThread { onChanged() }
    }

    /** Hands the purchase back so the same pack can be bought again. */
    private fun consume(purchase: Purchase) {
        client.consumeAsync(
            ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { _, _ -> }
    }

    companion object {
        private const val MAX_RECONNECTS = 3

        /** Whether this build can sell anything at all — false on a device with no Play Store,
         *  where the Shop shows only what tokens already buy. */
        fun available(context: Context): Boolean =
            runCatching {
                context.packageManager.getPackageInfo("com.android.vending", 0)
            }.isSuccess
    }
}
