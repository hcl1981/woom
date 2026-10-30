import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}

kotlin {
    jvmToolchain(21)
    // Protocol, data model and view model, shared with the Android app.
    sourceSets["main"].kotlin.srcDir("../shared/src")
}

// -Pwindows selects the Windows graphics libraries, so build-windows.sh can package Woom for Windows on Linux.
val forWindows = providers.gradleProperty("windows").isPresent

dependencies {
    implementation(if (forWindows) compose.desktop.windows_x64 else compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("dev.nucleusframework:composenativetray:2.1.7")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
}

compose.resources {
    packageOfResClass = "app.woom"
}

compose.desktop {
    application {
        mainClass = "app.woom.MainKt"
        buildTypes.release.proguard {
            configurationFiles.from(project.file("rules.pro"))
            optimize.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "Woom"
            packageVersion = "1.0.7"
            description = "Remote control for WiiM streamers"
            vendor = "Woom"
            modules("java.instrument", "java.naming", "jdk.crypto.ec", "jdk.unsupported")
            linux {
                // Debian package names must be lower case; the menu shows packageName above.
                debPackageVersion = packageVersion
                packageName = "woom"
                iconFile.set(project.file("icons/woom.png"))
                shortcut = true
                menuGroup = "AudioVideo"
            }
            windows {
                // Icon of Woom.exe in the app image built by `createReleaseDistributable`.
                // The Windows installer itself is built from that app image by windows/woom.iss (Inno Setup).
                iconFile.set(project.file("icons/woom.ico"))
                console = false
            }
        }
    }
}
