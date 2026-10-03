pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Prebuilt sing-box core (AndroidLibBoxLite) lives in app/libs/libbox.aar.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "sb-AI"
include(":app", ":backdrop")
