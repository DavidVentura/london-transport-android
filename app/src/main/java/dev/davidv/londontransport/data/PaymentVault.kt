package dev.davidv.londontransport.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dev.davidv.londontransport.domain.Sealed
import dev.davidv.londontransport.santander.Cvv
import dev.davidv.londontransport.santander.PaymentMethod
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class StoredPayment(val method: PaymentMethod, val cvv: Cvv?)

/** [previousLost] means everything sealed before this is unreadable and should be dropped. */
data class Sealing(val sealed: Sealed, val previousLost: Boolean)

fun interface PaymentSealer {
  fun seal(payment: StoredPayment): Sealing
}

sealed interface VaultCipher {
  data class Ready(val cipher: Cipher) : VaultCipher

  /** The lock screen or enrolled fingerprints changed; the key and all it sealed are gone. */
  data object Invalidated : VaultCipher
}

private const val KEYSTORE = "AndroidKeyStore"
private const val ALIAS = "payment-methods-rsa"
private const val WRAP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
private const val SEAL = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

/**
 * Many Keystore implementations only do MGF1 with SHA-1, whatever the main digest, so both sides
 * pin it rather than leave it to each provider's default.
 */
private val OAEP =
  OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

/**
 * Each method is sealed under its own throwaway AES key, wrapped with the public half of a Keystore
 * RSA pair. Only the private half needs an unlock, so saving is silent and reading is not.
 */
class PaymentVault(private val context: Context) : PaymentSealer {
  /**
   * Before Android 11 a per-use key cannot be unlocked with the screen lock, only with a
   * fingerprint.
   */
  val authenticators: Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    else BIOMETRIC_STRONG

  fun available(): Boolean =
    BiometricManager.from(context).canAuthenticate(authenticators) ==
      BiometricManager.BIOMETRIC_SUCCESS

  /**
   * The public key keeps working after the private one is invalidated, so it is checked first:
   * sealing under a dead pair would store something no unlock can ever open.
   */
  override fun seal(payment: StoredPayment): Sealing {
    val lost = existing()?.let(::invalidated) == true
    if (lost) keystore().deleteEntry(ALIAS)
    val public = existing()?.public ?: generate()

    val dataKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val sealer = Cipher.getInstance(SEAL).apply { init(Cipher.ENCRYPT_MODE, dataKey) }
    val plain = Json.encodeToString(payment).toByteArray(Charsets.UTF_8)
    val ciphertext = sealer.doFinal(plain)
    val wrapped =
      Cipher.getInstance(WRAP).apply { init(Cipher.ENCRYPT_MODE, public, OAEP) }.doFinal(dataKey.encoded)

    return Sealing(
      Sealed(wrappedKey = encode(wrapped), iv = encode(sealer.iv), ciphertext = encode(ciphertext)),
      previousLost = lost,
    )
  }

  fun opener(): VaultCipher {
    val pair = existing() ?: return VaultCipher.Invalidated
    return try {
      VaultCipher.Ready(
        Cipher.getInstance(WRAP).apply { init(Cipher.DECRYPT_MODE, pair.private, OAEP) }
      )
    } catch (e: KeyPermanentlyInvalidatedException) {
      keystore().deleteEntry(ALIAS)
      VaultCipher.Invalidated
    }
  }

  /** [cipher] must be the one [opener] returned, after the prompt has unlocked it. */
  fun open(cipher: Cipher, sealed: Sealed): StoredPayment {
    val dataKey = SecretKeySpec(cipher.doFinal(decode(sealed.wrappedKey)), "AES")
    val opener =
      Cipher.getInstance(SEAL).apply {
        init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(TAG_BITS, decode(sealed.iv)))
      }
    return Json.decodeFromString(opener.doFinal(decode(sealed.ciphertext)).toString(Charsets.UTF_8))
  }

  private data class Pair(val public: PublicKey, val private: PrivateKey)

  private fun existing(): Pair? {
    val store = keystore()
    val private = store.getKey(ALIAS, null) as PrivateKey? ?: return null
    return Pair(store.getCertificate(ALIAS).publicKey, private)
  }

  /** Initialising needs no unlock, but it is where an invalidated key says so. */
  private fun invalidated(pair: Pair): Boolean =
    try {
      Cipher.getInstance(WRAP).init(Cipher.DECRYPT_MODE, pair.private, OAEP)
      false
    } catch (e: KeyPermanentlyInvalidatedException) {
      true
    }

  private fun generate(): PublicKey {
    val spec =
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(2048)
        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
        .setUserAuthenticationRequired(true)
        .apply {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setUserAuthenticationParameters(
              0,
              KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
          } else {
            @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
          }
        }
        .build()
    return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
      .apply { initialize(spec) }
      .generateKeyPair()
      .public
  }

  private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

  private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
