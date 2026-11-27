# Add project specific ProGuard rules here.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.foldmedia.app.model.** { *; }
-keep class top.yukonga.miuix.** { *; }

-dontwarn com.yausername.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
