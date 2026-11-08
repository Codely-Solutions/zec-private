plugins {
    id("com.android.library")
    id("secant.android-build-conventions")
    id("secant.jacoco-conventions")
}

android {
    namespace = "co.electriccoin.zcash.test"
    resourcePrefix = "co_electriccoin_zcash_"
}

dependencies {
    api(libs.bundles.androidx.test)
}
