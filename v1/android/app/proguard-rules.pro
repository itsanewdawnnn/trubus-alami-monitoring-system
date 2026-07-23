# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- TAMS-specific keep rules ---
# minifyEnabled was previously false, so these were never actually applied.
# Retrofit, OkHttp, and Moshi's codegen artifact each ship their own
# consumer-rules.pro (auto-merged by AGP), which normally covers the well
# known trouble spots for this exact stack. The rules below are a defensive
# extra layer specifically for this app's own classes, since minification is
# being turned on without the ability to compile/run a release build in this
# environment to verify -- please still build and smoke-test (login,
# start/stop tracking, admin map, history) a release APK before shipping.

# Preserve generic type signatures, annotations, and exceptions -- Retrofit
# and Moshi both inspect these via reflection at runtime to build adapters
# and match Call<T>/Response<T> generics.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault, Exceptions

# Moshi: keep every @JsonClass(generateAdapter = true) model and its
# compile-time generated <Type>JsonAdapter untouched (fields/order matter for
# the generated adapter's direct field access).
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.trubus.tams.data.model.** { *; }
-keep class com.trubus.tams.data.model.**JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Retrofit service interface -- keep its method signatures (incl. the hidden
# Continuation parameter Kotlin coroutines add to suspend functions).
-keep interface com.trubus.tams.data.api.ApiService { *; }
