# Keep MainActivity and Vosk classes
-keep public class com.example.cutebotvoice.MainActivity { *; }
-keep class org.vosk.** { *; }
-keep class org.vosk.android.** { *; }

# Keep speech models in assets
-keep class com.example.cutebotvoice.R$raw { *; }
-keepclassmembers class com.example.cutebotvoice.R$* { public static <fields>; }
