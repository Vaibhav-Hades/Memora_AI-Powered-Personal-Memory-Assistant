# OkHttp and org.json ship their own rules; the app uses no reflection-based serialization.

# ML Kit text recognition loads its bundled models and internal classes by name; shrinking or renaming
# them made on-device indexing fail with a null-field error in the release build.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-dontwarn com.google.mlkit.**

# LiteRT-LM's native library (liblitertlm_jni.so) finds Java classes, fields and methods BY NAME through JNI
# (for example SamplerConfig and ThinkingConfig getters while creating a conversation). R8 renaming or removing
# them makes the lookup return null and ART aborts the whole process with
# "JNI DETECTED ERROR IN APPLICATION: mid == null" - which no try/catch can intercept. Keep the whole package.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# WorkManager builds its Room database by reflection (WorkDatabase_Impl.<init>). R8 removed that constructor,
# which crashed the app at startup in release builds.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
