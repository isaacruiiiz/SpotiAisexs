pluginManagement {
    buildscript {
        repositories {
            google()
            mavenCentral()
            maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
        }
        dependencies {
            classpath("com.android.tools:r8:9.4.14")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
    }
}
// Downloads the JDK 17 that :audio:decent-usb-audio-driver asks for (jvmToolchain(17))
// when the machine only has Android Studio's bundled JDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "SpotiAisexs"
include(":app")
include(":audio:decent-usb-audio-driver")
