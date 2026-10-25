package dev.davidv.londontransport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.davidv.londontransport.threeds.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeDsRuntimeInitializationTest {
  @get:Rule val folder = TemporaryFolder()

  @Test fun installationIdentityPersistsAndCorruptionDoesNotReplaceIt() {
    val directory = folder.newFolder()
    val first = (InstallationSdkIdentity(directory).loadOrCreate() as RuntimeResult.Success).value
    assertEquals(RuntimeResult.Success(first), InstallationSdkIdentity(directory).loadOrCreate())
    File(directory, "three-ds-app-id").writeText("corrupt")
    assertEquals(RuntimeResult.Failure(RuntimeFault.IdentityStorage), InstallationSdkIdentity(directory).loadOrCreate())
    assertEquals("corrupt", File(directory, "three-ds-app-id").readText())
  }

  @Test fun explicitlyProvisionedProfileInitializesWithoutFetchingConfiguration() = runBlocking {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.getString("threeDsInitialize") == "true")
    val context = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(context.noBackupFilesDir, "three-ds-profile")
    val first = AndroidThreeDsRuntime.create(context, directory)
    assertTrue("Runtime initialization failed: $first", first is RuntimeResult.Success)
    val second = AndroidThreeDsRuntime.create(context, directory) as RuntimeResult.Success
    assertEquals((first as RuntimeResult.Success).value.appId, second.value.appId)
  }

  @Test fun missingProfileFailsExplicitlyWithoutCreatingAnIdentity() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals(RuntimeResult.Failure(RuntimeFault.ProfileRead),
      AndroidThreeDsRuntime.create(context, folder.newFolder()))
  }
}
