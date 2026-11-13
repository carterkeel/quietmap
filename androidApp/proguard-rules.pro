# R8 rules for the "staging" (optimised) build.

# secp256k1: native code calls back into these classes by name.
-keep class fr.acinq.secp256k1.** { *; }

# MapLibre: JNI bindings are looked up by name. maplibre-compose's native layer loads through
# JavaCPP, which finds its classes and libraries by reflection.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-keep class org.bytedeco.javacpp.** { *; }
-dontwarn org.bytedeco.**

# kotlinx.serialization: keep generated serializers for our models.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class app.quietmap.** { *** Companion; }
-keepclasseswithmembers class app.quietmap.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.quietmap.**$$serializer { *; }

# Ktor / OkHttp optional dependencies.
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# QR scanner
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
