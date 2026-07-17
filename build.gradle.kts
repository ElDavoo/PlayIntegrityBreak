import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.nav.safeargs.kotlin) apply false
}

fun String.execute(currentWorkingDir: File = file("./")): String {
    val out = providers.exec {
        workingDir = currentWorkingDir
        commandLine = split("\\s".toRegex())
    }
    return out.standardOutput.asText.get().trim()
}

val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
val ciBuild = providers.environmentVariable("CI").isPresent
val officialBuild by extra(localProperties.getProperty("officialBuild", "false") == "true")

fun getUncommittedSuffix(): String {
    if (officialBuild) return ""

    if (ciBuild) {
        val headRefVal = providers.environmentVariable("GITHUB_HEAD_REF").orElse("HEAD").get()
        val sanitizedHeadRef = headRefVal
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
            .ifEmpty { "HEAD" }
        return "-$sanitizedHeadRef"
    }

    var returnedVal = ""

    try {
        val branch = "git rev-parse --abbrev-ref HEAD".execute().split("/").last()
        if (branch != "pib") {
            returnedVal += "-$branch"
        }
    } catch (_: Throwable) {}

    val result = "git status -s".execute()
    if (result.isEmpty()) {
        return returnedVal
    }

    return "$returnedVal-dirty+${result.count { it == '\n' } + 1}"
}

val gitHasUncommittedSuffix = getUncommittedSuffix()
val gitCommitCount = "git rev-list HEAD --count".execute().toInt()

// Reset versioning from 1 on the pib branch
val gitCommitCountAfterReset = gitCommitCount - 627

val minSdkVer by extra(29)
val targetSdkVer by extra(37)
val buildToolsVer by extra("37.0.0")

val appVerCode by extra(gitCommitCount + 0x6f7373) // commit count + 0xOSS
val appVerName by extra("${gitCommitCountAfterReset}${gitHasUncommittedSuffix}")

/*
 * configVerCode, serviceVerCode and minBackupVerCode is used by other build.gradle.kts files
 *
 * DO NOT REMOVE THESE LINES
*/

@Suppress("unused")
val configVerCode by extra(93)

@Suppress("unused")
val serviceVerCode by extra(102)

@Suppress("unused")
val minBackupVerCode by extra(65)

@Suppress("unused")
val appPackageName by extra("it.eldavo.pib")

@Suppress("unused")
val localBuild by extra(localProperties.getProperty("localBuild", "false") == "true")

val androidSourceCompatibility = JavaVersion.VERSION_21
val androidTargetCompatibility = JavaVersion.VERSION_21

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

fun Project.configureApplicationExtension() {
    extensions.findByType(ApplicationExtension::class.java)?.run {
        compileSdk = targetSdkVer
        buildToolsVersion = buildToolsVer

        defaultConfig {
            minSdk = minSdkVer
            targetSdk = targetSdkVer
            versionCode = appVerCode
            versionName = appVerName
        }

        val config = localProperties.getProperty("fileDir")?.let {
            signingConfigs.create("config") {
                storeFile = file(it)
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }

        buildTypes {
            all {
                signingConfig = config ?: signingConfigs.getByName("debug")
            }
            named("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }

        compileOptions {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }

        dependenciesInfo {
            // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
            includeInApk = false
            // Disables dependency metadata when building Android App Bundles (for Google Play)
            includeInBundle = false
        }
    }
}

fun Project.configureLibraryExtension() {
    extensions.findByType(LibraryExtension::class.java)?.run {
        compileSdk = targetSdkVer
        buildToolsVersion = buildToolsVer

        defaultConfig {
            minSdk = minSdkVer
            consumerProguardFiles("proguard-rules.pro")
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }

        compileOptions {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureApplicationExtension()
    }
    plugins.withId("com.android.library") {
        configureLibraryExtension()
    }
}
