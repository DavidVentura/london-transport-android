package dev.davidv.londontransport.threeds

import java.time.Instant

sealed interface PreparationFault {
  data class Configuration(val fault: RefreshFault) : PreparationFault
  data class Device(val fault: ProtocolFault) : PreparationFault
  data class Encryption(val fault: ProtocolFault) : PreparationFault
}
sealed interface PreparationResult {
  data class Success(val authentication: PreparedAuthentication) : PreparationResult
  data class Failure(val fault: PreparationFault) : PreparationResult
}
class PreparedAuthentication internal constructor(val transaction: SdkTransaction, val parameters: AuthenticationParameters)

class AuthenticationPrototype(
  private val appId: SdkAppId,
  private val reference: SdkReferenceNumber,
  private val sdkVersion: SdkVersion,
  private val collector: DeviceInformationSource,
  private val refresher: ConfigurationRefresher,
) {
  fun prepare(server: DirectoryServer, now: Instant): PreparationResult {
    val configuration = when (val result = refresher.refresh(now)) {
      is RefreshResult.Success -> result.value.configuration(server)
      is RefreshResult.Failure -> return PreparationResult.Failure(PreparationFault.Configuration(result.fault))
    }
    val collected = when (val result = collector.collect()) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return PreparationResult.Failure(PreparationFault.Device(result.fault))
    }
    val transaction = when (val result = protocolBoundary { SdkTransaction.create(appId, reference) }) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return PreparationResult.Failure(PreparationFault.Encryption(result.fault))
    }
    val device = collected.copy(data = collected.data.copy(
      sdkAppId = appId.value, sdkVersion = sdkVersion, sdkReferenceNumber = reference,
    ))
    val encrypted = when (val result = configuration.encrypt(device, now)) {
      is ProtocolResult.Success -> result.value
      is ProtocolResult.Failure -> return PreparationResult.Failure(PreparationFault.Encryption(result.fault))
    }
    val parameters = AuthenticationParameters(transaction.appId, transaction.id, transaction.reference, transaction.publicKey, encrypted)
    return PreparationResult.Success(PreparedAuthentication(transaction, parameters))
  }
}
