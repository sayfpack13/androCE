plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.androce"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androce"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/injectorAssets"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

/* APK lib/ only ships .so — package speedinjector under assets/injectors/<abi>/ */
val copySpeedInjector by tasks.registering(Copy::class) {
    dependsOn(tasks.named("externalNativeBuildDebug"))
    doFirst {
        delete(layout.buildDirectory.dir("generated/injectorAssets/injectors"))
    }
    from(layout.buildDirectory.dir("intermediates/cxx/Debug")) {
        include("*/obj/*/speedinjector")
        eachFile {
            val abi = relativePath.segments[relativePath.segments.size - 2]
            relativePath = org.gradle.api.file.RelativePath(true, abi, "speedinjector")
        }
    }
    into(layout.buildDirectory.dir("generated/injectorAssets/injectors"))
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    doLast {
        logger.lifecycle("androCE: staged speedinjector in APK assets")
    }
}

afterEvaluate {
    tasks.matching {
        it.name.startsWith("merge") && it.name.endsWith("Assets")
    }.configureEach { dependsOn(copySpeedInjector) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":Bcore"))
    debugImplementation(libs.androidx.ui.tooling)
}
