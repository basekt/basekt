pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "basekt"

// v0.1.0. Later modules (realtime, storage, client, admin-ui, audit, more data adapters) are in README.md's roadmap.
include(
    ":core:basekt-schema",
    ":server:basekt-server",
    ":server:basekt-data",
    ":server:basekt-data-memory",
    ":server:basekt-data-exposed",
    ":server:basekt-auth",
    ":admin:basekt-admin-api",
    ":testing:basekt-testing",
    ":samples:sample-shop",
)
