plugins {
    id("java")
}

val ktlint = configurations.create("ktlint")

dependencies {
    add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:${project.property("KTLINT_VERSION")}") {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named<Bundling>(Bundling.EXTERNAL))
        }
    }
    constraints {
        // ktlint 1.8.0 brings Logback 1.3.16. Keep the CLI's SLF4J provider and core aligned.
        val logbackVersion = "1.5.38"
        add(ktlint.name, "ch.qos.logback:logback-classic:$logbackVersion")
        add(ktlint.name, "ch.qos.logback:logback-core:$logbackVersion")
    }
}

tasks {
    val editorConfigFile = rootProject.file("tools/.editorconfig")
    val ktlintArgs = listOf("**/src/**/*.kt", "!**/build/**.kt", "--editorconfig=$editorConfigFile")

    register("ktlint", org.gradle.api.tasks.JavaExec::class) {
        description = "Check code style with ktlint"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args = ktlintArgs
    }

    register("ktlintFormat", org.gradle.api.tasks.JavaExec::class) {
        // https://github.com/pinterest/ktlint/issues/1195#issuecomment-1009027802
        jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")

        description = "Apply code style formatting with ktlint"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args = listOf("-F") + ktlintArgs
    }
}

java {
    val javaVersion = JavaVersion.toVersion(project.property("ANDROID_JVM_TARGET").toString())
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}
