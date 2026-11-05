plugins { id("com.android.application") version "8.13.2" }
android {
 namespace="zec.uibridge"; compileSdk=36
 defaultConfig { applicationId="zec.uibridge"; minSdk=27; targetSdk=36 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
