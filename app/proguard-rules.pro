# 自用 App，release 暂不开启混淆；保留规则以备后续启用
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn okhttp3.**
-dontwarn okio.**
