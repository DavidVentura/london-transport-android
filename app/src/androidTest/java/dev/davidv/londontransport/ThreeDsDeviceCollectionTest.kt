package dev.davidv.londontransport

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.davidv.londontransport.threeds.AndroidDeviceCollector
import dev.davidv.londontransport.threeds.DeviceParameter
import dev.davidv.londontransport.threeds.ProtocolResult
import dev.davidv.londontransport.threeds.SecurityWarning
import dev.davidv.londontransport.threeds.SigningCertificateFingerprint
import dev.davidv.londontransport.threeds.UnavailableReason
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeDsDeviceCollectionTest {
  @Suppress("DEPRECATION")
  @Test fun collectsActualDeviceFactsWithoutRequestingNewPermissions() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
      checkNotNull(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
    } else {
      checkNotNull(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures)
    }
    val signers = signatures.map {
      SigningCertificateFingerprint(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) })
    }.toSet()
    val result = AndroidDeviceCollector(context, signers).collect()
    assertTrue(result is ProtocolResult.Success)
    val device = (result as ProtocolResult.Success).value
    assertEquals("${Build.MANUFACTURER} ${Build.MODEL}", device.data.model)
    assertEquals(Build.VERSION.RELEASE, device.data.osVersion)
    assertEquals(context.packageName, device.data.packageName)
    assertEquals(Build.BOARD.takeIf { it.isNotBlank() }, device.data.board)
    assertEquals(Build.FINGERPRINT.takeIf { it.isNotBlank() }, device.data.buildFingerprint)
    assertEquals(Build.VERSION.SDK_INT.toString(), device.data.androidApi)
    assertEquals(Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }, device.data.securityPatch)
    assertEquals(context.packageManager.systemAvailableFeatures.size.toString(), device.data.systemFeatureCount)
    assertTrue(checkNotNull(device.data.dataStorageBytes).toLong() > 0)
    assertEquals(Build.SUPPORTED_32_BIT_ABIS.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }, device.data.supported32BitAbis)
    assertEquals(Build.SUPPORTED_64_BIT_ABIS.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }, device.data.supported64BitAbis)
    val installer = if (Build.VERSION.SDK_INT >= 30) {
      context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
      context.packageManager.getInstallerPackageName(context.packageName)
    }
    assertEquals(installer?.takeIf { it.isNotBlank() }, device.data.installerPackage)
    if (installer.isNullOrBlank()) {
      assertEquals(UnavailableReason.NoData, device.unavailable.getValue(DeviceParameter.InstallerPackage))
    }
    assertFalse(device.warnings.contains(SecurityWarning.Tampered))
    DeviceParameter.entries.filter { it.restrictedByPolicy }.forEach { parameter ->
      assertEquals(UnavailableReason.Restricted, device.unavailable.getValue(parameter))
    }
    if (Build.VERSION.SDK_INT >= 30) {
      assertNull(device.data.screenResolution)
      assertEquals(UnavailableReason.Unsupported, device.unavailable.getValue(DeviceParameter.Screen))
    }
    when (val output = InstrumentationRegistry.getArguments().getString("threeDsOutput")) {
      null -> Unit
      "device-json" -> InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("threeDsDeviceData", Json.encodeToString(device))
      })
      else -> error("Unsupported collector output: $output")
    }
  }
}
