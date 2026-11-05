plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "zec.privateprobe"
 compileSdk = 36
 defaultConfig {
  applicationId = "zec.privateprobe"; minSdk = 27; targetSdk = 36
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "x86_64" }
  missingDimensionStrategy("network", "zcashmainnet")
  missingDimensionStrategy("distribution", "foss")
 }
 compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation(project(":ui-lib"))
 implementation("com.zodl.android:zcash-android-backend:6.0.0")
 implementation(platform("io.ktor:ktor-bom:3.4.0"))
 implementation("io.ktor:ktor-client-core")
 implementation("io.ktor:ktor-client-content-negotiation")
 implementation("io.ktor:ktor-serialization-kotlinx-json")
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
