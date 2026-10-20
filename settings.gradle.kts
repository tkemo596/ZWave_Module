pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "IA Releases"
            url = uri("https://nexus.inductiveautomation.com/repository/inductiveautomation-releases")
        }
    }
}

rootProject.name = "zwave-driver"

include(":gateway")
