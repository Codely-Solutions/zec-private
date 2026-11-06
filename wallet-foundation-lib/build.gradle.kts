plugins {
    kotlin("jvm")
    id("secant.dependency-conventions")
}

kotlin {
    jvmToolchain(project.property("JVM_TOOLCHAIN").toString().toInt())
    compilerOptions {
        val target = project.property("KOTLIN_JVM_TARGET").toString()
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(if (target == "8") "1.8" else target)
        )
        allWarningsAsErrors.set(project.property("ZCASH_IS_TREAT_WARNINGS_AS_ERRORS").toString().toBoolean())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(project.property("KOTLIN_JVM_TARGET").toString().toInt())
}

dependencies {
    testImplementation(kotlin("test-junit"))
}
