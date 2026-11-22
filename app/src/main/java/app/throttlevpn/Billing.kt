package app.throttlevpn

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** One-time in-app product "full_access" (set to 0.99 EUR in the Play Console). */
object Billing {
    const val PRODUCT_ID = "full_access"

    private lateinit var app: Context
    private lateinit var client: BillingClient
    private var details: ProductDetails? = null
    private val main = Handler(Looper.getMainLooper())

    var price by mutableStateOf("\u20AC0.99")
        private set

    private fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }

    fun init(ctx: Context) {
        if (::client.isInitialized) return
        app = ctx.applicationContext
        client = BillingClient.newBuilder(app)
            .setListener(PurchasesUpdatedListener { result, purchases ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> if (purchases != null) handle(purchases)
                    BillingClient.BillingResponseCode.USER_CANCELED -> {}
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                    else -> toast("Purchase failed: ${result.debugMessage.ifBlank { "error ${result.responseCode}" }}")
                }
            })
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect(null)
    }

    private fun connect(then: (() -> Unit)?) {
        if (client.isReady) {
            then?.invoke()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    fetchDetails(null)
                    queryOwned()
                    then?.invoke()
                } else if (then != null) {
                    toast("Google Play Billing is not available on this device.")
                }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun fetchDetails(cb: ((ProductDetails) -> Unit)?) {
        details?.let { cb?.invoke(it); return }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        ).build()
        client.queryProductDetailsAsync(params) { r, res ->
            val d = if (r.responseCode == BillingClient.BillingResponseCode.OK) res.productDetailsList.firstOrNull() else null
            if (d != null) {
                details = d
                d.oneTimePurchaseOfferDetails?.formattedPrice?.let { price = it }
                cb?.invoke(d)
            } else if (cb != null) {
                toast("Purchase currently unavailable. Install the app from Google Play and try again.")
            }
        }
    }

    private fun queryOwned() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { r, purchases ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED && PRODUCT_ID in it.products
                }
                owned.forEach { acknowledge(it) }
                Access.unlock(owned.isNotEmpty())
            }
        }
    }

    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        ) { }
    }

    private fun handle(purchases: List<Purchase>) {
        for (p in purchases) {
            if (PRODUCT_ID !in p.products) continue
            when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    acknowledge(p)
                    Access.unlock(true)
                    toast("Thank you! Full version unlocked.")
                }
                Purchase.PurchaseState.PENDING -> toast("Payment pending. The app unlocks when it completes.")
                else -> {}
            }
        }
    }

    fun refresh() {
        if (::client.isInitialized && client.isReady) queryOwned()
    }

    fun buy(activity: Activity) {
        connect {
            fetchDetails { d ->
                val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())
                ).build()
                client.launchBillingFlow(activity, params)
            }
        }
    }
}
