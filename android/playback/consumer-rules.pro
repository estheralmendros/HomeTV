# libVLC: JNI calls back into these classes (events, VLCObject, Dialog, AWindow…) by name. Required: the
# libvlc-all 3.7.2 AAR ships no consumer rules (no proguard.txt); a release APK that strips one crashes in LibVLC.<init>.
-keep class org.videolan.libvlc.** { *; }

# CuePlacementPlayer (ExoCuePlacement.kt) implements Player.Listener with a java.lang.reflect.Proxy, which R8
# cannot see: keep the interface whole so no listener method is removed or devirtualized away.
-keep interface androidx.media3.common.Player$Listener { *; }
