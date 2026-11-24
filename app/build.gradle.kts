import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.room)
}

android {
    namespace = "com.pixelforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixelforge.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2", "-fvisibility=hidden", "-fopenmp")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        val keystorePropsFile = rootProject.file("keystore.properties")
        val keystoreProps = Properties().apply {
            if (keystorePropsFile.exists()) {
                keystorePropsFile.inputStream().use { load(it) }
            }
        }
        val keystorePath = keystoreProps.getProperty("storeFile")
            ?: System.getenv("RELEASE_KEYSTORE_PATH")

        if (!keystorePath.isNullOrBlank()) {
            val resolvedFile = rootProject.file(keystorePath).let {
                if (it.exists()) it else file(keystorePath)
            }
            create("release") {
                storeFile = resolvedFile
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("tflite", "onnx", "bin", "param")
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }
}

// Task to copy libomp.so from NDK into jniLibs
val copyLibOmp = tasks.register<Copy>("copyLibOmp") {
    val ndkDir = project.android.ndkDirectory.absolutePath
    val abi = "arm64-v8a"
    val clangAbi = "aarch64" // Clang uses aarch64 for arm64-v8a in the NDK layout

    // Modern NDKs (23+) store libomp in a multi-level subdirectory.
    // We search the 'toolchains/llvm/prebuilt' directory for the correct libomp.so.
    from(ndkDir)
    include("**/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/$clangAbi/libomp.so")
    
    into(file("src/main/jniLibs/$abi"))
    
    // Flatten the directory structure when copying
    eachFile {
        path = "libomp.so"
    }
    includeEmptyDirs = false
}

// Ensure this task runs before any task that merges native/JNI libs
tasks.configureEach {
    if (name.startsWith("merge") && (name.endsWith("NativeLibs") || name.endsWith("JniLibFolders"))) {
        dependsOn(copyLibOmp)
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.material)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Image Loading & Processing
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    // On-device AI Runtimes
    implementation(libs.onnxruntime.android)
    implementation(libs.mediapipe.tasks.vision)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
