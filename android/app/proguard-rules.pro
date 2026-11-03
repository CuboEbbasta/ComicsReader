# junrar usa slf4j solo per i log
-dontwarn org.slf4j.**
-keep class com.github.junrar.** { *; }
# Ponte JavaScript del lettore EPUB
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn javax.annotation.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.apache.commons.**
