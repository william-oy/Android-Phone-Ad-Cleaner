# keep 序列化数据模型
-keep class com.adcleaner.app.rules.** { *; }
-keep class com.adcleaner.app.model.** { *; }
-keepclassmembers class com.adcleaner.app.** {
    @kotlinx.serialization.Serializable <fields>;
}
