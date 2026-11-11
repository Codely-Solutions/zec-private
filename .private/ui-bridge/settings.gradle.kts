pluginManagement {
    val productVersions = java.util.Properties()
    settingsDir.resolve("../../gradle.properties").inputStream().use(productVersions::load)
    plugins {
        id("com.android.application") version requireNotNull(productVersions.getProperty("ANDROID_GRADLE_PLUGIN_VERSION"))
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name="UiBridge"
