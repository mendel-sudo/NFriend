# Add project specific ProGuard rules here.

# Keep data classes for Gson serialization
-keepclassmembers class com.nfriend.app.data.** { *; }
-keepclassmembers class com.nfriend.app.network.RelayClient$* { *; }
-keepclassmembers class com.nfriend.app.qr.QRScanner$* { *; }
-keepclassmembers class com.nfriend.app.crypto.E2EEEngine$* { *; }

# Keep Gson TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Lazysodium / JNA (native library)
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ML Kit
-keep class com.google.mlkit.** { *; }
