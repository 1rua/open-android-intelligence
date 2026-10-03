plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.openandroidintelligence.device.primitives" }
dependencies {
    implementation(project(":platform-kernel"))
    implementation(project(":gateway-client"))
    implementation(project(":core-model"))
    implementation(project(":capability-ports"))
    implementation(project(":sms-collector"))
    implementation(project(":call-log-collector"))
    implementation(project(":notification-collector"))
    implementation(project(":notification-host"))
    implementation(project(":policy-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
