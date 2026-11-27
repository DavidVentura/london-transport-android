package dev.davidv.londontransport.ui

import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.davidv.londontransport.data.PaymentVault
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.data.VaultCipher
import dev.davidv.londontransport.domain.SavedPaymentMethod
import javax.crypto.Cipher
import java.security.GeneralSecurityException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface Unlocked<out T> {
  data class Done<T>(val value: T) : Unlocked<T>

  /** [message] is null when the rider backed out themselves, so there is nothing to explain. */
  data class Declined(val message: String?) : Unlocked<Nothing>

  data object Invalidated : Unlocked<Nothing>
  data class Failed(val message: String) : Unlocked<Nothing>
}

private sealed interface Prompted {
  data class Unlocked(val cipher: Cipher) : Prompted

  data class Declined(val message: String?) : Prompted
}

/** The prompt needs a live activity, so this lives with the activity rather than in the graph. */
interface PaymentUnlocker {
  fun available(): Boolean
  suspend fun open(saved: SavedPaymentMethod): Unlocked<StoredPayment>
}

class AndroidPaymentUnlocker(private val activity: FragmentActivity, private val vault: PaymentVault) : PaymentUnlocker {
  override fun available(): Boolean = vault.available()

  override suspend fun open(saved: SavedPaymentMethod): Unlocked<StoredPayment> =
    when (val ready = vault.opener()) {
      VaultCipher.Invalidated -> Unlocked.Invalidated
      is VaultCipher.Ready ->
        when (val prompted = prompt(ready.cipher, "Pay with ${saved.name}")) {
          is Prompted.Unlocked -> try {
            Unlocked.Done(vault.open(prompted.cipher, saved.sealed))
          } catch (e: GeneralSecurityException) {
            Unlocked.Failed("Could not decrypt this saved payment method.")
          } catch (e: IllegalArgumentException) {
            Unlocked.Failed("This saved payment method is malformed.")
          }
          is Prompted.Declined -> Unlocked.Declined(prompted.message)
        }
    }

  private suspend fun prompt(cipher: Cipher, title: String): Prompted =
    suspendCancellableCoroutine { continuation ->
      val prompt =
        BiometricPrompt(
          activity,
          ContextCompat.getMainExecutor(activity),
          object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
              if (!continuation.isActive) return
              val unlocked = checkNotNull(result.cryptoObject?.cipher) { "prompt dropped the cipher" }
              continuation.resume(Prompted.Unlocked(unlocked))
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
              if (!continuation.isActive) return
              val backedOut =
                code == BiometricPrompt.ERROR_USER_CANCELED ||
                  code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                  code == BiometricPrompt.ERROR_CANCELED
              continuation.resume(Prompted.Declined(if (backedOut) null else message.toString()))
            }
          },
        )
      val info =
        BiometricPrompt.PromptInfo.Builder()
          .setTitle(title)
          .setAllowedAuthenticators(vault.authenticators)
          .apply {
            // The screen lock brings its own way out; a fingerprint-only prompt needs one added.
            if (vault.authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText("Cancel")
          }
          .build()
      prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
      continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
