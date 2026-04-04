import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.refine)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.nav.safeargs.kotlin)
}

val appPackageName: String by rootProject.extra
val appVerName: String by rootProject.extra
val localBuild: Boolean by rootProject.extra
val officialBuild: Boolean by rootProject.extra

extensions.configure<ApplicationExtension>("android") {
    namespace = appPackageName

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        dex.useLegacyPackaging = true
        resources {
            excludes += arrayOf(
                "/META-INF/*",
                "/META-INF/androidx/**",
                "/kotlin/**",
                "/okhttp3/**",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.common)
    runtimeOnly(projects.xposed)

    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.com.github.bumptech.glide)
    implementation(libs.dev.androidbroadcast.vbpd)
    implementation(libs.dev.androidbroadcast.vbpd.reflection)
    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.dev.rikka.hidden.compat)
    implementation(libs.me.zhanghai.android.appiconloader)
    compileOnly(libs.dev.rikka.hidden.stub)

    implementation(libs.androidx.appcompat.appcompat)
    implementation(libs.material)
}

fun registerApkRenameTask(buildType: String) {
    val taskSuffix = buildType.replaceFirstChar { it.uppercase() }
    val targetFileName = "pib-$appVerName-$buildType.apk"
    val outputDir = layout.buildDirectory.dir("outputs/apk/$buildType")

    val renameTask = tasks.register("rename${taskSuffix}Apk") {
        doLast {
            val dir = outputDir.get().asFile
            if (!dir.exists()) {
                throw GradleException("APK output directory does not exist: $dir")
            }

            val apkFiles = dir.listFiles { file -> file.extension == "apk" }
                ?.sortedBy { it.name }
                .orEmpty()
            val targetFile = dir.resolve(targetFileName)

            if (apkFiles.isEmpty()) {
                throw GradleException("No APK files found in $dir")
            }

            if (apkFiles.size == 1 && apkFiles.single().name == targetFileName) {
                return@doLast
            }

            val sourceFiles = apkFiles.filter { it.name != targetFileName }
            if (sourceFiles.size != 1) {
                val availableFiles = apkFiles.joinToString { it.name }
                throw GradleException(
                    "Expected exactly one source APK for $buildType, found ${sourceFiles.size}. Files: $availableFiles",
                )
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw GradleException("Unable to delete existing target APK: $targetFile")
            }

            val sourceFile = sourceFiles.single()
            if (!sourceFile.renameTo(targetFile)) {
                throw GradleException("Failed to rename ${sourceFile.name} to ${targetFile.name}")
            }
        }
    }

    tasks.configureEach {
        if (name == "assemble$taskSuffix") {
            finalizedBy(renameTask)
        }
    }
}

registerApkRenameTask("debug")
registerApkRenameTask("release")

