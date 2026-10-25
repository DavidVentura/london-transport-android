import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val santanderSecrets = rootProject.layout.projectDirectory.dir(".local-secrets/santander")
abstract class GenerateThreeDsConstants : DefaultTask() {
  @get:InputFile
  abstract val profileFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val profile = Properties().apply {
      profileFile.get().asFile.inputStream().use(::load)
    }
    fun constant(name: String): String {
      val value = profile.getProperty(name) ?: throw GradleException("Missing local Santander profile property: $name")
      if (value.isBlank()) throw GradleException("Empty local Santander profile property: $name")
      return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r")
    }
    val properties = listOf("SDK_REFERENCE_NUMBER", "SDK_PACKAGE", "SDK_VERSION", "SDK_SOURCE_VERSION_CODE", "SDK_CAPTURED_DATE")
    val name = "ThreeDsLocalConstants"
    val output = outputDirectory.get().file("dev/davidv/londontransport/local/$name.kt").asFile
    output.parentFile.mkdirs()
    output.writeText("package dev.davidv.londontransport.local\n\nobject $name {\n" +
      properties.joinToString("\n") { "  const val $it = \"${constant(it)}\"" } + "\n}\n")
  }
}

val generateLocalConstants = tasks.register<GenerateThreeDsConstants>("generateThreeDsLocalConstants") {
  profileFile.set(santanderSecrets.file("profile.properties"))
  outputDirectory.set(layout.buildDirectory.dir("generated/three-ds-local"))
}

androidComponents {
  onVariants(selector().withBuildType("debug")) { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(generateLocalConstants, GenerateThreeDsConstants::outputDirectory)
  }
}

// The keystore password is read from ~/.gradle/gradle.properties rather than the environment,
// which every child process can read.
val devKeystore: String? = System.getenv("LONDON_TRANSPORT_KEYSTORE")
val devKeystorePassword: String? =
  providers.gradleProperty("londonTransportKeystorePassword").orNull
    ?: if (devKeystore == null) {
      null
    } else {
      throw GradleException(
        "LONDON_TRANSPORT_KEYSTORE is set but londonTransportKeystorePassword is not; " +
          "add it to ~/.gradle/gradle.properties, or unset the variable to sign with the debug key"
      )
    }

android {
  sourceSets {
    getByName("debug").resources.srcDir(santanderSecrets.dir("resources").asFile)
  }
  namespace = "dev.davidv.londontransport"
  compileSdk = 36
  buildToolsVersion = "36.0.0"

  defaultConfig {
    applicationId = "dev.davidv.londontransport"
    minSdk = 26
    targetSdk = 36
    versionCode = 3
    versionName = "0.2.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // The dependency metadata block AGP appends is encrypted with a Google key, which makes the APK
  // impossible to reproduce byte-for-byte.
  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  // Signing the debug build with the release key keeps a single install on the phone: with two
  // different keys, moving between a local build and a published APK means uninstalling, which
  // takes the saved pins, lines and login with it.
  signingConfigs {
    if (devKeystore != null) {
      create("dev") {
        storeFile = file(devKeystore)
        storePassword = devKeystorePassword
        keyAlias = "key0"
        // PKCS12 holds one password for the store and the key alike.
        keyPassword = devKeystorePassword
      }
    }
  }

  buildTypes {
    debug {
      if (devKeystore != null) {
        signingConfig = signingConfigs.getByName("dev")
      }
    }

    release {
      isMinifyEnabled = true
      isShrinkResources = true
      vcsInfo {
        include = false
      }
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  // The JVM android.jar stubs throw on every call, so a stray Log line in code under test would
  // fail the test instead of being dropped.
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.osmdroid.android)
  debugImplementation(libs.nimbus.jose)
  debugImplementation(libs.androidx.biometric)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testDebugImplementation(libs.bouncycastle.pkix)

  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
