# R8 rules for the release build (Pass 18 — minification was off before this; see CHANGELOG).
# Room, security-crypto, and CameraX all ship their own consumer proguard rules bundled in their
# AARs, which R8 picks up automatically — the rules below are the additional, conservative keeps
# for this app's own code, kept minimal on purpose rather than a large "just in case" list.

# Room entities and DAOs: Room's generated code (AppDatabase_Impl, *_Impl DAO classes) references
# entity fields directly by name for SQL column mapping. Keeping the data classes and their members
# intact is the safe default for a first minification pass — much cheaper to keep a well-understood
# small set of classes than to debug a renamed-field query mismatch on a real device.
-keep class org.offlinemesh.app.data.*Entity { *; }
-keep class org.offlinemesh.app.data.AppDatabase { *; }
-keep class org.offlinemesh.app.data.AppDatabase$Companion { *; }

# The application-level wire format (MeshFrameCodec) and crypto (CryptoUtils) are correctness- and
# security-critical and gain nothing from shrinking (they're already small, hand-written binary
# encode/decode, not reflection-heavy) — keeping them unobfuscated also keeps a future crash stack
# trace from this specific code readable without needing the mapping file.
-keep class org.offlinemesh.app.ble.MeshFrameCodec { *; }
-keep class org.offlinemesh.app.crypto.CryptoUtils { *; }

# Standard Kotlin metadata + coroutines keep rules — losing these doesn't crash immediately but
# breaks reflection-based Kotlin tooling and coroutine debugging in ways that are easy to miss until
# something specific needs them.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn kotlinx.coroutines.**

# security-crypto's Tink dependency references errorprone/JSR-305 annotation classes defensively
# (CanIgnoreReturnValue, CheckReturnValue, Immutable, RestrictedApi, Nullable, GuardedBy) that are
# compile-time-only and never actually invoked at runtime — a well-known, standard gotcha when
# minifying anything that pulls in Tink. Confirmed by R8's own missing_rules.txt output, not a guess.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Phase 4 (sender identity): tink-android is now a direct dependency and actually exercised at
# runtime (crypto/SenderIdentity.kt), not just transitively present and unused. Tink's optional
# Conscrypt fast-path (com.google.crypto.tink.subtle.Ed25519Sign/Verify probe for a Conscrypt
# provider and fall back to Tink's own pure-Java implementation if it's absent) references
# org.conscrypt.* classes that aren't on this app's compile classpath (Android's own bundled
# Conscrypt lives in a different, non-public namespace) — harmless at runtime (the fallback is
# exactly what this app's minSdk 26 needs; see SenderIdentity's class doc), but R8 needs telling
# not to fail full-mode analysis over classes it can't find.
-dontwarn org.conscrypt.**
-keep class com.google.crypto.tink.subtle.Ed25519Sign { *; }
-keep class com.google.crypto.tink.subtle.Ed25519Sign$KeyPair { *; }
-keep class com.google.crypto.tink.subtle.Ed25519Verify { *; }

# Strip every android.util.Log call out of release builds entirely (R8 removes the call site, not
# just silences output) — a security-scan finding: this app logs peer BLE addresses, connection
# diagnostics, and warning messages (RelayResponder, MeshGattClient, the screens' defensive catch
# blocks) purely for on-device debugging, none of it meant to survive into a release APK where
# it's readable via `adb logcat` with USB debugging authorized. Debug builds are untouched (no
# minification there), so on-device debugging via logcat still works exactly as before.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int wtf(...);
}
