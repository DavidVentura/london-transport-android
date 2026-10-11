package dev.davidv.londontransport.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.davidv.londontransport.santander.Account
import dev.davidv.londontransport.santander.WebsitePass
import dev.davidv.londontransport.santander.purchaseCompleted
import dev.davidv.londontransport.santander.signInPage
import dev.davidv.londontransport.santander.signInScript

/**
 * The purchase runs entirely on the website: login, the hosted card page and the bank's 3-D Secure
 * pages. Those last ones live on each bank's own domain, so any https page is allowed in here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PurchaseWebView(
  pass: WebsitePass,
  account: Account?,
  onClose: () -> Unit,
  onCompleted: () -> Unit,
) {
  var web by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }

  BackHandler { if (canGoBack) web?.goBack() else onClose() }

  // The station sheet is a popup window above the activity, so the purchase replaces the whole app
  // rather than sitting under it.
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
      TopAppBar(
        title = { Text("Buy ${pass.label}") },
        navigationIcon = {
          IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        },
      )
      AndroidView(
        modifier = Modifier.fillMaxWidth().weight(1f),
        factory = { context ->
          enablePurchaseInspection()
          WebView(context).apply {
            // AndroidView hands out WRAP_CONTENT, under which the WebView resolves CSS `vh` to zero
            // and the site's cookie banner, sized in vh, collapses over the page.
            layoutParams =
              ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // target=_blank and window.open land in this same view instead of nowhere.
            settings.setSupportMultipleWindows(false)
            // The card page is a frame from another tfl.gov.uk host, and bank 3-D Secure frames
            // come from anywhere; both need their own cookies.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient =
              object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                  view: WebView,
                  request: WebResourceRequest,
                ): Boolean {
                  val url = request.url
                  if (url.scheme == "https") return false
                  // Banks hand off to their own app this way to approve the payment. A page must not
                  // pick the component itself, so only the browsable view intent goes out.
                  val intent =
                    (if (url.scheme == "intent") {
                        Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME).apply {
                          component = null
                          selector = null
                        }
                      } else {
                        Intent(Intent.ACTION_VIEW, url)
                      })
                      .addCategory(Intent.CATEGORY_BROWSABLE)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  try {
                    context.startActivity(intent)
                  } catch (e: ActivityNotFoundException) {
                    logPurchaseNavigation("no app for $url")
                  }
                  return true
                }

                // The sign-in page is reached both by a full load and by the site's own client-side
                // navigation, which only the history callback sees; the script fills empty fields
                // only, so running it from both is harmless.
                override fun onPageFinished(view: WebView, url: String) {
                  fillSignIn(view, url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) {
                  logPurchaseNavigation(url)
                  canGoBack = view.canGoBack()
                  fillSignIn(view, url)
                  val visited = Uri.parse(url)
                  if (purchaseCompleted(visited.host, visited.path)) {
                    CookieManager.getInstance().flush()
                    onCompleted()
                  }
                }

                private fun fillSignIn(view: WebView, url: String) {
                  if (account == null) return
                  val visited = Uri.parse(url)
                  if (!signInPage(visited.host, visited.path)) return
                  view.evaluateJavascript(signInScript(account.email, account.password), null)
                }
              }
            loadUrl(pass.url)
            web = this
          }
        },
        // CookieManager writes to disk on its own schedule; the website login must survive the app
        // being closed right after.
        onRelease = {
          CookieManager.getInstance().flush()
          it.destroy()
        },
      )
    }
  }
}
