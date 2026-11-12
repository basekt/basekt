pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Kotlin Multiplatform's wasmJs and Native targets register Node.js, Binaryen and Kotlin/Native toolchain
    // distribution repositories on the root project, which FAIL_ON_PROJECT_REPOS would reject.
    repositories {
        mavenCentral()
    }
}

rootProject.name = "basekt"

// Later modules (realtime, client, more data adapters) are in README.md's roadmap.
include(
    ":core:basekt-schema",
    ":client:basekt-auth-client",
    ":client:basekt-auth-client-firebase",
    ":client:basekt-auth-client-supabase",
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
    ":commerce:basekt-payment",
    ":commerce:basekt-payment-memory",
    ":commerce:basekt-payment-paymongo",
    ":commerce:basekt-payment-paymentwall",
    ":commerce:basekt-payment-lemonsqueezy",
    ":commerce:basekt-payment-kaia",
    ":commerce:basekt-payments",
    ":discord:basekt-discord",
    ":discord:basekt-discord-link",
    ":discord:basekt-discord-link-memory",
    ":admin:basekt-admin-api",
    ":admin:basekt-admin-ui",
    ":testing:basekt-testing",
    ":samples:sample-shop",
    ":samples:sample-lessons",
)
