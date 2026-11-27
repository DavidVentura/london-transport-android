package dev.davidv.londontransport.santander

/**
 * A docked bike's "SCAN TO UNLOCK" sticker encodes a Lyft short link whose payload is
 * `rlon.lft.to/<code>` (no scheme). Accept only that shape so pointing the camera at an
 * unrelated QR can never start an unlock.
 *
 * The wire value sent as `qr_code` is the raw scanned payload; the backend's pass gate
 * answers before it resolves the code, so URL-vs-bare-code is unconfirmed against a live
 * account. If a real pass-holding capture shows it wants only the trailing segment, change
 * the wrapped value here to `code`.
 */
fun bikeQrFromScan(scanned: String): QrCode? {
  val trimmed = scanned.trim()
  val host = trimmed.substringAfter("://", trimmed).substringBefore('/')
  if (host != "rlon.lft.to" && host != "lft.to") return null
  val code = trimmed.substringAfterLast('/')
  if (code.isEmpty() || code == host) return null
  return QrCode.parse(trimmed)
}
