#!/system/bin/sh
# ============================================================
# MiniShizuku 启动脚本（发布版）
# ------------------------------------------------------------
# 用途：为无法使用官方 Shizuku（Android 7 以下）的设备，
#       以 shell 身份启动本应用的系统权限服务。
# 适用：Android 4.4 ~ 6.x 的老手机（无 root 也可用）。
#
# 使用步骤（需要电脑 + USB 数据线，只需一次）：
#   1. 手机开启「开发者选项 → USB 调试」，用数据线连电脑
#   2. 电脑上下载本脚本（mini_shizuku.sh）
#   3. 电脑命令行执行下面两条命令：
#        adb push mini_shizuku.sh /data/local/tmp/
#        adb shell sh /data/local/tmp/mini_shizuku.sh
#   4. 手机回到「桌面设置 → mini_shizuku」，刷新状态，
#      看到「在线」即激活成功
#
# 提示：手机重启后服务会停止，重新执行第 3 步即可。
# ============================================================

# 忽略 SIGHUP，防止 adb shell 断开时杀掉后台服务
trap '' 1

# 杀掉旧的 app_process 服务（Android 4.4 toolbox 无 awk，改用 sh 解析 ps 输出）
ps | grep 'app_process' | grep -v grep | while read -r line; do
    set -- $line
    pid=$2
    [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
done

# 优先使用作为参数传入的包名（或脚本所在路径推导），其次按安装顺序定位
TARGET_PKG="$1"

PACKAGE=""
path=""

if [ -n "$TARGET_PKG" ]; then
    path=$(pm path "$TARGET_PKG")
    path=${path#package:}
    if [ -n "$path" ]; then
        PACKAGE="$TARGET_PKG"
    fi
fi

if [ -z "$PACKAGE" ]; then
    # 未指定或指定包名无效时，尝试从脚本自身所在路径猜测（如 /sdcard/Android/data/<pkg>/files/mini_shizuku.sh）
    script_dir=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
    case "$script_dir" in
        */Android/data/*)
            guessed_pkg=$(echo "$script_dir" | sed -e 's#.*/Android/data/##' -e 's#/.*##')
            if [ -n "$guessed_pkg" ]; then
                path=$(pm path "$guessed_pkg")
                path=${path#package:}
                if [ -n "$path" ]; then
                    PACKAGE="$guessed_pkg"
                fi
            fi
            ;;
    esac
fi

if [ -z "$PACKAGE" ]; then
    # 回退：优先当前运行中的包名，其次按列表寻找
    for p in io.github.cctyl.nokia.debug io.github.cctyl.nokia; do
        path=$(pm path "$p")
        path=${path#package:}
        if [ -n "$path" ]; then
            PACKAGE="$p"
            break
        fi
    done
fi

if [ -z "$PACKAGE" ]; then
    echo "MiniShizuku: 未找到应用，请先安装 KeydroidXLauncher"
    exit 1
fi

# 日志路径选择：优先固定名 /data/local/tmp/minishizuku.log，便于诊断工具定位；
# 若该文件已被其它 uid 创建（root 激活与 adb 激活混用时常见：root 创建后属主为
# root，adb shell 无法 append → "can't create ...: Permission denied"，导致
# app_process 根本不启动、服务永远离线）则退回带 uid 后缀的专属文件。
LOG=/data/local/tmp/minishizuku.log
if ! ( : >> "$LOG" ) 2>/dev/null; then
    uid_suf=$(id -u 2>/dev/null)
    [ -z "$uid_suf" ] && uid_suf=$$
    LOG=/data/local/tmp/minishizuku.$uid_suf.log
fi
# 清空同 uid 的旧日志（其它 uid 的文件已在上一步避开）
: > "$LOG" 2>/dev/null

# 用 -Djava.class.path 指定 APK（Android 4.4 的 CLASSPATH 环境变量方式不可用）
# -Dapp.package 供服务端在 APK 重装后通过 pm path 重新定位，避免旧路径失效
app_process -Djava.class.path="$path" -Dapp.package="$PACKAGE" /system/bin \
    ru.playsoftware.mini_shizuku.server.AdbProcess \
    >> "$LOG" 2>&1 &
echo "MiniShizuku started for $PACKAGE (log: $LOG)"
echo "Author: cctyl"
echo "GitHub: https://github.com/cctyl/keydroidx-launcher"
