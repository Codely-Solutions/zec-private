pluginManagement {
    repositories {
        mavenCentral {
            content {
                includeGroup("org.jetbrains.kotlin")
            }
        }
        gradlePluginPortal {
            content {
                excludeGroup("org.jetbrains.kotlin")
            }
        }
    }
}

rootProject.name = "buildSrc"
