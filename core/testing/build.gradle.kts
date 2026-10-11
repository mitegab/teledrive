plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}
