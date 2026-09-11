plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.movo.customer"
    compileSdk = 35
    defaultConfig { applicationId = "com.movo.customer"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    // Both installable variants always use the official MOVO API provider.
    val movoApiBaseUrl = "https://movo-vervice.tech"
    // Optional MapTiler key for hosted map tiles (see MapTileSources in :design);
    // unset means the map falls back to osmdroid's default Mapnik tiles. Set it via
    // -PmaptilerApiKey=... or a maptilerApiKey= line in ~/.gradle/gradle.properties
    // (not the committed gradle.properties) — same key as the server's MAPTILER_API_KEY.
    val maptilerApiKey = (project.findProperty("maptilerApiKey") as String?) ?: ""
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$movoApiBaseUrl\"")
            buildConfigField("String", "MAPTILER_API_KEY", "\"$maptilerApiKey\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$movoApiBaseUrl\"")
            buildConfigField("String", "MAPTILER_API_KEY", "\"$maptilerApiKey\"")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.socket:socket.io-client:2.1.1") { exclude(group = "org.json", module = "json") }
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
