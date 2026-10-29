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

// Later modules (realtime, client, more data adapters) are in README.md's roadmap.
include(
    ":core:basekt-schema",
    ":server:basekt-server",
    ":server:basekt-data",
    ":server:basekt-data-memory",
    ":server:basekt-data-exposed",
    ":server:basekt-data-mongo",
    ":server:basekt-data-firestore",
    ":server:basekt-auth",
    ":server:basekt-auth-firebase",
    ":server:basekt-auth-supabase",
    ":server:basekt-storage",
    ":server:basekt-storage-memory",
    ":server:basekt-storage-local",
    ":server:basekt-storage-s3",
    ":server:basekt-files",
    ":server:basekt-audit",
    ":server:basekt-richtext",
    ":server:basekt-openapi",
    ":commerce:basekt-commerce",
    ":commerce:basekt-storefront",
    ":admin:basekt-admin-api",
    ":admin:basekt-admin-ui",
    ":testing:basekt-testing",
    ":samples:sample-shop",
    ":samples:sample-lessons",
)
