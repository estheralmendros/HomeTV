# kotlinx.serialization: keep generated serializers and companion lookups.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.hometv.tv.**$$serializer { *; }
-keepclassmembers class com.hometv.tv.** { *** Companion; }
-keepclasseswithmembers class com.hometv.tv.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit: keep the API interface and its generic signatures (suspend functions).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.hometv.tv.core.api.HomeTvApi { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# libVLC: JNI calls back into these classes (events, VLCObject, Dialog, AWindow…) by name. Required: the
# libvlc-all 3.7.2 AAR ships no consumer rules (no proguard.txt); a release APK that strips one crashes in LibVLC.<init>.
-keep class org.videolan.libvlc.** { *; }
