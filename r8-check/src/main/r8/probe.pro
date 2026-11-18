# The app's own rules only: the SDK's rules come from core-api.jar's META-INF/proguard.
-keep class com.absmartly.r8check.Probe { public static void main(java.lang.String[]); }
# The probe's own class read with variable(key, Class, default), as the README tells apps to keep theirs.
-keep class com.absmartly.r8check.Probe$Banner { *; }
-dontwarn org.slf4j.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
