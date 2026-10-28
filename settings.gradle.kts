pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlin2rtc"

include(
    ":kotlin2rtc-core",
    ":kotlin2rtc-codec",
    ":kotlin2rtc-onvif",
    ":kotlin2rtc-rtsp",
    ":kotlin2rtc-tapo",
    ":kotlin2rtc-cli",
)
