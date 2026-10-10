import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.opensdr.sdrcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-g0")
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild.cmake.arguments += "-DOPENSDR_DIAGNOSTIC_CAPTURE=ON"
            ndk {
                debugSymbolLevel = "NONE"
            }
        }

        release {
            externalNativeBuild.cmake.arguments += "-DOPENSDR_DIAGNOSTIC_CAPTURE=OFF"
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
            ndk {
                debugSymbolLevel = "NONE"
            }
        }

        create("diagnostic") {
            initWith(getByName("release"))
            externalNativeBuild.cmake.arguments.clear()
            externalNativeBuild.cmake.arguments += "-DOPENSDR_DIAGNOSTIC_CAPTURE=ON"
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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
    testImplementation("junit:junit:4.13.2")
}
