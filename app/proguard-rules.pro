# Keep runtime class and method names so exported diagnostics remain actionable
# in minified release APKs instead of reporting a0.e2.o-style frames.
-keep class com.techwithkaushik.cppide.integration.linuxruntime.** { *; }
-keep class com.techwithkaushik.cppide.feature.terminal.DeveloperLog { *; }

# Preserve source/line metadata for any frames that are still obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
