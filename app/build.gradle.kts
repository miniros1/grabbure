import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

val minirosSourceDir: String = run {
    val properties = Properties()
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        local.inputStream().use { properties.load(it) }
    }
    val raw = properties.getProperty("miniros.dir")?.trim().orEmpty()
    if (raw.isEmpty()) {
        throw GradleException("Set miniros.dir in local.properties to the miniros source directory")
    }
    val source = if (File(raw).isAbsolute) file(raw) else rootProject.file(raw)
    if (!source.resolve("CMakeLists.txt").isFile) {
        throw GradleException("miniros.dir is not a miniros source tree: $raw")
    }
    source.absolutePath
}

android {
    namespace = "com.miniros1.grabbure"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.miniros1.grabbure"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                targets += "grabbure"
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DMINIROS_SOURCE_DIR=$minirosSourceDir",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.3"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
