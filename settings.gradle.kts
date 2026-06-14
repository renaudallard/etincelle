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

rootProject.name = "etincelle"

include(":app-mobile")
include(":app-tv")
include(":core:designsystem")
include(":core:model")
include(":core:domain")
include(":core:network")
include(":core:player")
include(":core:cast")
include(":core:ui")
include(":data:fubo")
