plugins {
    id("com.android.library")
}

android {
    namespace = "nl.neerdael.milkbeat.sabr"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(libs.androidx.media3.exoplayer)
    api(project(":plugin-api"))
    implementation("androidx.annotation:annotation-jvm:1.10.0")
    api(project(":media3-sabr-protocol"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
