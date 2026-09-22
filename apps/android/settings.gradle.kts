import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
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
    ":tailnet-core",
    ":transport",
    ":encrypted-store",
    ":platform-kernel",
    ":plugin-package",
    ":plugin-runtime-wasm",
    ":plugin-ui",
    ":companion-bridge",
    ":tailscale-companion",
)
