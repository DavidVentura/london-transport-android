package dev.davidv.londontransport.threeds

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.source

sealed interface RuntimeFault {
  data object ProfileRead : RuntimeFault
  data object InvalidProfile : RuntimeFault
  data object IdentityStorage : RuntimeFault
  data object Credentials : RuntimeFault
  data class Cache(val fault: RefreshFault) : RuntimeFault
  data class Transport(val fault: ProtocolFault) : RuntimeFault
}

sealed interface RuntimeResult<out T> {
  data class Success<T>(val value: T) : RuntimeResult<T>
  data class Failure(val fault: RuntimeFault) : RuntimeResult<Nothing>
}

class RuntimeProfile private constructor(
  val packageName: SdkPackageName,
  val sdkVersion: SdkVersion,
  val reference: SdkReferenceNumber,
  val refreshEndpoint: HttpUrl,
  val licenseZone: ZoneId,
  val expectedSigners: Set<SigningCertificateFingerprint>,
  internal val apiKey: RefreshApiKey,
  private val credentialPassword: String,
) {
  internal fun password(): CharArray = credentialPassword.toCharArray()
  override fun toString(): String = "RuntimeProfile(<redacted>)"

  companion object {
    fun parse(text: String): RuntimeResult<RuntimeProfile> {
      return try {
        require(text.length <= 16_384)
        val record = Json.decodeFromString<RuntimeProfileRecord>(text)
        val endpoint = record.refreshEndpoint.toHttpUrl()
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty())
        require(endpoint.query == null && endpoint.fragment == null)
        require(record.credentialPassword.isNotEmpty())
        require(record.expectedSigners.isNotEmpty() && record.expectedSigners.toSet().size == record.expectedSigners.size)
        val apiKey = when (val result = RefreshApiKey.parse(record.apiKey)) {
          is ProtocolResult.Success -> result.value
          is ProtocolResult.Failure -> return RuntimeResult.Failure(RuntimeFault.InvalidProfile)
        }
        RuntimeResult.Success(RuntimeProfile(record.packageName, record.sdkVersion, record.reference,
          endpoint, ZoneId.of(record.licenseZone), record.expectedSigners.toSet(), apiKey, record.credentialPassword))
      } catch (e: IllegalArgumentException) {
        RuntimeResult.Failure(RuntimeFault.InvalidProfile)
      } catch (e: java.time.DateTimeException) {
        RuntimeResult.Failure(RuntimeFault.InvalidProfile)
      }
    }
  }
}

@Serializable private data class RuntimeProfileRecord(
  val packageName: SdkPackageName,
  val sdkVersion: SdkVersion,
  val reference: SdkReferenceNumber,
  val refreshEndpoint: String,
  val licenseZone: String,
  val apiKey: String,
  val credentialPassword: String,
  val expectedSigners: List<SigningCertificateFingerprint>,
) {
  override fun toString(): String = "RuntimeProfileRecord(<redacted>)"
}

class InstallationSdkIdentity(private val directory: File) {
  @Synchronized
  fun loadOrCreate(): RuntimeResult<SdkAppId> {
    return try {
      require(directory.isDirectory)
      RandomAccessFile(File(directory, "three-ds-identity.lock"), "rw").use { lockFile ->
        lockFile.channel.lock().use {
          val file = AtomicFile(File(directory, "three-ds-app-id"))
          if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val bytes = file.openRead().source().buffer().use { source ->
              source.request(37)
              source.buffer.readByteArray()
            }
            require(bytes.size == 36)
            return RuntimeResult.Success(SdkAppId(bytes.toString(Charsets.US_ASCII)))
          }
          val identity = SdkAppId(UUID.randomUUID().toString())
          val output = file.startWrite()
          try {
            output.write(identity.value.toByteArray(Charsets.US_ASCII))
            file.finishWrite(output)
          } catch (e: IOException) {
            file.failWrite(output)
            throw e
          }
          RuntimeResult.Success(identity)
        }
      }
    } catch (e: IOException) {
      RuntimeResult.Failure(RuntimeFault.IdentityStorage)
    } catch (e: IllegalArgumentException) {
      RuntimeResult.Failure(RuntimeFault.IdentityStorage)
    } catch (e: java.nio.channels.OverlappingFileLockException) {
      RuntimeResult.Failure(RuntimeFault.IdentityStorage)
    }
  }
}

