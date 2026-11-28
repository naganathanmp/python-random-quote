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

# On-device AI (MediaPipe LLM inference). Its settings are protobuf-lite messages whose fields are
# found by name at run time, and the native engine calls back into Java by name: keep them whole.
# (v2.0.1 release lost LlmModelSettings.modelPath_ → "The AI model could not be loaded".)
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.mediapipe.**

# Digital signatures (BouncyCastle looks up algorithm classes by name).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
