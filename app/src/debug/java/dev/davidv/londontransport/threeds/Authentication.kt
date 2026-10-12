package dev.davidv.londontransport.threeds

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@JvmInline @Serializable value class SdkAppId(val value: String) {
  init { require(UUID.fromString(value).toString() == value) }
}

@JvmInline @Serializable value class SdkTransactionId(val value: String) {
  init { require(UUID.fromString(value).toString() == value) }
}

@JvmInline @Serializable value class ServerTransactionId(val value: String) {
  init { require(UUID.fromString(value).toString() == value) }
}

@JvmInline @Serializable value class AcsTransactionId(val value: String) {
  init { require(UUID.fromString(value).toString() == value) }
}

@JvmInline @Serializable value class SdkReferenceNumber(val value: String) {
  init { require(value.isNotBlank() && value.length <= 32) }
}

@Serializable enum class DirectoryServer(val id: String) {
  @SerialName("visa") Visa("A000000003"),
  @SerialName("mastercard") Mastercard("A000000004"),
  @SerialName("amex") Amex("A000000025"),
}

@Serializable enum class DeviceDataVersion { @SerialName("1.5") V15 }

@Serializable
data class DeviceInformation(
  @SerialName("DV") val version: DeviceDataVersion,
  @SerialName("DD") val data: DeviceData,
  @SerialName("DPNA") val unavailable: Map<DeviceParameter, UnavailableReason> = emptyMap(),
  @SerialName("SW") val warnings: List<SecurityWarning> = emptyList(),
)

@Serializable
data class DeviceData(
  @SerialName("C001") val platform: String,
  @SerialName("C002") val model: String,
  @SerialName("C003") val osDescription: String,
  @SerialName("C004") val osVersion: String,
  @SerialName("C005") val locale: String,
  @SerialName("C006") val utcOffsetMinutes: String,
  @SerialName("C008") val screenResolution: String? = null,
  @SerialName("C013") val packageName: String? = null,
  @SerialName("C014") val sdkAppId: String? = null,
  @SerialName("C015") val sdkVersion: SdkVersion? = null,
  @SerialName("A234") val sdkReferenceNumber: SdkReferenceNumber? = null,
  @SerialName("A042") val board: String? = null,
  @SerialName("A043") val bootloader: String? = null,
  @SerialName("A044") val brand: String? = null,
  @SerialName("A045") val buildDevice: String? = null,
  @SerialName("A046") val buildDisplay: String? = null,
  @SerialName("A047") val buildFingerprint: String? = null,
  @SerialName("A048") val hardware: String? = null,
  @SerialName("A049") val buildId: String? = null,
  @SerialName("A050") val buildManufacturer: String? = null,
  @SerialName("A051") val product: String? = null,
  @SerialName("A052") val radioVersion: String? = null,
  @SerialName("A054") val supported32BitAbis: List<String>? = null,
  @SerialName("A055") val supported64BitAbis: List<String>? = null,
  @SerialName("A056") val buildTags: String? = null,
  @SerialName("A057") val buildTime: String? = null,
  @SerialName("A058") val buildType: String? = null,
  @SerialName("A059") val buildUser: String? = null,
  @SerialName("A060") val versionCodename: String? = null,
  @SerialName("A061") val incrementalVersion: String? = null,
  @SerialName("A062") val previewSdk: String? = null,
  @SerialName("A063") val androidApi: String? = null,
  @SerialName("A064") val securityPatch: String? = null,
  @SerialName("A124") val safeMode: String? = null,
  @SerialName("A126") val installerPackage: String? = null,
  @SerialName("A127") val systemFeatureCount: String? = null,
  @SerialName("A128") val systemLibraries: List<String>? = null,
  @SerialName("A129") val externalStorageRemovable: String? = null,
  @SerialName("A130") val availableLocaleCount: String? = null,
  @SerialName("A136") val dataStorageBytes: String? = null,
)

