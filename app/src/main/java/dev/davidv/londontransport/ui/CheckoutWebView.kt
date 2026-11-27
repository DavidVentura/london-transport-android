package dev.davidv.londontransport.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.davidv.londontransport.santander.Checkout

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CheckoutWebView(checkout: Checkout, onReturn: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
  var web by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  BackHandler { if (canGoBack) web?.goBack() else onClose() }
  error?.let { Text(it) }
  AndroidView(modifier = modifier, factory = { context ->
    WebView(context).apply {
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.setSupportMultipleWindows(false)
      CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
          val url = request.url
          if (url.scheme == "londontransport") {
            onReturn(url.toString())
            return true
          }
          if (url.scheme == "https") {
            if (request.isForMainFrame && request.method == "GET" && url.host != checkout.url.host) {
              view.loadUrl(url.toString())
              return true
            }
            return false
          }
          if (url.scheme == "http") {
            error = "The payment page tried to use an insecure connection."
            return true
          }
          val intent = try {
            (if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME).apply {
              component = null
              selector = null
              setPackage(null)
            } else Intent(Intent.ACTION_VIEW, url)).addCategory(Intent.CATEGORY_BROWSABLE)
          } catch (e: java.net.URISyntaxException) {
            error = "The banking-app link is invalid."
            return true
          }
          try { context.startActivity(intent) } catch (e: ActivityNotFoundException) {
            error = "No installed app can open this bank verification link. Return to checkout to continue."
          }
          return true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) {
          canGoBack = view.canGoBack()
          if (Uri.parse(url).scheme == "londontransport") onReturn(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
          if (request.isForMainFrame) error = "Could not load checkout. Check your account before starting another payment."
        }
      }
      loadUrl(checkout.url.toString(), checkout.headers)
      web = this
    }
  }, onRelease = {
    CookieManager.getInstance().flush()
    it.destroy()
  })
}
