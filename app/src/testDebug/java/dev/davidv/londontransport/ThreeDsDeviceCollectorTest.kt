package dev.davidv.londontransport

import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import dev.davidv.londontransport.threeds.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ThreeDsDeviceCollectorTest {
  private val basic = AndroidBasicFacts(
    board = observeDeviceText("test-board"),
    bootloader = observeDeviceText("test-bootloader"),
    brand = observeDeviceText("test-brand"),
    buildDevice = observeDeviceText("test-buildDevice"),
    buildDisplay = observeDeviceText("test-buildDisplay"),
    buildFingerprint = observeDeviceText("test-buildFingerprint"),
    hardware = observeDeviceText("test-hardware"),
    buildId = observeDeviceText("test-buildId"),
    buildManufacturer = observeDeviceText("test-buildManufacturer"),
    product = observeDeviceText("test-product"),
    radioVersion = observeDeviceText("test-radioVersion"),
    supported32BitAbis = observeDeviceTexts(listOf("armeabi-v7a")),
    supported64BitAbis = observeDeviceTexts(listOf("arm64-v8a")),
    buildTags = observeDeviceText("test-buildTags"),
    buildTime = observeDeviceText("1700000000000"),
    buildType = observeDeviceText("test-buildType"),
    buildUser = observeDeviceText("test-buildUser"),
    versionCodename = observeDeviceText("test-versionCodename"),
    incrementalVersion = observeDeviceText("test-incrementalVersion"),
    previewSdk = observeDeviceText("0"),
    androidApi = observeDeviceText("36"),
    securityPatch = observeDeviceText("test-securityPatch"),
    safeMode = true,
    installerPackage = observeDeviceText("com.android.vending"),
    systemFeatureCount = DeviceCount(42),
    systemLibraries = observeDeviceTexts(listOf("test-library")),
    externalStorageRemovable = true,
    availableLocaleCount = DeviceCount(1000),
    dataStorageBytes = StorageCapacity(128000000000L),
  )
  private val restricted: Map<DeviceParameter, UnavailableReason> = Json.decodeFromString(
    """{
      "C009":"RE01","C010":"RE01","C011":"RE01","C012":"RE01","A028":"RE01","A029":"RE01",
      "A030":"RE01","A031":"RE01","A039":"RE01","A040":"RE01","A065":"RE01","A066":"RE01",
      "A068":"RE01","A069":"RE01","A070":"RE01","A071":"RE01","A073":"RE01","A074":"RE01",
      "A075":"RE01","A077":"RE01","A078":"RE01","A080":"RE01","A081":"RE01","A082":"RE01",
      "A083":"RE01","A087":"RE01","A091":"RE01","A092":"RE01","A094":"RE01","A096":"RE01",
      "A099":"RE01","A100":"RE01","A101":"RE01","A102":"RE01","A103":"RE01","A104":"RE01",
      "A105":"RE01","A106":"RE01","A107":"RE01","A108":"RE01","A110":"RE01","A112":"RE01",
      "A113":"RE01","A114":"RE01","A115":"RE01","A116":"RE01","A117":"RE01","A118":"RE01",
      "A119":"RE01","A120":"RE01","A121":"RE01","A122":"RE01","A123":"RE01","A125":"RE01",
      "A147":"RE01","A148":"RE01","A149":"RE01","A150":"RE01","A152":"RE01"
    }"""
  )
  private val facts = AndroidDeviceFacts("Example", "Phone", "BAKLAVA", "16", 36, Locale.UK, 0,
    SdkPackageName("dev.davidv.londontransport"), DeviceObservation.Unavailable(UnavailableReason.Unsupported),
    DeviceSecurityFacts(false, false, false, false, false), basic)

  @Test fun `actual common device facts serialize to the verified upstream fields`() {
    val device = deviceInformation(facts)
    val wire = Json.encodeToString(device)
    assertEquals("Example Phone", device.data.model)
    assertEquals("Android BAKLAVA 16 API 36", device.data.osDescription)
    assertEquals("en-GB", device.data.locale)
    assertEquals("0", device.data.utcOffsetMinutes)
    assertEquals("dev.davidv.londontransport", device.data.packageName)
    assertEquals(restricted + (DeviceParameter.Screen to UnavailableReason.Unsupported), device.unavailable)
    assertTrue(wire.contains("\"C009\":\"RE01\""))
    assertFalse(wire.contains("\"SW\""))
    assertFalse(wire.contains(":null"))
  }

  @Test fun `locale variants and raw timezone offset follow Java formats rather than DST or zone names`() {
    val device = deviceInformation(facts.copy(locale = Locale.forLanguageTag("en-GB-POSIX"), rawUtcOffsetMillis = -5 * 60 * 60 * 1000))
    assertEquals("en-GB-POSIX", device.data.locale)
    assertEquals("-300", device.data.utcOffsetMinutes)
    assertEquals("345", deviceInformation(facts.copy(rawUtcOffsetMillis = 345 * 60000)).data.utcOffsetMinutes)
  }

  @Test fun `available screen never enables policy restricted collection`() {
    val device = deviceInformation(facts.copy(screen = DeviceObservation.Available(ScreenSize(1080, 2400))))
    assertEquals("1080x2400", device.data.screenResolution)
    assertEquals(restricted, device.unavailable)
    val wire = Json.encodeToString(device)
    assertTrue(wire.contains("\"C011\":\"RE01\""))
    assertTrue(wire.contains("\"C012\":\"RE01\""))
    assertFalse(Json.encodeToString(device.data).contains("C011"))
    assertFalse(Json.encodeToString(device.data).contains("C012"))
    val dataWire = Json.encodeToString(device.data)
    restricted.keys.forEach { parameter ->
      val id = Json.encodeToString(parameter)
      assertFalse(dataWire.contains(id))
      assertTrue(wire.contains("$id:\"RE01\""))
    }
  }

  @Test fun `missing optional values are distinguished from policy restrictions`() {
    val device = deviceInformation(facts.copy(screen = DeviceObservation.Unavailable(UnavailableReason.NoData)))
    assertEquals(restricted + (DeviceParameter.Screen to UnavailableReason.NoData), device.unavailable)
    assertNull(device.data.screenResolution)
    assertTrue(Json.encodeToString(device).contains("\"C008\":\"RE04\""))
    assertTrue(Json.encodeToString(device).contains("\"C010\":\"RE01\""))
  }

  @Test fun `security observations preserve all warnings in upstream order`() {
    val device = deviceInformation(facts.copy(security = DeviceSecurityFacts(true, true, true, true, true)))
    assertEquals(SecurityWarning.entries, device.warnings)
    assertTrue(Json.encodeToString(device).contains("\"SW\":[\"SW01\",\"SW02\",\"SW03\",\"SW04\",\"SW05\"]"))
  }

  @Test fun `SDK identity belongs to transaction composition and collected values survive independent decryption`() {
    val transaction = SdkTransaction.create(SdkAppId("ac2d9673-ed8d-439f-a9ba-9629e203966a"), SdkReferenceNumber("test-sdk-reference"))
    val collected = deviceInformation(facts)
    assertNull(collected.data.sdkAppId)
    assertNull(collected.data.sdkVersion)
    assertNull(collected.data.sdkReferenceNumber)
    val version = SdkVersion("0.0.0")
    val device = collected.copy(data = collected.data.copy(
      sdkAppId = transaction.appId.value, sdkVersion = version, sdkReferenceNumber = transaction.reference,
    ))
    val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val result = encryptDeviceInformation(device, keys.public as RSAPublicKey) as ProtocolResult.Success
    val encrypted = JWEObject.parse(result.value)
    encrypted.decrypt(RSADecrypter(keys.private))
    val decoded = Json.decodeFromString<DeviceInformation>(encrypted.payload.toString())
    assertEquals(device, decoded)
    assertEquals(restricted + (DeviceParameter.Screen to UnavailableReason.Unsupported), decoded.unavailable)
    val decodedValues = Json.encodeToString(decoded.data)
    restricted.keys.forEach { parameter -> assertFalse(decodedValues.contains(Json.encodeToString(parameter))) }
    assertEquals(transaction.appId.value, decoded.data.sdkAppId)
    assertEquals(version, decoded.data.sdkVersion)
    assertEquals(transaction.reference, decoded.data.sdkReferenceNumber)
    assertTrue(encrypted.payload.toString().contains("\"C015\":\"0.0.0\""))
    assertTrue(encrypted.payload.toString().contains("\"A234\":\"${transaction.reference.value}\""))
  }

  @Test fun `basic facts use upstream wire IDs and string or array value types`() {
    val expected = Json.decodeFromString<DeviceData>(
      """{
        "C001": "Android",
        "C002": "Example Phone",
        "C003": "Android BAKLAVA 16 API 36",
        "C004": "16",
        "C005": "en-GB",
        "C006": "0",
        "C013": "dev.davidv.londontransport",
        "A042": "test-board",
        "A043": "test-bootloader",
        "A044": "test-brand",
        "A045": "test-buildDevice",
        "A046": "test-buildDisplay",
        "A047": "test-buildFingerprint",
        "A048": "test-hardware",
        "A049": "test-buildId",
        "A050": "test-buildManufacturer",
        "A051": "test-product",
        "A052": "test-radioVersion",
        "A054": [
          "armeabi-v7a"
        ],
        "A055": [
          "arm64-v8a"
        ],
        "A056": "test-buildTags",
        "A057": "1700000000000",
        "A058": "test-buildType",
        "A059": "test-buildUser",
        "A060": "test-versionCodename",
        "A061": "test-incrementalVersion",
        "A062": "0",
        "A063": "36",
        "A064": "test-securityPatch",
        "A124": "true",
        "A126": "com.android.vending",
        "A127": "42",
        "A128": [
          "test-library"
        ],
        "A129": "true",
        "A130": "1000",
        "A136": "128000000000"
      }"""
    )
    val collected = deviceInformation(facts)
    assertEquals(expected, collected.data)
    assertEquals(expected, Json.decodeFromString<DeviceData>(Json.encodeToString(collected.data)))
  }

  @Test fun `absent basic values retain their actual unavailable reason without changing exclusions`() {
    val absent = basic.copy(
      radioVersion = observeDeviceText(null), installerPackage = observeDeviceText(" "),
      supported32BitAbis = observeDeviceTexts(emptyList()),
      systemLibraries = DeviceObservation.Unavailable(UnavailableReason.Unsupported),
    )
    val device = deviceInformation(facts.copy(basic = absent))
    assertNull(device.data.radioVersion)
    assertNull(device.data.installerPackage)
    assertNull(device.data.supported32BitAbis)
    assertNull(device.data.systemLibraries)
    assertEquals(restricted + mapOf(
      DeviceParameter.Screen to UnavailableReason.Unsupported, DeviceParameter.RadioVersion to UnavailableReason.NoData,
      DeviceParameter.InstallerPackage to UnavailableReason.NoData, DeviceParameter.Supported32BitAbis to UnavailableReason.NoData,
      DeviceParameter.SystemLibraries to UnavailableReason.Unsupported,
    ), device.unavailable)
    assertEquals(UnavailableReason.Restricted, device.unavailable.getValue(DeviceParameter.InstalledApplications))
  }

  @Test fun `empty entries in platform lists are omitted and an entirely empty list is unavailable`() {
    assertEquals(observeDeviceTexts(listOf("arm64-v8a")), observeDeviceTexts(listOf("", "arm64-v8a", " ")))
    assertEquals(DeviceObservation.Unavailable(UnavailableReason.NoData), observeDeviceTexts(listOf("", " ")))
    assertEquals("0", deviceInformation(facts.copy(basic = basic.copy(systemFeatureCount = DeviceCount(0)))).data.systemFeatureCount)
    assertEquals("false", deviceInformation(facts.copy(basic = basic.copy(safeMode = false))).data.safeMode)
  }

  @Test fun `invalid device facts fail at the boundary`() {
    listOf(
      { ScreenSize(0, 2400) }, { SigningCertificateFingerprint("not-a-fingerprint") },
      { DeviceCount(-1) }, { StorageCapacity(0) }, { DeviceText(" ") }, { DeviceTextList(emptyList()) },
    ).forEach { construct ->
      assertEquals(ProtocolResult.Failure(ProtocolFault.MalformedInput), protocolBoundary { construct() })
    }
  }
}
