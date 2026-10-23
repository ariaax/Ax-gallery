# Ax Gallery: shrink unused code and resources, but do NOT rename anything (keeps reflection and
# crash reports working, avoids obfuscation surprises).
-dontobfuscate
-dontwarn **

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# All of the app's own code (small) - never let R8 strip or merge it.
-keep class com.iris.gallery.** { *; }

# Reflection on ExifInterface internals (Unicode EXIF comments) needs these members to stay.
-keep class androidx.exifinterface.media.ExifInterface { *; }
-keep class androidx.exifinterface.media.ExifInterface$* { *; }

# Native AVIF decoder (JNI)
-keep class org.aomedia.avif.android.** { *; }

# Enums are stored by name in preferences
-keepclassmembers enum * { *; }
