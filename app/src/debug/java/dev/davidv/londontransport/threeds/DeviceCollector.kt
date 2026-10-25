package dev.davidv.londontransport.threeds

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

sealed interface DeviceObservation<out T> {
  data class Available<T>(val value: T) : DeviceObservation<T>
  data class Unavailable(val reason: UnavailableReason) : DeviceObservation<Nothing>
}

data class ScreenSize(val width: Int, val height: Int) {
  init { require(width > 0 && height > 0) }
}
data class DeviceSecurityFacts(val rooted: Boolean, val tampered: Boolean, val emulator: Boolean, val debugger: Boolean, val unsupportedOs: Boolean)
data class AndroidDeviceFacts(
  val manufacturer: String, val model: String, val osCodename: String, val release: String, val apiLevel: Int,
  val locale: Locale, val rawUtcOffsetMillis: Int, val packageName: SdkPackageName,
  val screen: DeviceObservation<ScreenSize>,
  val security: DeviceSecurityFacts,
  val basic: AndroidBasicFacts,
) {
  init {
    require(manufacturer.isNotBlank() && model.isNotBlank() && osCodename.isNotBlank() && release.isNotBlank())
    require(apiLevel >= 26)
    require(rawUtcOffsetMillis in -18 * 60 * 60 * 1000..18 * 60 * 60 * 1000)
  }
}

fun deviceInformation(facts: AndroidDeviceFacts): DeviceInformation {
  val restricted = DeviceParameter.entries.filter { it.restrictedByPolicy }.associateWith { UnavailableReason.Restricted }
  val basic = facts.basic
  val missingBasic = listOf(
    DeviceParameter.Board to basic.board,
    DeviceParameter.Bootloader to basic.bootloader,
    DeviceParameter.Brand to basic.brand,
    DeviceParameter.BuildDevice to basic.buildDevice,
    DeviceParameter.BuildDisplay to basic.buildDisplay,
    DeviceParameter.BuildFingerprint to basic.buildFingerprint,
    DeviceParameter.Hardware to basic.hardware,
    DeviceParameter.BuildId to basic.buildId,
    DeviceParameter.BuildManufacturer to basic.buildManufacturer,
    DeviceParameter.Product to basic.product,
    DeviceParameter.RadioVersion to basic.radioVersion,
    DeviceParameter.Supported32BitAbis to basic.supported32BitAbis,
    DeviceParameter.Supported64BitAbis to basic.supported64BitAbis,
    DeviceParameter.BuildTags to basic.buildTags,
    DeviceParameter.BuildTime to basic.buildTime,
    DeviceParameter.BuildType to basic.buildType,
    DeviceParameter.BuildUser to basic.buildUser,
    DeviceParameter.VersionCodename to basic.versionCodename,
    DeviceParameter.IncrementalVersion to basic.incrementalVersion,
    DeviceParameter.PreviewSdk to basic.previewSdk,
    DeviceParameter.AndroidApi to basic.androidApi,
    DeviceParameter.SecurityPatch to basic.securityPatch,
    DeviceParameter.InstallerPackage to basic.installerPackage,
    DeviceParameter.SystemLibraries to basic.systemLibraries,
  ).mapNotNull { (parameter, observation) ->
    if (observation is DeviceObservation.Unavailable) parameter to observation.reason else null
  }.toMap()
  val unavailable = when (val observation = facts.screen) {
    is DeviceObservation.Unavailable -> restricted + missingBasic + (DeviceParameter.Screen to observation.reason)
    is DeviceObservation.Available -> restricted + missingBasic
  }
  val screen = when (val value = facts.screen) {
    is DeviceObservation.Available -> "${value.value.width}x${value.value.height}"
    is DeviceObservation.Unavailable -> null
  }
  val security = facts.security
  val warnings = listOf(
    security.rooted to SecurityWarning.Rooted, security.tampered to SecurityWarning.Tampered,
    security.emulator to SecurityWarning.Emulator, security.debugger to SecurityWarning.Debugger,
    security.unsupportedOs to SecurityWarning.UnsupportedOs,
  ).filter { it.first }.map { it.second }
  return DeviceInformation(DeviceDataVersion.V15, DeviceData(
    platform = "Android", model = "${facts.manufacturer} ${facts.model}",
    osDescription = "Android ${facts.osCodename} ${facts.release} API ${facts.apiLevel}",
    osVersion = facts.release, locale = facts.locale.toString().replace('_', '-'),
    utcOffsetMinutes = (facts.rawUtcOffsetMillis / 60000).toString(), screenResolution = screen,
    packageName = facts.packageName.value,
    board = basic.board.wireText(),
    bootloader = basic.bootloader.wireText(),
    brand = basic.brand.wireText(),
    buildDevice = basic.buildDevice.wireText(),
    buildDisplay = basic.buildDisplay.wireText(),
    buildFingerprint = basic.buildFingerprint.wireText(),
    hardware = basic.hardware.wireText(),
    buildId = basic.buildId.wireText(),
    buildManufacturer = basic.buildManufacturer.wireText(),
    product = basic.product.wireText(),
    radioVersion = basic.radioVersion.wireText(),
    supported32BitAbis = basic.supported32BitAbis.wireTexts(),
    supported64BitAbis = basic.supported64BitAbis.wireTexts(),
    buildTags = basic.buildTags.wireText(),
    buildTime = basic.buildTime.wireText(),
    buildType = basic.buildType.wireText(),
    buildUser = basic.buildUser.wireText(),
    versionCodename = basic.versionCodename.wireText(),
    incrementalVersion = basic.incrementalVersion.wireText(),
    previewSdk = basic.previewSdk.wireText(),
    androidApi = basic.androidApi.wireText(),
    securityPatch = basic.securityPatch.wireText(),
    safeMode = basic.safeMode.toString(),
    installerPackage = basic.installerPackage.wireText(),
    systemFeatureCount = basic.systemFeatureCount.value.toString(),
    systemLibraries = basic.systemLibraries.wireTexts(),
    externalStorageRemovable = basic.externalStorageRemovable.toString(),
    availableLocaleCount = basic.availableLocaleCount.value.toString(),
    dataStorageBytes = basic.dataStorageBytes.bytes.toString(),
  ), unavailable, warnings)
}

