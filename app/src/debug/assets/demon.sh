#!/system/bin/sh
# MiniShizuku 启动脚本（DEBUG 版）
# 包名写死为 debug 包名，用于测试。
# 用法：adb push demon.sh /data/local/tmp/ && adb shell sh /data/local/tmp/demon.sh
PACKAGE="io.github.cctyl.nokia.debug"

# 忽略 SIGHUP，防止 adb shell 断开时杀掉后台服务
trap '' 1

# 杀掉旧的 app_process 服务（Android 4.4 toolbox 无 awk，改用 sh 解析 ps 输出）
ps | grep 'app_process' | grep -v grep | while read -r line; do
    set -- $line
    pid=$2
    [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
done

# 通过包名定位 APK 路径
path=$(pm path "$PACKAGE")
path=${path#package:}
if [ -z "$path" ]; then
    echo "MiniShizuku: package $PACKAGE not found"
    exit 1
fi

# 用 -Djava.class.path 指定 APK（Android 4.4 的 CLASSPATH 环境变量方式不可用）
app_process -Djava.class.path="$path" /system/bin \
    ru.playsoftware.mini_shizuku.server.AdbProcess \
    >> /data/local/tmp/minishizuku.log 2>&1 &
echo "MiniShizuku started for $PACKAGE"