@Serializable enum class DeviceParameter(val restrictedByPolicy: Boolean) {
  @SerialName("C008") Screen(false),
  @SerialName("A042") Board(false),
  @SerialName("A043") Bootloader(false),
  @SerialName("A044") Brand(false),
  @SerialName("A045") BuildDevice(false),
  @SerialName("A046") BuildDisplay(false),
  @SerialName("A047") BuildFingerprint(false),
  @SerialName("A048") Hardware(false),
  @SerialName("A049") BuildId(false),
  @SerialName("A050") BuildManufacturer(false),
  @SerialName("A051") Product(false),
  @SerialName("A052") RadioVersion(false),
  @SerialName("A054") Supported32BitAbis(false),
  @SerialName("A055") Supported64BitAbis(false),
  @SerialName("A056") BuildTags(false),
  @SerialName("A057") BuildTime(false),
  @SerialName("A058") BuildType(false),
  @SerialName("A059") BuildUser(false),
  @SerialName("A060") VersionCodename(false),
  @SerialName("A061") IncrementalVersion(false),
  @SerialName("A062") PreviewSdk(false),
  @SerialName("A063") AndroidApi(false),
  @SerialName("A064") SecurityPatch(false),
  @SerialName("A124") SafeMode(false),
  @SerialName("A126") InstallerPackage(false),
  @SerialName("A127") SystemFeatureCount(false),
  @SerialName("A128") SystemLibraries(false),
  @SerialName("A129") ExternalStorageRemovable(false),
  @SerialName("A130") AvailableLocaleCount(false),
  @SerialName("A136") DataStorageBytes(false),
  @SerialName("C009") BluetoothName(true),
  @SerialName("C010") IpAddress(true),
  @SerialName("C011") Latitude(true),
  @SerialName("C012") Longitude(true),
  @SerialName("A028") WifiMacAddress(true),
  @SerialName("A029") WifiBssid(true),
  @SerialName("A030") WifiSsid(true),
  @SerialName("A031") WifiNetworkId(true),
  @SerialName("A039") BluetoothAddress(true),
  @SerialName("A040") BondedBluetoothAddresses(true),
  @SerialName("A065") AccessibilityDisplayInversion(true),
  @SerialName("A066") AccessibilityEnabled(true),
  @SerialName("A068") GeolocationOrigins(true),
  @SerialName("A069") AndroidId(true),
  @SerialName("A070") DataRoamingPreference(true),
  @SerialName("A071") DefaultInputMethod(true),
  @SerialName("A073") AccessibilityServices(true),
  @SerialName("A074") EnabledInputMethods(true),
  @SerialName("A075") InputMethodSelectorVisibility(true),
  @SerialName("A077") LocationEnabled(true),
  @SerialName("A078") SkipFirstUseHints(true),
  @SerialName("A080") SpeechPitch(true),
  @SerialName("A081") SpeechRate(true),
  @SerialName("A082") SpeechSynthesizer(true),
  @SerialName("A083") SpeechPlugins(true),
  @SerialName("A087") AnimationDurationScale(true),
  @SerialName("A091") HttpProxy(true),
  @SerialName("A092") NetworkPreference(true),
  @SerialName("A094") TransitionAnimationScale(true),
  @SerialName("A096") GoogleMailPreference(true),
  @SerialName("A099") AccelerometerRotation(true),
  @SerialName("A100") BluetoothDiscoverability(true),
  @SerialName("A101") BluetoothDiscoverabilityTimeout(true),
  @SerialName("A102") DateFormat(true),
  @SerialName("A103") DtmfToneType(true),
  @SerialName("A104") DtmfTone(true),
  @SerialName("A105") EndButtonBehavior(true),
  @SerialName("A106") FontScale(true),
  @SerialName("A107") HapticFeedback(true),
  @SerialName("A108") RingerStreams(true),
  @SerialName("A110") MuteStreams(true),
  @SerialName("A112") ScreenBrightness(true),
  @SerialName("A113") ScreenBrightnessMode(true),
  @SerialName("A114") ScreenOffTimeout(true),
  @SerialName("A115") SoundEffects(true),
  @SerialName("A116") AutoCaps(true),
  @SerialName("A117") AutoPunctuate(true),
  @SerialName("A118") AutoReplace(true),
  @SerialName("A119") ShowPassword(true),
  @SerialName("A120") ClockFormat(true),
  @SerialName("A121") UserRotation(true),
  @SerialName("A122") VibrateOn(true),
  @SerialName("A123") VibrateWhenRinging(true),
  @SerialName("A125") InstalledApplications(true),
  @SerialName("A147") PasspointFqdn(true),
  @SerialName("A148") PasspointProvider(true),
  @SerialName("A149") BondedBluetoothAliases(true),
  @SerialName("A150") RttCallingMode(true),
  @SerialName("A152") RampingRinger(true),
}

@Serializable enum class UnavailableReason {
  @SerialName("RE01") Restricted,
  @SerialName("RE02") Unsupported,
  @SerialName("RE03") PermissionRequired,
  @SerialName("RE04") NoData,
}

@Serializable enum class SecurityWarning {
  @SerialName("SW01") Rooted,
  @SerialName("SW02") Tampered,
  @SerialName("SW03") Emulator,
  @SerialName("SW04") Debugger,
  @SerialName("SW05") UnsupportedOs,
}

class SdkTransaction private constructor(
  val appId: SdkAppId,
  val id: SdkTransactionId,
  val reference: SdkReferenceNumber,
  internal val keyPair: KeyPair,
) {
  val publicKey: PublicJwk
    get() = Json.decodeFromString(
      ECKey.Builder(Curve.P_256, keyPair.public as ECPublicKey).build().toJSONString()
    )

  companion object {
    fun create(appId: SdkAppId, reference: SdkReferenceNumber): SdkTransaction {
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"))
      return SdkTransaction(appId, SdkTransactionId(UUID.randomUUID().toString()), reference, generator.generateKeyPair())
    }
  }
}

@Serializable enum class JwkType { @SerialName("EC") Ec }
@Serializable enum class JwkCurve { @SerialName("P-256") P256 }

@Serializable
data class PublicJwk(val kty: JwkType, val crv: JwkCurve, val x: String, val y: String) {
  internal fun toKey(): ECPublicKey {
    val key = ECKey.parse(Json.encodeToString(this))
    return key.toECPublicKey()
  }
}

data class AuthenticationParameters(
  val appId: SdkAppId,
  val transactionId: SdkTransactionId,
  val reference: SdkReferenceNumber,
  val ephemeralPublicKey: PublicJwk,
  val encryptedDeviceData: String,
) {
  fun formFields(): Map<String, String> = mapOf(
    "sdkAppID" to appId.value,
    "sdkTransID" to transactionId.value,
    "sdkReferenceNumber" to reference.value,
    "sdkEphemPubKey" to Json.encodeToString(ephemeralPublicKey),
    "sdkEncData" to encryptedDeviceData,
    "sdkMaxTimeout" to "05",
    "sdkInterface" to "01",
  )
}

fun encryptDeviceInformation(device: DeviceInformation, key: RSAPublicKey): ProtocolResult<String> = protocolBoundary {
  if (key.modulus.bitLength() < 2048) throw ProtocolException(ProtocolFault.UnsupportedProfile)
  val jwe = JWEObject(
    JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A128CBC_HS256).build(),
    Payload(Json.encodeToString(device)),
  )
  jwe.encrypt(RSAEncrypter(key))
  jwe.serialize()
}
