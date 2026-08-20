# JavaMail resolves providers reflectively.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**

# kotlinx.serialization keeps generated serializers on the classes it annotates.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.paisa.core.** {
    *** Companion;
}
-keepclasseswithmembers class app.paisa.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
