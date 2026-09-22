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
        // JitPack removed – was causing 401 Unauthorized for Xposed and other packages in CI
        // RootEncoder and Xposed APIs now provided via local JARs in app/libs/
        maven {
            name = "TarsosDSP repository"
            url = uri("https://mvn.0110.be/releases")
        }
    }
}
rootProject.name = "Android Virtual Cam"
include(":app")
