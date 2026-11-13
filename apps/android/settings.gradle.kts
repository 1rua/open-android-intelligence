import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // CI can reach the official repositories directly; a mirror outage
        // must not disable dependency resolution for every build task.
        if (!providers.environmentVariable("CI").orNull.equals("true", ignoreCase = true)) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!providers.environmentVariable("CI").orNull.equals("true", ignoreCase = true)) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "open-android-intelligence-android"
include(
    ":app",
    ":assistant-holder",
    ":artifact-ports",
    ":capability-ports",
    ":capability-sync-runtime",
    ":core-model",
    ":control-ports",
    ":screen-control",
    ":gateway-client",
    ":conversation-domain",
    ":conversation-data",
    ":conversation-ui",
    ":policy-engine",
    ":notification-control",
    ":notification-host",
    ":notification-collector",
    ":sms-collector",
    ":call-log-collector",
    ":encrypted-store",
    ":platform-kernel",
    ":device-primitives",
    ":plugin-package",
    ":plugin-runtime-wasm",
    ":plugin-ui",
    ":ui-resources",
)
