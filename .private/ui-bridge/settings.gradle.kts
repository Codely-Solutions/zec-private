import org.gradle.api.artifacts.verification.DependencyVerificationMode

pluginManagement {
    val shared = settingsDir.resolve("../../gradle/verification-metadata.xml").toPath()
    val standalone = settingsDir.resolve("gradle/verification-metadata.xml").toPath()
    val parameters = gradle.startParameter
    check(
        java.nio.file.Files.isRegularFile(standalone) &&
            java.nio.file.Files.mismatch(shared, standalone) == -1L &&
            parameters.dependencyVerificationMode == DependencyVerificationMode.STRICT &&
            parameters.writeDependencyVerifications.isEmpty()
    ) { "Build the UI bridge via .private/build_ui_bridge.py with the reviewed verification metadata and strict mode." }

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
