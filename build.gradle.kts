plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktfmt) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    plugins.apply("com.ncorti.ktfmt.gradle")

    configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { kotlinLangStyle() }
}

tasks.register<Delete>("clean") {
    description = "Deletes all build artifacts."
    delete(rootProject.layout.buildDirectory)
}
