-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }

# mini_shizuku 服务端：必须留在主 dex（classes.dex）。
# 原因：Android 4.4 的 Dalvik 通过 `app_process -Djava.class.path=<apk>` 加载时
# 只读取主 classes.dex，不支持 multidex 的 secondary dex（classes2/classes3）。
# 若服务端类（AdbProcess 等）被 R8 拆分到 classes2/3，则 app_process 报
# "could not find class ru.playsoftware.mini_shizuku.server.AdbProcess" 并 abort，
# 表现为 mini_shizuku 服务离线（Android 5+ 的 ART 无此问题）。
-keep class ru.playsoftware.mini_shizuku.server.** { *; }
-keep class ru.playsoftware.mini_shizuku.** { *; }