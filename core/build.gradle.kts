import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.agp.lib)
}

val appPackageName: String by rootProject.extra

extensions.configure<LibraryExtension>("android") {
    namespace = "$appPackageName.core"

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(projects.common)

    testImplementation(libs.junit)
}
