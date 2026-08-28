# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
-keep class javax.** { *; }
-keep class com.kddi.** { *; }
-keep class com.siemens.mp.** { *; }
-keep class com.samsung.util.** { *; }
-keep class com.sonyericsson.accelerometer.** { *; }
-keep class com.sprintpcs.media.** { *; }
-keep class com.mascotcapsule.micro3d.v3.** { *; }
-keep class com.jblend.graphics.j3d.* { *; }
-keep class com.motorola.** { *; }
-keep class com.nokia.mid.** { *; }
-keep class com.sun.midp.midlet.** { *; }
-keep class com.vodafone.** { *; }
-keep class mmpp.media.** { *; }
-keep class org.microemu.** { *; }
-keep class ru.playsoftware.j2meloader.util.SparseIntArrayAdapter { *; }
# mini_shizuku 服务端被 app_process 按类名反射加载（app 主代码不引用），
# release R8 默认会当不可达代码删除/改名 -> app_process 启动即
# ClassNotFoundException、服务永远不上线。必须保名保成员。
# （库已用 consumerProguardFiles 导出，这里再加一道直保兜底。）
-keep class ru.playsoftware.mini_shizuku.server.** { *; }
# Keep the BuildConfig
-keep class ru.playsoftware.j2meloader.BuildConfig { *; }

-keep class androidx.appcompat.widget.SearchView { *; }
-keep class com.arthenica.mobileffmpeg.** { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class ru.playsoftware.j2meloader.crashes.models.* { *; }
