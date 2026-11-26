// AGP 9 has built-in Kotlin: org.jetbrains.kotlin.android is NOT applied anywhere. kotlin.jvm (for :persona)
// puts KGP 2.4.20 on the build classpath, which AGP then uses instead of its bundled 2.2.x.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.paparazzi) apply false
}
