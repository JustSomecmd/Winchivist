enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "winchivist"

// The Windows app
include(":app")

// TubeArchivist's API and models, the same as in the phone app, Tubedroid
include(":tubearchivist")

// The JRWI archiver's API and models, the same as in the phone app
include(":jrwi")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
