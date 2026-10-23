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

// Later modules (realtime, storage, client, audit, more data adapters) are in README.md's roadmap.
include(
    ":core:basekt-schema",
    ":server:basekt-server",
    ":server:basekt-data",
    ":server:basekt-data-memory",
    ":server:basekt-data-exposed",
    ":server:basekt-auth",
    ":server:basekt-storage",
    ":server:basekt-storage-memory",
    ":server:basekt-storage-local",
    ":server:basekt-files",
    ":admin:basekt-admin-api",
    ":admin:basekt-admin-ui",
    ":testing:basekt-testing",
    ":samples:sample-shop",
)
