pluginManagement {
    val productVersions = java.util.Properties()
    settingsDir.resolve("../../gradle.properties").inputStream().use(productVersions::load)
    val androidGradlePlugin = requireNotNull(productVersions.getProperty("ANDROID_GRADLE_PLUGIN_VERSION"))
    plugins {
        id("com.android.application") version androidGradlePlugin
    }
    repositories {
        google { content { excludeGroup("org.jetbrains.kotlin") } }
        mavenCentral()
        gradlePluginPortal { content { excludeGroup("org.jetbrains.kotlin") } }
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name="UiBridge"
