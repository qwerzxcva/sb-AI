# gomobile/gobind 生成的类必须保留，否则 JNI 注册失败
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose 已在 AGP 8 中默认处理，无需额外规则
