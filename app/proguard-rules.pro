# Keep the APK patching libraries intact (they use reflection-free code,
# but their class names are referenced from resource files).
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.android.apksig.**
