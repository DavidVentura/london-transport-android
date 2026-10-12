package dev.davidv.londontransport.threeds

@JvmInline value class DeviceText(val value: String) {
  init { require(value.isNotBlank()) }
}

data class DeviceTextList(val values: List<DeviceText>) {
  init { require(values.isNotEmpty()) }
}

@JvmInline value class DeviceCount(val value: Int) {
  init { require(value >= 0) }
}

@JvmInline value class StorageCapacity(val bytes: Long) {
  init { require(bytes > 0) }
}

data class AndroidBasicFacts(
  val board: DeviceObservation<DeviceText>,
  val bootloader: DeviceObservation<DeviceText>,
  val brand: DeviceObservation<DeviceText>,
  val buildDevice: DeviceObservation<DeviceText>,
  val buildDisplay: DeviceObservation<DeviceText>,
  val buildFingerprint: DeviceObservation<DeviceText>,
  val hardware: DeviceObservation<DeviceText>,
  val buildId: DeviceObservation<DeviceText>,
  val buildManufacturer: DeviceObservation<DeviceText>,
  val product: DeviceObservation<DeviceText>,
  val radioVersion: DeviceObservation<DeviceText>,
  val supported32BitAbis: DeviceObservation<DeviceTextList>,
  val supported64BitAbis: DeviceObservation<DeviceTextList>,
  val buildTags: DeviceObservation<DeviceText>,
  val buildTime: DeviceObservation<DeviceText>,
  val buildType: DeviceObservation<DeviceText>,
  val buildUser: DeviceObservation<DeviceText>,
  val versionCodename: DeviceObservation<DeviceText>,
  val incrementalVersion: DeviceObservation<DeviceText>,
  val previewSdk: DeviceObservation<DeviceText>,
  val androidApi: DeviceObservation<DeviceText>,
  val securityPatch: DeviceObservation<DeviceText>,
  val safeMode: Boolean,
  val installerPackage: DeviceObservation<DeviceText>,
  val systemFeatureCount: DeviceCount,
  val systemLibraries: DeviceObservation<DeviceTextList>,
  val externalStorageRemovable: Boolean,
  val availableLocaleCount: DeviceCount,
  val dataStorageBytes: StorageCapacity,
)

internal fun observeDeviceText(value: String?): DeviceObservation<DeviceText> =
  if (value.isNullOrBlank()) DeviceObservation.Unavailable(UnavailableReason.NoData) else DeviceObservation.Available(DeviceText(value))

internal fun observeDeviceTexts(values: List<String>): DeviceObservation<DeviceTextList> {
  val texts = values.filter { it.isNotBlank() }.map(::DeviceText)
  if (texts.isEmpty()) return DeviceObservation.Unavailable(UnavailableReason.NoData)
  return DeviceObservation.Available(DeviceTextList(texts))
}

internal fun DeviceObservation<DeviceText>.wireText(): String? = when (this) {
  is DeviceObservation.Available -> value.value
  is DeviceObservation.Unavailable -> null
}

internal fun DeviceObservation<DeviceTextList>.wireTexts(): List<String>? = when (this) {
  is DeviceObservation.Available -> value.values.map { it.value }
  is DeviceObservation.Unavailable -> null
}
