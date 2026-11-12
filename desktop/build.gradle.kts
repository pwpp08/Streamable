import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.2"
}

val appVersion = "1.1.2"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-nowarn")
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

// ---- Code shared with the Android app ------------------------------------------------------
// Providers, extractors, models and the TMDb client are compiled from ../app as they are. The few
// Android classes they touch (Log, Base64, Uri, Parcelable, MimeTypes...) have small JVM stand-ins
// under src/main/kotlin/android*, and the Android-only helpers (NetworkClient, UserPreferences,
// the WebView extractors) have desktop versions with the same names.
val appSrc = rootDir.resolve("../app/src/main/java/com/streamflixreborn/streamflix")
val sharedDir = layout.buildDirectory.dir("shared-src")

val syncSharedSources by tasks.registering(Sync::class) {
    into(sharedDir)
    from(appSrc) {
        into("com/streamflixreborn/streamflix")
        include(
            "models/**",
            "providers/**",
            "extractors/**",
            "torrent/TorrentioClient.kt",
            "torrent/TorrentModels.kt",
            "utils/TMDb3.kt",
            "utils/TmdbUtils.kt",
            "utils/JsUnpacker.kt",
            "utils/AesHelper.kt",
            "utils/DecryptHelper.kt",
            "utils/CryptoAES.kt",
            "utils/AADecoder.java",
            "utils/StringConverter.kt",
            "utils/DnsResolver.kt",
            "utils/EdgeOrder.kt",
            "utils/Keys.kt",
            "utils/InertiaUtils.kt",
            "utils/UprotResolver.kt",
            "utils/PlaylistUtils.kt",
            "utils/ArtworkRequestHeaders.kt",
            "utils/health/HlsPlaylistParser.kt",
            "utils/health/ServerHealth.kt",
        )
        // These capture streams from inside an Android WebView; desktop has stand-ins.
        exclude(
            "models/Profile.kt",
            "extractors/VidLinkExtractor.kt",
            "extractors/VidsrcRuExtractor.kt",
        )
        // These only use a WebView to follow a JavaScript redirect: desktop follows it over HTTP.
        filesMatching(listOf("**/StreamWishExtractor.kt", "**/UpzoneExtractor.kt")) {
            var skipping = false
            var sawResult = false
            filter { line ->
                when {
                    line.startsWith("import android.webkit") || line.contains("utils.teardownOnMain") -> ""
                    line.contains("fun resolveRedirectWithWebView(") -> {
                        skipping = true
                        sawResult = false
                        val visibility = if (line.trimStart().startsWith("private")) "private " else ""
                        val params = line.substringAfter("resolveRedirectWithWebView(").substringBefore("): String")
                        "    ${visibility}suspend fun resolveRedirectWithWebView($params): String = " +
                            "com.streamflixreborn.streamflix.desktop.web.Redirects.resolve(url)"
                    }
                    skipping -> {
                        if (line.contains("result ?: url")) sawResult = true
                        else if (sawResult && line.trimEnd() == "        }") skipping = false
                        ""
                    }
                    else -> line
                }
            }
        }
    }
    from(rootDir.resolve("../retrofit-jsoup-converter/src/main/java"))
}

// BuildConfig for the shared code, from the same local.properties the Android build reads.
val generateBuildConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    val propsFile = rootDir.resolve("../local.properties")
    inputs.property("version", appVersion)
    if (propsFile.exists()) inputs.file(propsFile)
    outputs.dir(out)
    doLast {
        val props = Properties()
        if (propsFile.exists()) propsFile.inputStream().use(props::load)
        fun prop(name: String, default: String = "") = (props.getProperty(name) ?: default).replace("\"", "\\\"")
        val file = out.get().file("com/streamflixreborn/streamflix/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.streamflixreborn.streamflix
            |
            |object BuildConfig {
            |    const val DEBUG = false
            |    const val APPLICATION_ID = "com.streamflixreborn.streamflix.desktop"
            |    const val VERSION_NAME = "$appVersion"
            |    const val TMDB_API_KEY = "${prop("TMDB_API_KEY", "9b4396c90d4c866105c05677002f6a15")}"
            |    const val SUBDL_API_KEY = "${prop("SUBDL_API_KEY")}"
            |    const val SUPABASE_URL = "${prop("SUPABASE_URL", "https://adxcjzotlwrrpcllabox.supabase.co")}"
            |    const val SUPABASE_KEY = "${prop("SUPABASE_KEY", "sb_publishable_-joQB1UYwiQs4nlvaL_Pnw_s1-xF6J4")}"
            |    const val RABBITSTREAM_SOURCE_API = "${prop("RABBITSTREAM_SOURCE_API")}"
            |}
            |""".trimMargin()
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(syncSharedSources)
        kotlin.srcDir(generateBuildConfig)
        java.srcDir(syncSharedSources)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Same network stack as the Android app.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("org.mozilla:rhino:1.8.0")
    implementation("org.json:json:20250517")
    // Only the annotations: the models carry Room's @Entity/@Ignore, desktop stores them as JSON.
    implementation("androidx.room:room-common:2.7.0")

    // Images and playback.
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("uk.co.caprica:vlcj:4.12.1")
}

compose.desktop {
    application {
        mainClass = "com.streamflixreborn.streamflix.desktop.MainKt"
        // G1's periodic collection hands unused heap (and the video frame buffers) back after playback.
        jvmArgs += listOf("-Xmx1g", "-XX:G1PeriodicGCInterval=30000", "-Dsun.java2d.uiScale.enabled=true")
        // ./gradlew run -Pautomation=8799 opens the loopback test hook (see Automation.kt).
        (project.findProperty("automation") as String?)?.let { jvmArgs += "-Dstreamflix.automation=$it" }
        (project.findProperty("vlcargs") as String?)?.let { jvmArgs += "-Dstreamflix.vlcargs=$it" }
        if (project.hasProperty("windowed")) jvmArgs += "-Dstreamflix.windowed=true"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "StreamFlix"
            packageVersion = appVersion
            description = "StreamFlix for desktop"
            vendor = "StreamFlix"
            modules("java.net.http", "jdk.httpserver", "java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec")
            // A bundled VLC (put there by the release workflow) lives under resources/<os>/vlc.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                menuGroup = "StreamFlix"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1d3f0e-5a7b-4f7e-9a51-2b9f0b6c1d42"
                iconFile.set(project.file("icons/streamflix.ico"))
            }
            macOS {
                bundleID = "com.streamflixreborn.streamflix.desktop"
                appCategory = "public.app-category.entertainment"
                iconFile.set(project.file("icons/streamflix.icns"))
            }
        }
    }
}

tasks.register<JavaExec>("smoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.streamflixreborn.streamflix.desktop.SmokeKt")
    args = listOfNotNull(project.findProperty("movie")?.toString())
}
