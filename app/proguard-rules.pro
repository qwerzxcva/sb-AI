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

# snakeyaml 的 java.beans 反射路径在 Android 不存在（R8 会报缺失）；
# 我们只用到 Map/List 加载，不需要 Bean 反序列化，忽略这些引用即可
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.introspector.**
# snakeyaml 内部类需保留以正常解析
-keep class org.yaml.snakeyaml.** { *; }
