# Keep rules every app using the engine needs (applied through consumerProguardFiles).
# JNI entry points are kept by the default rules (native methods).

# MediaPipe (skin segmentation): its native code looks up Java classes and
# protobuf messages by name, so R8 must not rename or strip them.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
# Flogger (used by MediaPipe) finds its caller by class name on the stack.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
