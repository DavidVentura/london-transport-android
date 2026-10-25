package dev.davidv.londontransport

import dev.davidv.londontransport.threeds.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ThreeDsRuntimeProfileTest {
  private val record = ProfileFixture()
  private val json = Json { encodeDefaults = true }

  @Test fun `profile is parsed once into domain types and secrets remain redacted`() {
    val profile = (RuntimeProfile.parse(json.encodeToString(record)) as RuntimeResult.Success).value
    assertEquals(SdkPackageName(record.packageName), profile.packageName)
    assertEquals(SdkVersion(record.sdkVersion), profile.sdkVersion)
    assertEquals(SdkReferenceNumber(record.reference), profile.reference)
    assertEquals(record.refreshEndpoint, profile.refreshEndpoint.toString())
    assertFalse(profile.toString().contains(record.apiKey))
    assertFalse(profile.toString().contains(record.credentialPassword))
  }

  @Test fun `one invalid field invalidates the whole profile without defaults`() {
    val invalid = listOf(
      record.copy(packageName = "invalid"), record.copy(sdkVersion = "invalid"),
      record.copy(reference = ""), record.copy(apiKey = "invalid"), record.copy(credentialPassword = ""),
      record.copy(expectedSigners = emptyList()), record.copy(expectedSigners = listOf("invalid")),
      record.copy(licenseZone = "invalid"), record.copy(refreshEndpoint = "http://remote.example/sdk-data"),
      record.copy(refreshEndpoint = "https://user:pass@remote.example/sdk-data"),
      record.copy(refreshEndpoint = "https://remote.example/sdk-data?key=secret"),
      record.copy(refreshEndpoint = "https://remote.example/sdk-data#fragment"),
    ).map { json.encodeToString(it) } + listOf("{}", "not-json", " ".repeat(16_385))
    invalid.forEach { text ->
      assertEquals(RuntimeResult.Failure(RuntimeFault.InvalidProfile), RuntimeProfile.parse(text))
    }
  }
}

@Serializable private data class ProfileFixture(
  val packageName: String = "test.sdk.profile",
  val sdkVersion: String = "0.0.0",
  val reference: String = "test-sdk-reference",
  val refreshEndpoint: String = "https://refresh.example/sdk-data",
  val licenseZone: String = "UTC",
  val apiKey: String = "c3fbc9e0-287a-4c7e-acfa-238ed994c3d6",
  val credentialPassword: String = "synthetic-test-password",
  val expectedSigners: List<String> = listOf("00".repeat(32)),
)
