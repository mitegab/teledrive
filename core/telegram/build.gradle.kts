plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.teledrive.core.telegram"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/tdlib/jniLibs")
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
}
