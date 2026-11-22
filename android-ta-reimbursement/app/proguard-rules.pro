# Keep class/method names readable (no obfuscation) – only unused code is removed.
-dontobfuscate

# App data model & services (Room entities/DAOs, enums stored by name in the database).
-keep class com.tareimbursement.app.data.** { *; }
-keep class com.tareimbursement.app.core.** { *; }
-keepclassmembers enum * { *; }

# ML Kit / Play services component discovery.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
