# Keep WebRTC JNI classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep Ktor classes
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
