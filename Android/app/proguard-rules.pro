# IFATC Companion — R8 / ProGuard configuration.

# kotlinx.serialization keeps generated serializers reachable through reflection.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    static **$* INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Every @Serializable model in :core is decoded from bundled JSON or a network
# payload, so its members must survive shrinking.
-keep,includedescriptorclasses class com.h3consultingpartners.ifatccompanion.core.**$$serializer { *; }
-keepclassmembers class com.h3consultingpartners.ifatccompanion.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio ship their own rules but these silence platform-only references.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Play Billing.
-keep class com.android.billingclient.api.** { *; }

# Coroutines debug agent is not shipped.
-dontwarn kotlinx.coroutines.debug.**
