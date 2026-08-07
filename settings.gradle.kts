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
    }
}

rootProject.name = "wear-keyboard"

include(":app")
include(":ui-wear")
include(":ime-core")
include(":layout-engine")
include(":dict")
include(":engine-swipe")
