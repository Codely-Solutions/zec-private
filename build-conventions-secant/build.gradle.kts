import java.util.Properties

plugins {
    `kotlin-dsl`
}

dependencyLocking {
    // This property is treated specially, as it is not defined by default in the root gradle.properties
    // and declaring it in the root gradle.properties is ignored by included builds. This only picks up
    // a value declared as a system property, a command line argument, or a an environment variable.
    val isDependencyLockingEnabled = if (project.hasProperty("ZCASH_IS_DEPENDENCY_LOCKING_ENABLED")) {
        project.property("ZCASH_IS_DEPENDENCY_LOCKING_ENABLED").toString().toBoolean()
    } else {
        true
    }

    if (isDependencyLockingEnabled) {
        lockAllConfigurations()
    }
}

dependencies {
    val rootProperties = getRootProperties()

    constraints {
        // AGP uses these together for host-side certificate and archive handling.
        val bouncyCastleVersion = "1.87"
        implementation("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion") {
            because("Fix certificate name-constraint bypass and ASN.1 nesting limits")
        }
        implementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
        implementation("org.bouncycastle:bcutil-jdk18on:$bouncyCastleVersion")

        // Keep host-tool fixes on the convention plugin classpath.
        implementation("com.google.guava:guava:33.8.0-jre")
        implementation("org.apache.commons:commons-compress:1.28.0")
        implementation("org.apache.commons:commons-lang3:3.21.0")
        implementation("org.bitbucket.b_c:jose4j:0.9.7")
        implementation("org.jdom:jdom2:2.0.6.1")

        // Kover's IntelliJ coverage reporter still requests FreeMarker 2.3.32.
        implementation("org.freemarker:freemarker:2.3.35")
    }

    implementation("com.android.tools.build:gradle:${rootProperties.getProperty("ANDROID_GRADLE_PLUGIN_VERSION")}")
    implementation("com.google.apis:google-api-services-androidpublisher:${rootProperties.getProperty
         ("PLAY_PUBLISHER_API_VERSION")}")
    implementation("com.google.auth:google-auth-library-oauth2-http:${rootProperties.getProperty
         ("GOOGLE_AUTH_LIB_JAVA_VERSION")}")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${rootProperties.getProperty("DETEKT_VERSION")}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${rootProperties.getProperty("KOTLIN_VERSION")}")
    implementation(
        "org.jetbrains.kotlin:compose-compiler-gradle-plugin:${rootProperties.getProperty("KOTLIN_VERSION")}"
    )
    implementation(
        "org.jetbrains.kotlinx.kover:org.jetbrains.kotlinx.kover.gradle.plugin:" +
            rootProperties.getProperty("KOVER_VERSION")
    )
    implementation("org.jetbrains:markdown:${rootProperties.getProperty("MARKDOWN_VERSION")}")
}

// Share the root version pins with this included build.
fun getRootProperties(): Properties = Properties().apply {
    File(project.projectDir.parentFile, "gradle.properties").inputStream().use { load(it) }
}
