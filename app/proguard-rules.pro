# GeckoView and Android Components ship consumer rules. Keep our serializable model.
-keep class app.hatch.persona.** { *; }
-dontwarn org.mozilla.**

# hatch:credentials begin
# Mozilla Application Services (uniffi Kotlin bindings over JNA: logins, sync15, db-crypto, search,
# remotesettings). The rule Fenix 158 uses (fenix/app/proguard-rules.pro: `-keep class mozilla.appservices.** { *; }`);
# Fenix additionally builds with -dontobfuscate, Hatch does not, so the keep is load-bearing here: uniffi's
# `UniffiLib` interface method names must match the exported C symbols and the RustBuffer/ForeignBytes
# structures are read by JNA through reflection. The JNA rules themselves (`-keep class com.sun.jna.* { *; }`,
# `-keep class * extends com.sun.jna.*`) come from the consumer proguard.txt inside the appservices and glean
# AARs (verified in logins-158.0.aar, init_rust_components-158.0.aar, glean-70.2.0.aar), so they are not repeated.
-keep class mozilla.appservices.** { *; }
# GeckoView reads geckoview-config.yaml into this class by field name (Fenix keeps it for the same reason).
-keep class org.mozilla.gecko.util.DebugConfig { *; }
# ...through SnakeYAML, whose TypeDescription static initialiser calls `TypeDescription.class.getPackage().getName()`
# (TypeDescription.java:43). R8 flattens renamed classes into the unnamed package, where ART's getPackage() is
# null, so every release build crashed in GeckoRuntime.create (build 20/21 on the Pixel, 2026-10-20). Keeping the
# package names (classes may still be renamed and shrunk) is enough; tool/check_release_mapping.sh guards it.
-keeppackagenames org.yaml.snakeyaml.**
# hatch:credentials end
