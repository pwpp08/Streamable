# StreamFlix R8 rules. Correctness over size: scrapers parse with Gson/reflection into
# (often nested) classes, so whole app packages that do this are kept with their members.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes *Annotation*,AnnotationDefault
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleAnnotations
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------- App packages using reflection (Gson, Retrofit, Room, backup/sync JSON) -------------
-keep class com.streamflixreborn.streamflix.models.** { *; }
-keep class com.streamflixreborn.streamflix.utils.TMDb3 { *; }
-keep class com.streamflixreborn.streamflix.utils.TMDb3$** { *; }
-keep class com.streamflixreborn.streamflix.utils.** { *; }
-keep class com.streamflixreborn.streamflix.providers.** { *; }
-keep class com.streamflixreborn.streamflix.extractors.** { *; }
-keep class com.streamflixreborn.streamflix.database.** { *; }
-keep class com.streamflixreborn.streamflix.backup.** { *; }
-keep class com.streamflixreborn.streamflix.sync.** { *; }
# Home-row layout JSON is (de)serialised by Gson through private holder classes.
-keep class com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig { *; }
-keep class com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig$** { *; }
-keep class com.streamflixreborn.streamflix.BuildConfig { *; }

# Anything annotated for Gson anywhere in the app
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.streamflixreborn.streamflix.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Enums are looked up by name (Gson, nav args, preferences)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# Navigation safe-args: Parcelable/Serializable argument types and generated Args/Directions
-keepnames class * implements android.os.Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class com.streamflixreborn.streamflix.**Args { *; }
-keep class com.streamflixreborn.streamflix.**Args$* { *; }
-keep class com.streamflixreborn.streamflix.**Directions { *; }
-keep class com.streamflixreborn.streamflix.**Directions$* { *; }

# WebView JS bridges
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# ---------------- Gson ----------------
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# ---------------- Retrofit / OkHttp / Okio ----------------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# retrofit-jsoup-converter
-keep class com.tanasi.retrofit_jsoup.** { *; }
-dontwarn com.tanasi.retrofit_jsoup.**

# ---------------- Jsoup ----------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**

# ---------------- Rhino ----------------
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# ---------------- Conscrypt ----------------
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**

# ---------------- Room ----------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * implements androidx.room.migration.AutoMigrationSpec { *; }
-dontwarn androidx.room.paging.**

# ---------------- kotlinx.serialization ----------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---------------- Supabase / Ktor ----------------
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.github.jan.supabase.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**

# ---------------- Kotlin coroutines ----------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---------------- Media3 / ExoPlayer ----------------
# Extensions (DASH/HLS/OkHttp datasource) are loaded reflectively by DefaultMediaSourceFactory.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------------- Glide ----------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep class com.bumptech.glide.GeneratedAppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }
-dontwarn com.bumptech.glide.**

# AndroidSVG
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# ---------------- AndroidX misc ----------------
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.preference.Preference { <init>(...); }
-keep class * extends androidx.fragment.app.Fragment { <init>(); }
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# ---------------- libtorrent4j (JNI/SWIG: native code looks up Java names) ----------------
-keep class org.libtorrent4j.** { *; }
-keep interface org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
