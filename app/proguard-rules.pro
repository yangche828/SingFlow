# ---------------------------------------------------------------------------
# libbox（sing-box 1.14+）JNI 桥接：全部通过类名字符串反射调用，禁止混淆。
# ---------------------------------------------------------------------------
-dontwarn io.nekohasekai.libbox.**
-keep class io.nekohasekai.libbox.** { *; }
-keep class libbox.** { *; }
-keep class go.** { *; }
-keep class com.singflow.android.core.engine.** { *; }

# ---------------------------------------------------------------------------
# Kotlin Serialization：保留生成的 serializer 与 Companion。
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.singflow.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.singflow.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.singflow.android.**$$serializer { *; }

# ---------------------------------------------------------------------------
# Room：实体、DAO 与数据库实现由生成代码直接访问。
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep interface com.singflow.android.core.database.*Dao { *; }

# ---------------------------------------------------------------------------
# 发布包移除调试日志调用，减少 DEX 体积与运行期字符串拷贝。
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# 合并短方法、放宽访问修饰符，进一步压缩 DEX。
-allowaccessmodification
