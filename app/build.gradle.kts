import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
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
    compileSdk = 37
    compileSdkMinor = 2   // Android Components 158 AARs declare minCompileSdk=37, minCompileMinorSdk=2

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
        // Built-in Kotlin (AGP 9) takes jvmTarget from targetCompatibility.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        // Compress the native libraries in the APK and extract them at install (what Fenix does for APK
        // builds): with AGP's default (extractNativeLibs=false) libxul.so alone is 153 MB stored uncompressed
        // and no release build could meet the 95 MB budget (PERF-01). Device footprint = APK + ~204 MB extracted.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // Plain-JVM unit tests (app/src/test) touch android.util.Log only through injected loggers; the
        // default-values mode keeps any stray framework call from throwing "Method ... not mocked".
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform(); it.maxHeapSize = "512m" }
    }

    androidResources {
        // aapt2 asset ignore list: the defaults plus the extension's node tests (assets/extension/test/**).
        // Patterns match file/dir NAMES; "<dir>test" only matches a directory called exactly "test".
        ignoreAssetsPatterns += listOf(
            "!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~",
            "<dir>test",
        )
        // hatch:fixer — PERF-02: Hatch's own UI is English-only; without a filter the AC/androidx/material string
        // tables ship ~150 locale configs (34,857 of 45,710 string entries, ~2.5 MB of a 4.1 MB resources.arsc
        // stored uncompressed). `localeFilters` is the AGP 9 name (`ApplicationAndroidResources.getLocaleFilters`,
        // verified in gradle-api-9.4.1.jar); resource shrinking never drops alternative configs of used IDs.
        localeFilters += listOf("en")
    }

}

// Copies the detection harness (repo-root harness/) into the APK as assets/selfcheck/ so the in-app
// Self-check screen can serve it from LocalHttpServer. No-op (empty asset dir) when harness/ is missing.
// Wired through the Variant API (addGeneratedSourceDirectory): AGP 9 refuses Provider-based srcDirs.
abstract class SyncSelfcheckAssets : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val excludes: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        val target = outputDir.dir("selfcheck").get().asFile
        val src = sourceDir.orNull
        if (src == null) {
            target.deleteRecursively()
            target.mkdirs()
            return
        }
        val patterns: List<String> = excludes.get()
        fs.sync {
            from(src) { exclude(patterns) }
            into(target)
        }
    }
}

val harnessDir = rootProject.layout.projectDirectory.dir("harness")
val syncSelfcheckAssets = tasks.register<SyncSelfcheckAssets>("syncSelfcheckAssets") {
    description = "Syncs harness/ into the app assets as selfcheck/"
    if (harnessDir.asFile.isDirectory) sourceDir.set(harnessDir)
    excludes.set(
        listOf(
            // redteam/*.html pages ship so the phone can open /t/<token>/redteam/<page> (device-only vectors);
            // their README, runner script and service worker stay out.
            "node_modules/**", "build/**", "test/**", "tool/**", "**/.*",
            "redteam/README.md", "redteam/run-all.sh",
            "vendor/entry.js", "vendor/modernizr-config.json",
            "package.json", "package-lock.json", "README.md",
        ),
    )
    outputDir.set(layout.buildDirectory.dir("generated/selfcheck-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncSelfcheckAssets, SyncSelfcheckAssets::outputDir)
    }
}

// geckoview-omni bundles glean-native and declares the capability; app-services (feature-search ->
// remotesettings -> glean) also pulls org.mozilla.telemetry:glean-native. Prefer GeckoView's copy (the same
// rule Fenix uses), otherwise the runtime classpath cannot be resolved.
configurations.all {
    resolutionStrategy.capabilitiesResolution.withCapability("org.mozilla.telemetry:glean-native") {
        val gecko = candidates.find { c ->
            val id = c.id
            id is ModuleComponentIdentifier && id.module.contains("geckoview")
        }
        if (gecko != null) select(gecko)
        because("use GeckoView's bundled glean-native instead of the standalone one")
    }
}

dependencies {
    // Plain-JVM unit tests (app/src/test): AddonUrl, BridgeReportFilter, LocalHttpServer. JUnit Jupiter
    // annotations + kotlin.test assertions (the junit5 variant of kotlin-test is not auto-selected here).
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(project(":persona"))
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // GeckoView 158 (geckoview-omni, transitively) + Android Components 158. Never add geckoview (non-omni).
    implementation(libs.bundles.mozilla.core)
    // hatch:chrome begin — compose-browser-toolbar + tab tray thumbnails/favicons + domain autocomplete (158.0).
    implementation(libs.bundles.mozilla.chrome)
    // hatch:chrome end
    // hatch:credentials begin — device-credential/biometric gate for the Saved logins screen (its AAR manifest
    // declares USE_BIOMETRIC/USE_FINGERPRINT). The logins store and WebAuthn modules ride in the mozilla-core bundle.
    implementation(libs.androidx.biometric)
    // hatch:credentials end
    // hatch:fixer begin — PERF-06: compile-time androidx.work for Configuration.Provider (already on the runtime
    // classpath through service-sync-logins; same version).
    implementation(libs.androidx.work.runtime)
    // hatch:fixer end
}
