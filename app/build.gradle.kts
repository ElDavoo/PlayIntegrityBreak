import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.google.gson.JsonParser
import org.jose4j.json.internal.json_simple.JSONObject
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.autoresconfig)
    alias(libs.plugins.refine)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.nav.safeargs.kotlin)
    alias(libs.plugins.materialthemebuilder)
}

materialThemeBuilder {
    themes {
        for ((name, color) in listOf(
            "Red" to "F44336",
            "Pink" to "E91E63",
            "Purple" to "9C27B0",
            "DeepPurple" to "673AB7",
            "Indigo" to "3F51B5",
            "Blue" to "2196F3",
            "LightBlue" to "03A9F4",
            "Cyan" to "00BCD4",
            "Teal" to "009688",
            "Green" to "4FAF50",
            "LightGreen" to "8BC3A4",
            "Lime" to "CDDC39",
            "Yellow" to "FFEB3B",
            "Amber" to "FFC107",
            "Orange" to "FF9800",
            "DeepOrange" to "FF5722",
            "Brown" to "795548",
            "BlueGrey" to "607D8F",
            "Sakura" to "FF9CA8"
        )) {
            create("Material$name") {
                lightThemeFormat = "ThemeOverlay.Light.%s"
                darkThemeFormat = "ThemeOverlay.Dark.%s"
                primaryColor = "#$color"
            }
        }
    }
    // Add Material Design 3 color tokens (such as palettePrimary100) in generated theme
    // rikka.material >= 2.0.0 provides such attributes
    generatePalette = false
}

val appPackageName: String by rootProject.extra
val localBuild: Boolean by rootProject.extra
val officialBuild: Boolean by rootProject.extra

@Suppress("deprecation")
afterEvaluate {
    val srcDir = android.sourceSets["main"].assets.srcDirs.first()
    logger.lifecycle("Asset dir: $srcDir")
    if (!srcDir.exists()) srcDir.mkdirs()

    val translatorsMap = mutableMapOf(
        // Keep one known translator profile if remote metadata fetch fails.
        "cvnertnc" to "https://avatars.githubusercontent.com/u/148134890?v=4",
    )

    runCatching {
        val urlConnection = URL("https://github.com/frknkrc44/PIB/releases/latest/download/translators.json")
            .openConnection() as HttpURLConnection

        val inputStream = DataInputStream(urlConnection.getInputStream())
        val str = String(inputStream.readAllBytes())
        inputStream.close()
        urlConnection.disconnect()

        val json = JsonParser.parseString(str).asJsonObject
        json.keySet().forEach { translatorsMap[it] = json.get(it).asString }
    }.onFailure {
        logger.lifecycle("Failed to fetch translators metadata, using bundled defaults")
    }

    val translatorJson = JSONObject(translatorsMap).toJSONString()
    File(srcDir, "translators.json").writeText(translatorJson)
}

android {
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

autoResConfig {
    generateClass.set(true)
    generateRes.set(false)
    generatedClassFullName.set("icu.nullptr.playintegritybreak.util.LangList")
    generatedArrayFirstItem.set("SYSTEM")
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

android.applicationVariants.all {
    outputs.all {
        (this as BaseVariantOutputImpl).apply {
            outputFileName = "${rootProject.name.replace(" ", "_")}-${versionName}-${buildType.name}.apk"
        }
    }
}