class AndroidThreeDsRuntime private constructor(
  val appId: SdkAppId,
  private val authentication: AuthenticationPrototype,
) {
  suspend fun prepare(server: DirectoryServer, now: Instant): PreparationResult =
    withContext(Dispatchers.IO) { authentication.prepare(server, now) }

  companion object {
    suspend fun create(
      context: Context,
      profileDirectory: File,
      trace: PaymentTrace = PaymentTrace.Disabled,
    ): RuntimeResult<AndroidThreeDsRuntime> = withContext(Dispatchers.IO) {
      val text: String
      val trustText: String
      val credentialBytes: ByteArray
      try {
        text = readBounded(File(profileDirectory, "runtime-profile.json"), 16_384).toString(Charsets.UTF_8)
        trustText = readBounded(File(profileDirectory, "refresh-trust.json"), 128 * 1024).toString(Charsets.UTF_8)
        credentialBytes = readBounded(File(profileDirectory, "client.p12"), 128 * 1024)
      } catch (e: IOException) {
        return@withContext RuntimeResult.Failure(RuntimeFault.ProfileRead)
      }
      val profile = when (val result = RuntimeProfile.parse(text)) {
        is RuntimeResult.Success -> result.value
        is RuntimeResult.Failure -> return@withContext result
      }
      val trust = when (val result = RefreshTrust.parse(trustText)) {
        is ProtocolResult.Success -> result.value
        is ProtocolResult.Failure -> return@withContext RuntimeResult.Failure(RuntimeFault.InvalidProfile)
      }
      val appId = when (val result = InstallationSdkIdentity(context.noBackupFilesDir).loadOrCreate()) {
        is RuntimeResult.Success -> result.value
        is RuntimeResult.Failure -> return@withContext result
      }
      val identity = RefreshIdentity(profile.packageName, appId, profile.sdkVersion, Build.VERSION.SDK_INT)
      val password = profile.password()
      val transport = try {
        val credentials = KeyStore.getInstance("PKCS12").apply {
          credentialBytes.inputStream().use { load(it, password) }
        }
        when (val result = HttpsConfigurationTransport.create(profile.refreshEndpoint, credentials, password, trust, identity, profile.apiKey, trace)) {
          is ProtocolResult.Success -> result.value
          is ProtocolResult.Failure -> return@withContext RuntimeResult.Failure(RuntimeFault.Transport(result.fault))
        }
      } catch (e: IOException) {
        return@withContext RuntimeResult.Failure(RuntimeFault.Credentials)
      } catch (e: GeneralSecurityException) {
        return@withContext RuntimeResult.Failure(RuntimeFault.Credentials)
      } finally {
        password.fill('\u0000')
        credentialBytes.fill(0)
      }
      val cache = when (val result = EncryptedConfigurationCache.create(context.noBackupFilesDir)) {
        is RefreshResult.Success -> result.value
        is RefreshResult.Failure -> return@withContext RuntimeResult.Failure(RuntimeFault.Cache(result.fault))
      }
      val refresher = ConfigurationRefresher(RefreshValidator(trust.licenseVerificationKey, profile.packageName,
        profile.apiKey, profile.licenseZone), transport, cache)
      RuntimeResult.Success(AndroidThreeDsRuntime(appId, AuthenticationPrototype(appId, profile.reference,
        profile.sdkVersion, AndroidDeviceCollector(context.applicationContext, profile.expectedSigners), refresher)))
    }

    private fun readBounded(file: File, limit: Int): ByteArray {
      val bytes = file.source().buffer().use { source ->
        source.request(limit + 1L)
        source.buffer.readByteArray()
      }
      if (bytes.size > limit) throw IOException("Local profile file exceeds limit")
      return bytes
    }
  }
}
