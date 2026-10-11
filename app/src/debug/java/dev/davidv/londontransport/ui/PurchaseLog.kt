package dev.davidv.londontransport.ui

import android.util.Log

/** Where the purchase pages go is how the end of a purchase will be recognised, once it is known. */
fun logPurchaseNavigation(url: String) {
  Log.i("PurchaseWebView", url)
}

/** Lets `adb forward` reach the purchase pages' DevTools when one of them misbehaves. */
fun enablePurchaseInspection() {
  android.webkit.WebView.setWebContentsDebuggingEnabled(true)
}
