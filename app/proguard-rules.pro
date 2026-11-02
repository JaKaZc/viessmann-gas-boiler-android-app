# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Tink uses protobuf-lite reflection
-keep class com.google.crypto.tink.proto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# Glance creates ActionCallback classes by reflection (actionRunCallback<T>()).
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
