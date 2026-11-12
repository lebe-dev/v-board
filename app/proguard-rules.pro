# R8 defaults suffice for the keyboard itself; only the JNI boundary below needs keeping.

# sherpa-onnx: the native library calls back into these classes and reads their fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# fbjni (the JNI layer of ExecuTorch) ships no consumer rules. Its native code builds HybridData,
# reads its fields and throws CppException subclasses by name; without these rules R8 empties
# HybridData and drops the exceptions, so any native error aborts the process.
-keep class com.facebook.jni.** { *; }
-keep @com.facebook.jni.annotations.DoNotStrip class * { *; }
-keepclassmembers class * {
    @com.facebook.jni.annotations.DoNotStrip *;
}
# Annotations only, absent at run time.
-dontwarn javax.annotation.**
