# Отчёты собираются на чистой JVM (модуль report-core), поэтому правила минимальны.
-keep class ru.jbosint.osint.core.** { *; }
-dontwarn org.jetbrains.annotations.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