@JvmInline @kotlinx.serialization.Serializable value class SigningCertificateFingerprint(val value: String) {
  init { require(value.matches(Regex("[a-f0-9]{64}"))) }
}

interface DeviceInformationSource { fun collect(): ProtocolResult<DeviceInformation> }

class AndroidDeviceCollector(private val context: Context, private val expectedSigners: Set<SigningCertificateFingerprint>) : DeviceInformationSource {
  init { require(expectedSigners.isNotEmpty()) }

  override fun collect(): ProtocolResult<DeviceInformation> = protocolBoundary {
    val api = Build.VERSION.SDK_INT
    val codename = Build.VERSION_CODES::class.java.fields.firstOrNull { it.getInt(null) == api }?.name
      ?: throw ProtocolException(ProtocolFault.UnsupportedProfile)
    val screen = if (api >= 30) {
      DeviceObservation.Unavailable(UnavailableReason.Unsupported)
    } else {
      val manager = context.getSystemService(WindowManager::class.java)
        ?: throw ProtocolException(ProtocolFault.InvalidState)
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION")
      manager.defaultDisplay.getMetrics(metrics)
      DeviceObservation.Available(ScreenSize(metrics.widthPixels, metrics.heightPixels))
    }
    val security = DeviceSecurityFacts(
      rooted = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk").any { File(it).exists() },
      tampered = signingFingerprints() != expectedSigners,
      emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("Android SDK built for") || Build.HARDWARE in setOf("goldfish", "ranchu"),
      debugger = Debug.isDebuggerConnected() || Debug.waitingForDebugger(),
      unsupportedOs = api < 26 || api > 36,
    )
    deviceInformation(AndroidDeviceFacts(Build.MANUFACTURER, Build.MODEL, codename, Build.VERSION.RELEASE, api,
      Locale.getDefault(), TimeZone.getDefault().rawOffset, SdkPackageName(context.packageName), screen,
      security, basicFacts(api)))
  }

  @Suppress("DEPRECATION")
  private fun basicFacts(api: Int): AndroidBasicFacts {
    val manager = context.packageManager
    val installer = try {
      if (Build.VERSION.SDK_INT >= 30) manager.getInstallSourceInfo(context.packageName).installingPackageName
      else manager.getInstallerPackageName(context.packageName)
    } catch (e: PackageManager.NameNotFoundException) {
      throw ProtocolException(ProtocolFault.InvalidState)
    }
    return AndroidBasicFacts(
      board = observeDeviceText(Build.BOARD),
      bootloader = observeDeviceText(Build.BOOTLOADER),
      brand = observeDeviceText(Build.BRAND),
      buildDevice = observeDeviceText(Build.DEVICE),
      buildDisplay = observeDeviceText(Build.DISPLAY),
      buildFingerprint = observeDeviceText(Build.FINGERPRINT),
      hardware = observeDeviceText(Build.HARDWARE),
      buildId = observeDeviceText(Build.ID),
      buildManufacturer = observeDeviceText(Build.MANUFACTURER),
      product = observeDeviceText(Build.PRODUCT),
      radioVersion = observeDeviceText(Build.getRadioVersion()),
      supported32BitAbis = observeDeviceTexts(Build.SUPPORTED_32_BIT_ABIS.toList()),
      supported64BitAbis = observeDeviceTexts(Build.SUPPORTED_64_BIT_ABIS.toList()),
      buildTags = observeDeviceText(Build.TAGS),
      buildTime = observeDeviceText(Build.TIME.toString()),
      buildType = observeDeviceText(Build.TYPE),
      buildUser = observeDeviceText(Build.USER),
      versionCodename = observeDeviceText(Build.VERSION.CODENAME),
      incrementalVersion = observeDeviceText(Build.VERSION.INCREMENTAL),
      previewSdk = observeDeviceText(Build.VERSION.PREVIEW_SDK_INT.toString()),
      androidApi = observeDeviceText(api.toString()),
      securityPatch = observeDeviceText(Build.VERSION.SECURITY_PATCH),
      safeMode = manager.isSafeMode,
      installerPackage = observeDeviceText(installer),
      systemFeatureCount = DeviceCount(manager.systemAvailableFeatures.size),
      systemLibraries = observeDeviceTexts(manager.systemSharedLibraryNames?.toList().orEmpty()),
      externalStorageRemovable = Environment.isExternalStorageRemovable(),
      availableLocaleCount = DeviceCount(Locale.getAvailableLocales().size),
      dataStorageBytes = StorageCapacity(StatFs(Environment.getDataDirectory().absolutePath).totalBytes),
    )
  }

  @Suppress("DEPRECATION")
  private fun signingFingerprints(): Set<SigningCertificateFingerprint> {
    val signatures = try {
      if (Build.VERSION.SDK_INT >= 28) {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        checkNotNull(info.signingInfo).apkContentsSigners
      } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
      }
    } catch (e: PackageManager.NameNotFoundException) {
      throw ProtocolException(ProtocolFault.InvalidState)
    }
    return checkNotNull(signatures).map {
      SigningCertificateFingerprint(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) })
    }.toSet()
  }
}
