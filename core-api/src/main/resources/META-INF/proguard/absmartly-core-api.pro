# Consumer rules for com.absmartly.sdk:core-api 2.x, applied automatically by R8 and ProGuard (Android).

# The relocated Jackson reads the JSON models reflectively: keep its own classes, the generic signatures and
# annotations it inspects, and every model class with its fields and constructors.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.absmartly.sdk.internal.shaded.jackson.** { *; }
-keep class com.absmartly.sdk.internal.json.** { *; }
-dontwarn com.absmartly.sdk.internal.shaded.jackson.**
-dontwarn java.beans.**

# Typed variables recognise Kotlin's empty collections by class name.
-keepnames class kotlin.collections.EmptyList
-keepnames class kotlin.collections.EmptySet
-keepnames class kotlin.collections.EmptyMap

# The default java.net.http transport is loaded by name only when no Transport is supplied: keep that entry point
# for minified JVM apps. Android always supplies a transport and has no java.net.http, so silence the references.
-keep class com.absmartly.sdk.JdkHttpTransport {
	public static com.absmartly.sdk.JdkHttpTransport create();
}
-dontwarn com.absmartly.sdk.JdkHttpTransport
-dontwarn com.absmartly.sdk.JdkHttpTransport$*
-dontwarn java.net.http.**
