import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

base {
    archivesName.set("opensdr")
}

android {
    namespace = "org.opensdr.amhost"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rafaldot.opensdr"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"
    }

    buildTypes {
        debug {
            ndk {
                debugSymbolLevel = "NONE"
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "NONE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("diagnostic") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets {
        getByName("debug").java.srcDir("src/recording/java")
        getByName("diagnostic").java.srcDir("src/recording/java")
        getByName("release").java.srcDir("src/production/java")
        getByName("testDebug").java.srcDir("src/recordingTest/java")
        getByName("testDiagnostic").java.srcDir("src/recordingTest/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

// APK-only capture builds: guard the complete task graph, including abbreviated
// CLI tasks and bundle tasks invoked by Android Studio or another Gradle task.
gradle.taskGraph.whenReady {
    check(allTasks.none {
        it.project.path == project.path &&
            it.name.matches(Regex("(?:bundle(?:Debug|Diagnostic)|(?:package|sign|finalize)(?:Debug|Diagnostic)Bundle)", RegexOption.IGNORE_CASE))
    }) { "Diagnostic recording is APK-only. Use :rtlsdrhost:bundleRelease for Google Play." }
}

// Inspect the actual outputs too; a future dependency/source-set change must not
// silently ship capture code even if the UI remains hidden.
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    doLast {
        val directory = if (name == "bundleRelease") "outputs/bundle/release" else "outputs/apk/release"
        val extension = if (name == "bundleRelease") "aab" else "apk"
        val artifacts = fileTree(layout.buildDirectory.dir(directory)).matching { include("*.$extension") }.files
        check(artifacts.isNotEmpty()) { "No release artifact found for recording exclusion check" }
        val forbidden = listOf(
            "Java_org_opensdr_sdrcore_DiagnosticCapture_", "DiagnosticCapture;",
            "Diagnostic recording", "input-%03d", "Cannot start native recording buffer",
            "Clear recording folder", "OpenSdrCaptureCleanup",
        )
        artifacts.forEach { artifact ->
            ZipFile(artifact).use { zip ->
                val entries = zip.entries().asSequence().filter {
                    it.name.endsWith(".dex") || it.name.endsWith(".so")
                }.toList()
                check(entries.any { it.name.endsWith("libopensdr_rtlsdr.so") }) { "Missing receiver library" }
                entries.forEach { entry ->
                    val content = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    check(forbidden.none { content.contains(it) }) {
                        "Diagnostic recording leaked into ${artifact.name}: ${entry.name}"
                    }
                }
            }
            logger.lifecycle("Verified: ${artifact.name} contains no diagnostic recorder")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":radioapi"))
    implementation(project(":opensdrcore"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
