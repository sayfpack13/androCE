plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Groovy subprojects (Bcore) read these via rootProject.ext
extra["compileSdkVersion"] = 35
extra["minSdk"] = 26
extra["targetSdkVersion"] = 35
