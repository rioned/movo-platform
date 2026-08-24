plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.movo.rider"
    compileSdk = 35
    defaultConfig { applicationId = "com.movo.rider"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    // Point a debug build at any MOVO server without editing this file:
    //   ./gradlew :rider-app:installDebug -PmovoApiBaseUrl=http://10.0.2.2:3000
    val debugApiBaseUrl = (project.findProperty("movoApiBaseUrl") as String?) ?: "https://movo-vervice.tech"
    buildTypes {
        debug { buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"") }
        release {
            // A release build with no (or non-https) API base URL would silently ship with
            // cleartext or an empty endpoint. The check itself is deferred below — buildTypes
            // blocks configure eagerly for every variant on every invocation, so an eager
            // error() here would fail `installDebug` too and break the LAN-debug workflow
            // documented above.
            val releaseApiBaseUrl = project.findProperty("movoApiBaseUrl") as String?
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiBaseUrl.orEmpty()}\"")
        }
    }
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it.path.startsWith(":${project.name}:") && it.name.contains("Release") }) {
        val releaseApiBaseUrl = project.findProperty("movoApiBaseUrl") as String?
        if (releaseApiBaseUrl.isNullOrBlank() || !releaseApiBaseUrl.startsWith("https://")) {
            throw GradleException("movoApiBaseUrl must be set to an https:// URL for release builds, e.g. -PmovoApiBaseUrl=https://movo-vervice.tech (got: ${releaseApiBaseUrl ?: "<unset>"})")
        }
    }
}

dependencies {
    implementation(project(":design"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.socket:socket.io-client:2.1.1") { exclude(group = "org.json", module = "json") }
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The Android JSON stubs throw on every call; the real implementation lets the
    // rider workflow be tested against the payloads the platform actually returns.
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
