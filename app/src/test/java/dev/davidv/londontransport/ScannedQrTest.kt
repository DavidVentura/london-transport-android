package dev.davidv.londontransport

import dev.davidv.londontransport.santander.bikeQrFromScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScannedQrTest {
  @Test fun `accepts a bike short link and keeps the raw scanned payload`() {
    assertEquals("rlon.lft.to/ABDRDO", bikeQrFromScan("rlon.lft.to/ABDRDO")?.value)
    assertEquals("https://rlon.lft.to/ABDRDO", bikeQrFromScan("https://rlon.lft.to/ABDRDO")?.value)
    assertEquals("lft.to/ABDRDO", bikeQrFromScan("  lft.to/ABDRDO  ")?.value)
  }

  @Test fun `rejects anything that is not a Santander bike short link`() {
    assertNull(bikeQrFromScan(""))
    assertNull(bikeQrFromScan("https://example.com/ABDRDO"))
    assertNull(bikeQrFromScan("ABDRDO"))
    assertNull(bikeQrFromScan("rlon.lft.to/"))
    assertNull(bikeQrFromScan("https://evil.test/rlon.lft.to/ABDRDO"))
  }
}
