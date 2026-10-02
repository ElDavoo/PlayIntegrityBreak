import com.android.build.api.dsl.ApplicationExtension
import java.util.Locale
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.agp.app)
}

val appPackageName: String by rootProject.extra
val appVerName: String by rootProject.extra
val appVerCode: Int by rootProject.extra

val moduleAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

extensions.configure<ApplicationExtension>("android") {
    namespace = "$appPackageName.zygisk"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = namespace
        ndk {
            abiFilters += moduleAbis
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildFeatures {
        buildConfig = false
        prefab = true
    }

    buildTypes {
        // The payload is loaded from a single classes.dex, so keep debug builds shrunk too.
        named("debug") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        named("release") {
            // There are no resources to shrink, only the payload dex.
            isShrinkResources = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        resources {
            excludes += arrayOf("/META-INF/**", "/kotlin/**", "**.bin")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

val lsplantAar: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(projects.core)

    implementation(libs.io.github.vvb2060.ndk.dobby)
    implementation(libs.org.lsposed.lsplant.standalone)
    lsplantAar(libs.org.lsposed.lsplant.standalone) {
        artifact { type = "aar" }
    }
}

// Builds the flashable Magisk/KernelSU module: zygisk/<abi>.so + classes.dex + liblsplant + PIB app.
for (variant in listOf("debug", "release")) {
    val variantCapped = variant.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val payloadApk = layout.buildDirectory.file("outputs/apk/$variant/zygisk-$variant.apk")
    val appApkDir = project(":app").layout.buildDirectory.dir("outputs/apk/$variant")

    tasks.register<Zip>("zipZygisk$variantCapped") {
        group = "build"
        description = "Assembles the $variant Zygisk module zip"
        dependsOn("assemble$variantCapped", ":app:assemble$variantCapped")

        archiveFileName.set("PIB-ZYGISK-$appVerName-$variant.zip")
        destinationDirectory.set(layout.buildDirectory.dir("outputs/zip"))

        from("src/main/module") {
            filesMatching("module.prop") {
                expand(
                    "version" to appVerName,
                    "versionCode" to appVerCode.toString(),
                )
            }
        }
        from(payloadApk.map { zipTree(it) }) {
            include("classes.dex")
            include("lib/*/libpib_zygisk.so")
            eachFile { if (path.startsWith("lib/")) path = "zygisk/${relativePath.segments[1]}.so" }
        }
        from(lsplantAar.elements.map { files -> files.map { zipTree(it) } }) {
            include("prefab/modules/lsplant/libs/*/liblsplant.so")
            eachFile {
                val abi = relativePath.segments[4].removePrefix("android.")
                if (abi in moduleAbis) path = "lsplant/$abi.so" else exclude()
            }
        }
        from(appApkDir) {
            include("*.apk")
            rename { "pib.apk" }
        }
        includeEmptyDirs = false

        val requiredEntries = listOf("module.prop", "customize.sh", "classes.dex", "pib.apk") +
            moduleAbis.flatMap { listOf("zygisk/$it.so", "lsplant/$it.so") }
        doLast {
            val zip = archiveFile.get().asFile
            val entries = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toSet() }
            val missing = requiredEntries - entries
            check(missing.isEmpty()) { "$zip is missing $missing" }
            logger.lifecycle("Zygisk module: $zip")
        }
    }
}
