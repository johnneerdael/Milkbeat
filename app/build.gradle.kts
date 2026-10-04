import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("androidx.baselineprofile")
    alias(libs.plugins.room)
}

// Releases are major.minor from gradle.properties plus a patch number CI counts up on every
// published build (the configured minimum patch for local builds). The version code grows with every release, minor bumps
// included, as long as a minor line stays under 1000 patches.
val milkbeatMajorMinor = providers.gradleProperty("milkbeatVersion").get()
val milkbeatPatch =
    providers.gradleProperty("milkbeatPatch").orNull?.toInt()
        ?: providers.gradleProperty("milkbeatMinimumPatch").orNull?.toInt() ?: 0
val (milkbeatMajor, milkbeatMinor) = milkbeatMajorMinor.split('.').map(String::toInt)

android {
    namespace = "io.github.aedev.flow"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.neerdael.milkbeat"
        minSdk = 26
        targetSdk = 36
        versionCode = milkbeatMajor * 1_000_000 + milkbeatMinor * 1_000 + milkbeatPatch
        versionName = "$milkbeatMajorMinor.$milkbeatPatch"

        testInstrumentationRunner = "io.github.aedev.flow.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Support all architectures for maximum device compatibility
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("github") {
            dimension = "version"
            isDefault = true
            buildConfigField("Boolean", "UPDATER_ENABLED", "true")
            buildConfigField("String", "DISCORD_APPLICATION_ID", "\"1526515771021328514\"")
        }
        create("foss") {
            dimension = "version"
            buildConfigField("Boolean", "UPDATER_ENABLED", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootDir.resolve("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { localProperties.load(it) }
            }

            storeFile = rootDir.resolve("release.keystore")
            storePassword = (project.findProperty("storePassword") as? String)
                ?: localProperties.getProperty("storePassword")
                ?: System.getenv("STORE_PASSWORD")
                ?: ""
            keyAlias = (project.findProperty("keyAlias") as? String)
                ?: localProperties.getProperty("keyAlias")
                ?: System.getenv("KEY_ALIAS")
                ?: ""
            keyPassword = (project.findProperty("keyPassword") as? String)
                ?: localProperties.getProperty("keyPassword")
                ?: System.getenv("KEY_PASSWORD")
                ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
        // Nightly: release-level performance + debug signing so it's easy to
        // sideload. Fixes the laggy-nightly issue reported in #66.
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use release signing if configured, otherwise fallback to debug
            val releaseKeystore =
                try {
                    signingConfigs.getByName("release").storeFile
                } catch (e: Exception) {
                    null
                }
            if (releaseKeystore?.exists() == true) {
                signingConfig = signingConfigs.getByName("release")
                println("Using RELEASE signing config with keystore: ${releaseKeystore.absolutePath}")
            } else {
                signingConfig = null // Let Gradle build an unsigned APK for IzzyOnDroid/F-Droid
                println("WARNING: Release keystore not found. Building UNSIGNED release APK.")
            }
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        // The fixture plugin, built by `npm run build` in plugins/fixture, for the plugin host tests.
        getByName("androidTest").assets.directories.add("$rootDir/plugins/fixture/build")
        getByName("androidTest").assets.directories.add("$rootDir/plugins/spotify/build/android-test-assets")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // Enable desugaring
    }

    packaging {
        resources {
            excludes +=
                listOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/*.version",
                )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

composeCompiler {
    if (project.findProperty("composeCompilerReports") == "true") {
        val reportsDir = layout.buildDirectory.dir("compose_compiler")
        reportsDestination = reportsDir
        metricsDestination = reportsDir
    }
}

// Robolectric supplies the JVM Conscrypt artifact; Android's duplicate classes load Android-only JNI.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}

// ProjectM-TV's core engine AAR. "latest" follows ProjectM-TV's newest stable release (re-checked
// daily, or with --refresh-dependencies); CI passes the exact version it resolved.
val projectmCoreVersion = providers.gradleProperty("projectmCoreVersion").get()

dependencies {
    implementation("nl.neerdael.projectm:projectM-TV-core:$projectmCoreVersion@aar") {
        isChanging = projectmCoreVersion == "latest"
    }
    implementation(project(":plugin-api"))
    implementation(libs.quickjs.kt)
    implementation(libs.smbj)
    // --- Core Android ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    // --- Compose (Using BOM is best practice) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // --- Navigation ---
    implementation(libs.androidx.navigation.compose)

    // --- Lifecycle & Architecture ---
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // --- Image Loading ---
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // --- Dependency Injection ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // --- Data & Network ---
    implementation(libs.newpipe.extractor)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // --- Account sign-in: the phone input server and its QR code ---
    implementation(libs.ktor.server.core) {
        exclude(group = "org.fusesource.jansi", module = "jansi")
    }
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.zxing.core)
    implementation(libs.androidx.webkit)

    // Serialization & JSON
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)

    // conscrypt for OkHttp TLS support on older Android versions
    implementation(libs.conscrypt.android)

    // --- Media Playback ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.inspector)

    // --- Database & Storage ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    // implementation(libs.androidx.datastore) // In TOML if needed

    // --- Async & Utils ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)

    // --- Baseline profiles ---
    // Runtime installer for the merged baseline profile. AGP merges profiles shipped inside
    // library AARs (Compose, RecyclerView, ...) at build time; this applies them at runtime,
    // which matters for sideloaded/F-Droid installs that bypass Play's cloud profiles.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":benchmark"))

    // Desugaring for older Android versions
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)

    // Compose UI tests in the JVM (Robolectric) so CI's unit-test task covers them
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)

    // Room migration tests (device-sync schema 20→23)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Allow references to generated code
ksp {
    arg("dagger.fastInit", "enabled")
}

hilt {
    enableAggregatingTask = true
}
