pluginManagement {
    repositories {
        mavenCentral {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
        gradlePluginPortal {
            content {
                excludeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
    }
}

rootProject.name = "buildSrc"
