# -*- coding: utf-8 -*-
"""
通知中心测试脚本：用 adb 向设备快速制造测试通知。

用法：
    python post_test_notifications.py                     # 默认发 4 条（标题/正文各不相同）
    python post_test_notifications.py -s <serial>         # 指定设备（多设备时用）
    python post_test_notifications.py stress 20           # 连发 20 条（测列表刷新节流/滚动）
    python post_test_notifications.py update 10           # 同 tag 连发 10 次（模拟下载进度，测去重与原地更新）
    python post_test_notifications.py count               # 查看当前活跃的 shell 测试通知条数

说明：
- 通知来自 com.android.shell，会被系统自动归组；桌面通知中心已过滤"分组摘要"，
  所以每条 post 都是列表里独立的一条。
- 同 tag 重复 post 是"更新"而不是新增，用来模拟下载进度类高频通知。
- 清除通知没有对应的 shell 命令，请在通知中心里测（这正是被测功能）。
"""

import subprocess
import sys
import argparse
import re


def adb(serial, *args):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        err = (result.stderr or result.stdout or "").strip()
        print("[ERR] %s\n%s" % (" ".join(cmd), err))
        sys.exit(1)
    return result.stdout


def post(serial, title, tag, text):
    adb(serial, "shell", "cmd", "notification", "post",
        "-t", title, tag, text)
    print("[POST] %s | %s | %s" % (title, tag, text))


def default_batch(serial):
    samples = [
        ("GitHub",  "ntest_github", "[keydroidx-launcher] New pull request #42"),
        ("微信",    "ntest_wechat", "张三：中午一起吃饭吗？"),
        ("10086",   "ntest_sms",    "您本月已使用流量 3.2GB，剩余 6.8GB"),
        ("日历",    "ntest_cal",    "下午 3:00 项目周会"),
        ("邮件",    "ntest_mail",   "服务器告警：磁盘使用率 91%"),
    ]
    for title, tag, text in samples:
        post(serial, title, tag, text)
    print("完成：共 %d 条" % len(samples))


def stress(serial, count):
    for i in range(1, count + 1):
        post(serial, "压测 %d" % i, "ntest_stress_%d" % i, "这是第 %d 条压测通知" % i)
    print("完成：连发 %d 条" % count)


def update(serial, count):
    # 同 tag 反复 post = 更新同一条通知（模拟下载进度）
    for pct in range(0, count * 10, 10):
        post(serial, "下载中", "ntest_download", "已下载 %d%%" % min(pct + 10, 100))
    print("完成：同 tag 更新 %d 次（应只占列表 1 条）" % count)


def count(serial):
    out = adb(serial, "shell", "dumpsys", "notification", "--noredact")
    records = re.findall(r"pkg=com\.android\.shell user=UserHandle\{0\} id=\d+ tag=(\S+)", out)
    summaries = [t for t in records if t == "ranker_group"]
    print("shell 通知记录共 %d 条（含分组摘要 %d 条，摘要已被通知中心过滤，不用管）"
          % (len(records), len(summaries)))
    for t in sorted(set(records)):
        print("  tag=%s x%d" % (t, records.count(t)))


def main():
    parser = argparse.ArgumentParser(description="通知中心测试：快速制造系统通知")
    parser.add_argument("-s", "--serial", default=None, help="adb 设备序列号（单设备可省略）")
    parser.add_argument("mode", nargs="?", default="default",
                        choices=["default", "stress", "update", "count"],
                        help="default=发5条样例; stress N=连发N条; update N=同tag更新N次; count=查看当前数量")
    parser.add_argument("number", nargs="?", type=int, default=20, help="stress/update 模式的次数")
    args = parser.parse_args()

    serial = args.serial
    if not serial:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        devices = [l.split()[0] for l in out.splitlines()
                   if l.strip() and not l.startswith("List of") and "device" in l]
        if len(devices) > 1:
            print("检测到多台设备，请用 -s 指定：%s" % ", ".join(devices))
            sys.exit(1)
        serial = devices[0] if devices else None
        if serial:
            print("使用设备: %s" % serial)

    if args.mode == "default":
        default_batch(serial)
    elif args.mode == "stress":
        stress(serial, max(1, args.number))
    elif args.mode == "update":
        update(serial, max(1, min(args.number, 50)))
    elif args.mode == "count":
        count(serial)


if __name__ == "__main__":
    main()
