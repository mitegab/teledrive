plugins {
    alias(libs.plugins.android.library)
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android's org.json is a stub in JVM unit tests; the real implementation lets fixtures be parsed and mapped.
    testImplementation(libs.json.reference)
}
