package dev.davidv.londontransport.santander

import kotlinx.serialization.json.Json

private const val WEBSITE_HOST = "santandercycles.tfl.gov.uk"
private const val PURCHASE_PAGE = "https://$WEBSITE_HOST/purchase"
private const val PURCHASE_COMPLETE = "/purchase/complete"
private const val SIGN_IN = "/sign-in"

/** The page the website lands on once the hosted card page has taken the payment. */
fun purchaseCompleted(host: String?, path: String?): Boolean =
  host == WEBSITE_HOST && path == PURCHASE_COMPLETE

fun signInPage(host: String?, path: String?): Boolean = host == WEBSITE_HOST && path == SIGN_IN

/**
 * Fills the website's sign-in form with the account the app already logs in with; the rider still
 * taps Log in. The form is React's, which only notices a value set through the native setter and
 * announced with an input event, and it can render after the page has loaded, so this waits for it.
 */
fun signInScript(email: String, password: Password): String {
  val quotedEmail = Json.encodeToString(email)
  val quotedPassword = Json.encodeToString(password.value)
  return """
    (function (email, password) {
      if (location.host !== "$WEBSITE_HOST") return;
      var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
      function fill(name, value) {
        var field = document.querySelector('input[name="' + name + '"]');
        if (!field) return false;
        if (!field.value) {
          setter.call(field, value);
          field.dispatchEvent(new Event("input", { bubbles: true }));
        }
        return true;
      }
      var tries = 0;
      var timer = setInterval(function () {
        var filledEmail = fill("bssUsername", email);
        var filledPassword = fill("bssPassword", password);
        if ((filledEmail && filledPassword) || ++tries > 50) clearInterval(timer);
      }, 100);
    })($quotedEmail, $quotedPassword);
  """.trimIndent()
}

/** The website's own purchase pages, which take care of the card and 3-D Secure in the browser. */
enum class WebsitePass(val label: String, private val idx: Int?) {
  Single("Single ride", 3),
  DayPass("Day pass", 2),
  Monthly("Monthly", 1),
  Annual("Annual", null);

  val url: String
    get() = if (idx == null) PURCHASE_PAGE else "$PURCHASE_PAGE?idx=$idx"
}
