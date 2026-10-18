import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

