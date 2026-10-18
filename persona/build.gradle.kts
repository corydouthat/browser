// Pure Kotlin/JVM: the persona model, presets and the persona -> Gecko pref mapping.
// No Android dependencies so it stays portable (future iOS/KMP) and unit-testable without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// DO NOT use jvmToolchain(17): this box has only JDK 21 (Android Studio JBR) and no toolchain
// download repository, so a toolchain request fails the whole build. Run on 21, target 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        // personas/identities.json + personas/regions.json (repo-root source of truth) are exposed as
        // classpath resources so Presets loads "/identities.json" and "/regions.json".
        resources.srcDir("../personas")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "512m"
    // Where the golden test reads personas/golden/*.prefs.json from.
    systemProperty("hatch.personasDir", rootProject.file("personas").absolutePath)
    // `./gradlew :persona:test -Dhatch.regenGoldens=true` rewrites the golden files instead of comparing.
    systemProperty("hatch.regenGoldens", providers.systemProperty("hatch.regenGoldens").getOrElse("false"))
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
