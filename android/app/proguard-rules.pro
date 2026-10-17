# kotlinx.serialization: conservar los serializadores generados de los modelos de la API.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.edgarguitarist.audiopc.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.edgarguitarist.audiopc.data.**$$serializer { *; }
