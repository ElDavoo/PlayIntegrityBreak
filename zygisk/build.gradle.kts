import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Locale

plugins {
    alias(libs.plugins.agp.app)
    // Java-only Zygisk module, like HMA-OSS: ZygoteLoader injects our dex, AndroidVMTools hooks.
    alias(libs.plugins.zygoteloader)
}

val appPackageName: String by rootProject.extra
val appVerName: String by rootProject.extra

extensions.configure<ApplicationExtension>("android") {
    namespace = "$appPackageName.zygisk"

    defaultConfig {
        applicationId = namespace
    }

    buildFeatures {
        buildConfig = false
    }

    buildTypes {
        named("release") {
            // There are no resources to shrink, only the payload dex.
            isShrinkResources = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

zygisk {
    // Only the Play Store gets the payload (Constants.VENDING_PACKAGE_NAME).
    packages("com.android.vending")

    id = "pib_zygisk"
    name = "Play Integrity Break (Zygisk)"
    author = "ElDavoo"
    description = "Logs and optionally intercepts Play Integrity requests in the Play Store. Zygisk backend of PIB."
    entrypoint = "icu.nullptr.playintegritybreak.zygisk.ZygiskEntry"
    archiveName = "PIB-ZYGISK-$appVerName"
    isAddVariantToArchiveName = true
}

dependencies {
    implementation(projects.core)
    implementation(libs.com.github.aerath.stuff.androidvmtools)
}

/** Copies the PIB app APK into the module's assets, so the zip ships it as pib.apk. */
abstract class BundleAppTask : DefaultTask() {
    @get:InputFiles
    abstract val appApkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val apk = appApkDir.asFileTree.matching { include("*.apk") }.singleFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        apk.copyTo(out.resolve("pib.apk"))
    }
}

extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
    onVariants { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val bundleApp = tasks.register<BundleAppTask>("bundle${variantCapped}PibApp") {
            dependsOn(":app:assemble$variantCapped")
            appApkDir.set(project(":app").layout.buildDirectory.dir("outputs/apk/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundleApp, BundleAppTask::outputDir)

        // ZygoteLoader builds the zip in assemble<Variant>; keep the old task name for CI/README.
        tasks.register("zipZygisk$variantCapped") {
            group = "build"
            description = "Assembles the ${variant.name} Zygisk module zip"
            dependsOn("zipMagisk$variantCapped")
        }
    }
}
