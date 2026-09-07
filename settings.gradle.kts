pluginManagement {
    includeBuild("build-logic")
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
rootProject.name = "Look4Sat"
include(":app")
include(
    ":core:cw",
    ":core:data",
    ":core:domain",
    ":core:ft4-native",
    ":core:presentation"
)
include(
    ":feature:ft4",
    ":feature:logbook",
    ":feature:map",
    ":feature:mutual",
    ":feature:passes",
    ":feature:radar",
    ":feature:satellites",
    ":feature:settings",
    ":feature:status"
)
