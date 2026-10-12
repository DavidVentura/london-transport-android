package dev.davidv.londontransport.threeds

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.buffer
import okio.source

interface ConfigurationStorage {
  fun read(): ByteArray?
  fun write(bytes: ByteArray)
}

private class AtomicConfigurationStorage(private val file: AtomicFile) : ConfigurationStorage {
  override fun read(): ByteArray? {
    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
    return file.openRead().source().buffer().use { source ->
      source.request(1024 * 1024 + 1L)
      if (source.buffer.size > 1024 * 1024L) throw IOException()
      source.buffer.readByteArray()
    }
  }

  override fun write(bytes: ByteArray) {
    val stream = file.startWrite()
    try {
      stream.write(bytes)
      file.finishWrite(stream)
    } catch (e: IOException) {
      file.failWrite(stream)
      throw e
    }
  }
}

class EncryptedConfigurationCache(private val storage: ConfigurationStorage, private val key: SecretKey) : ConfigurationCache {
  override fun read(): RefreshResult<String?> {
    return try {
      val bytes = storage.read() ?: return RefreshResult.Success(null)
      require(bytes.size in 29..1024 * 1024)
      require(bytes[0] == 1.toByte())
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
      cipher.updateAAD(byteArrayOf(1))
      val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
      RefreshResult.Success(plaintext.toString(Charsets.UTF_8))
    } catch (e: IOException) {
      RefreshResult.Failure(RefreshFault.CacheRead)
    } catch (e: GeneralSecurityException) {
      RefreshResult.Failure(RefreshFault.CacheRead)
    } catch (e: IllegalArgumentException) {
      RefreshResult.Failure(RefreshFault.CacheRead)
    }
  }

  override fun write(validated: RefreshedConfiguration): RefreshResult<Unit> {
    val bytes = try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key)
      cipher.updateAAD(byteArrayOf(1))
      require(cipher.iv.size == 12)
      byteArrayOf(1) + cipher.iv + cipher.doFinal(Json.encodeToString(validated.cache).toByteArray(Charsets.UTF_8))
    } catch (e: GeneralSecurityException) {
      return RefreshResult.Failure(RefreshFault.CacheWrite)
    }
    return try {
      storage.write(bytes)
      RefreshResult.Success(Unit)
    } catch (e: IOException) {
      RefreshResult.Failure(RefreshFault.CacheWrite)
    }
  }

  companion object {
    fun create(filesDirectory: File): RefreshResult<EncryptedConfigurationCache> = try {
      require(filesDirectory.isDirectory)
      val alias = "three-ds-configuration-v1"
      val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      val key = if (store.containsAlias(alias)) {
        store.getKey(alias, null) as SecretKey
      } else {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
          init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
      }
      RefreshResult.Success(EncryptedConfigurationCache(AtomicConfigurationStorage(AtomicFile(File(filesDirectory, "three-ds-configuration.enc"))), key))
    } catch (e: GeneralSecurityException) {
      RefreshResult.Failure(RefreshFault.CacheWrite)
    } catch (e: IOException) {
      RefreshResult.Failure(RefreshFault.CacheWrite)
    } catch (e: IllegalArgumentException) {
      RefreshResult.Failure(RefreshFault.CacheWrite)
    }
  }
}
