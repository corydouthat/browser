import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Build number = git commit count, so every commit is an installable update (fitness-app convention).
val gitCommitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.get()

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

fun prop(name: String, default: String): String = (project.findProperty(name) as String?) ?: default

android {
    namespace = "app.hatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hatch.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = versionProps.getProperty("versionName")
        buildConfigField("String", "GIT_SHA", "\"${prop("gitSha", "local")}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${prop("gitBranch", "local")}\"")
        buildConfigField("String", "BUILD_TIME", "\"${prop("buildTime", "")}\"")
    }

    // Sideload-only: ship arm64 only, no universal APK (GeckoView is ~70-90 MB per ABI).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    val keyProps = rootProject.file("key.properties")
    signingConfigs {
        if (keyProps.exists()) {
            create("release") {
                val p = Properties().apply { keyProps.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 may OOM under the VPS heap cap; if so set isMinifyEnabled=false (APK grows a few MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keyProps.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(project(":persona"))
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Gecko + Android Components are added after the toolchain gate build (see docs/roadmap.md M0).
}
