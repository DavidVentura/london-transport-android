package dev.davidv.londontransport

import dev.davidv.londontransport.santander.PersistentUnlockDiagnostics
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentUnlockDiagnosticsTest {
  @get:Rule val folder = TemporaryFolder()

  @Test fun `a new logger appends without clearing a previous attempt`() {
    val file = folder.root.resolve("no_backup/diagnostics/unlock.jsonl")
    val clock = Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZoneOffset.UTC)
    PersistentUnlockDiagnostics(file, clock).response(200, """{"interstitial":{"action":[{"list_action":{"sequential_actions":[]}}]},"access_token":"secret"}""")
    PersistentUnlockDiagnostics(file, clock).failure("Unlock network failure: SocketTimeoutException")
    val lines = file.readLines()
    assertEquals(2, lines.size)
    assertTrue(lines.first().contains("list_action"))
    assertTrue(lines.last().contains("SocketTimeoutException"))
    assertTrue(lines.all { it.contains("2026-11-20T15:00:00Z") })
    assertFalse(file.readText().contains("secret"))
  }
}
