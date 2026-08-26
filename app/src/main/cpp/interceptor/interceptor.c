
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <string.h>
#include <dirent.h>
#include <pthread.h>
#include <time.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "Interceptor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x)-1)/BITS_PER_LONG)+1)
#define test_bit(bit, array) ((array[bit/BITS_PER_LONG] >> (bit%BITS_PER_LONG)) & 1)

// 原键桌面应用包名候选（release 无后缀 / debug 带 .debug）。
// 回桌面注入时先探测已安装的包；前台判断也用它。
#define PKG_NOKIA_RELEASE "io.github.cctyl.nokia"
#define PKG_NOKIA_DEBUG   "io.github.cctyl.nokia.debug"
#define NOKIA_ACTIVITY    "ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity"

// 注入防抖间隔（毫秒）：仅对亮屏态动作（锁屏/回桌面）生效，防止快速连按导致
// 注入被系统忽略。息屏态（唤醒）不设防抖。500ms 足够系统完成状态切换 + 状态线程更新。
#define INJECT_DEBOUNCE_MS 500
// 状态轮询间隔（微秒）：500ms 轮询 dumpsys 更新缓存。
#define STATE_POLL_US      (500 * 1000)
// 长按判定阈值（毫秒）：按下超过此时间仍未抬起视为长按，转发给系统。
#define LONG_PRESS_THRESHOLD_MS 500
// 系统电源键长按超时（毫秒）：系统检测长按的时间，注入 DOWN 后至少保持此时长
// 才能让系统弹出开关机菜单。
#define SYSTEM_LONG_PRESS_MS 500

static pthread_t interceptor_thread;
static pthread_t state_thread;
static volatile int is_running = 0;
static int uinput_fd = -1;
static int power_key_fd = -1;

// ---- 状态缓存（由状态线程 500ms 轮询 dumpsys 更新，按键时直接读） ----
static volatile int screen_awake = 1;        // 屏幕是否亮（默认亮，避免首次按键误锁屏）
static volatile int front_is_nokia = 0;      // 前台窗口是否为本应用
static volatile int front_is_keyguard = 0;   // 前台窗口是否为锁屏界面（Keyguard）
static char front_package[128] = "";         // 当前前台包名（日志用）
static char nokia_package[128] = "";         // 探测到的有效本应用包名（缓存，go home 用）
static long long last_inject_ms = 0;         // 上次注入时间（防抖）

// ---- 页面状态（由 App 通过 JNI 上报，区分诺基亚主界面 vs 子页面） ----
// 1 = 主界面（待机屏 NokiaDesktopFragment），0 = 子页面（功能表/设置/百宝箱等）
static volatile int page_is_main = 1;

// ---- 长按追踪 ----
static volatile int power_is_down = 0;          // 电源键当前是否处于按下状态
static volatile long long power_down_time_ms = 0; // 按下时刻
static volatile int long_press_injected = 0;     // 是否已通过 uinput 注入长按 DOWN
static volatile long long long_press_inject_time_ms = 0; // 注入 DOWN 的时刻
static pthread_t long_press_thread;
static volatile int long_press_thread_created = 0; // 长按监控线程是否已创建（防 join 未初始化句柄）
static volatile int grab_active = 0;            // EVIOCGRAB 是否处于抓取状态（0=已释放，系统原生处理）

// ---- 工具 ----

static long long now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void emit(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    write(fd, &ie, sizeof(ie));
}

// 模拟按键：发送 DOWN、SYN、UP、SYN
void simulate_key(int fd, int code) {
    emit(fd, EV_KEY, code, 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    emit(fd, EV_KEY, code, 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

// 查询设备是否声明了指定按键
static int device_has_key(int fd, int key_code) {
    unsigned long key_bitmask[NBITS(KEY_MAX)];
    memset(key_bitmask, 0, sizeof(key_bitmask));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bitmask)), key_bitmask) < 0) {
        return 0;
    }
    return test_bit(key_code, key_bitmask);
}

// 读取设备名（ioctl EVIOCGNAME），失败返回空串
static void device_get_name(int fd, char *name, size_t max_len) {
    name[0] = '\0';
    if (ioctl(fd, EVIOCGNAME((int)max_len - 1), name) < 0) {
        name[0] = '\0';
    }
}

// 判断设备是否为「物理按键设备」：含目标键 + 不含 FN 功能键 + 非 uinput 虚拟设备。
// 背景：部分手机多个设备声明同一按键（如 madev 声明 KEY_POWER 的 FN 功能键设备，
// 以及本方案创建的 virtual-nokia-keypad 回放设备），readdir 顺序可能先遇到它们，
// 导致 grab 错设备（物理电源键在 gpio-keys，却抓到 madev）。此处按特征优先物理设备。
static int is_physical_key_device(int fd, int key_code) {
    if (!device_has_key(fd, key_code)) {
        return 0;
    }
    // FN 功能键设备（KEY_FN_F1..）非物理电源键设备
    if (device_has_key(fd, KEY_FN_F1) || device_has_key(fd, KEY_FN_F2)) {
        return 0;
    }
    // 跳过本方案创建的 uinput 回放设备
    char name[128];
    device_get_name(fd, name, sizeof(name));
    if (strstr(name, "virtual") != NULL || strstr(name, "uinput") != NULL
            || strstr(name, "Virtual") != NULL) {
        return 0;
    }
    return 1;
}

// 查找包含指定按键的输入设备：优先物理按键设备，退化取第一个含目标键的设备。
int find_device_with_key(int key_code, char *dev_path, size_t max_len) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;

    struct dirent *ent;
    char fallback[256];
    int fallback_found = 0;

    while ((ent = readdir(dir)) != NULL) {
        if (strncmp(ent->d_name, "event", 5) != 0) continue;

        char path[256];
        snprintf(path, sizeof(path), "/dev/input/%s", ent->d_name);

        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;

        if (device_has_key(fd, key_code)) {
            if (!fallback_found) {
                strncpy(fallback, path, sizeof(fallback) - 1);
                fallback[sizeof(fallback) - 1] = '\0';
                fallback_found = 1;
            }
            if (is_physical_key_device(fd, key_code)) {
                strncpy(dev_path, path, max_len);
                close(fd);
                closedir(dir);
                return 0; // 命中物理按键设备
            }
        }
        close(fd);
    }

    closedir(dir);

    if (fallback_found) {
        strncpy(dev_path, fallback, max_len);
        return 0; // 退化：任意含目标键的设备
    }
    return -1;
}

// 创建 uinput 设备：把原设备支持的所有按键都注册上（含 POWER、HOME、音量等），
// 以便未拦截的事件原样回放，避免 grab 导致其它按键失效。
int setup_uinput(int evdev_fd) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("Failed to open uinput");
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) {
        LOGE("Failed UI_SET_EVBIT");
        close(fd);
        return -1;
    }

    // 枚举原设备支持的 EV_KEY 按键并全部注册到 uinput
    unsigned long key_bitmask[NBITS(KEY_MAX)];
    memset(key_bitmask, 0, sizeof(key_bitmask));
    if (ioctl(evdev_fd, EVIOCGBIT(EV_KEY, sizeof(key_bitmask)), key_bitmask) >= 0) {
        int code;
        for (code = 0; code < KEY_MAX; code++) {
            if (test_bit(code, key_bitmask)) {
                if (ioctl(fd, UI_SET_KEYBIT, code) < 0) {
                    LOGI("UI_SET_KEYBIT failed for code %d (ignored)", code);
                }
            }
        }
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "virtual-nokia-keypad");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor  = 0x1;
    uidev.id.product = 0x1;
    uidev.id.version = 1;

    if (write(fd, &uidev, sizeof(uidev)) < 0) {
        LOGE("Failed to write uinput_user_dev");
        close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("Failed to create uinput device");
        close(fd);
        return -1;
    }

    return fd;
}

// ---- 方案1：决策状态机（power 键语义） ----

// 执行 shell 命令并丢弃输出（注入通道；命令很快，同步执行）。
// 返回 pclose 的退出码（0=成功，-1=popen 失败）。
static int run_cmd(const char *cmd) {
    FILE *p = popen(cmd, "r");
    if (!p) {
        LOGE("popen failed: %s", cmd);
        return -1;
    }
    char buf[256];
    while (fgets(buf, sizeof(buf), p) != NULL) { /* 读空输出，避免管道阻塞 */ }
    return pclose(p);
}

// 执行命令并捕获输出到 out（截断）。返回 pclose 退出码。
static int run_cmd_output(const char *cmd, char *out, size_t out_sz) {
    if (out_sz <= 0) return -1;
    FILE *p = popen(cmd, "r");
    if (!p) {
        LOGE("popen failed: %s", cmd);
        return -1;
    }
    size_t total = 0;
    while (total < out_sz - 1) {
        size_t n = fread(out + total, 1, out_sz - 1 - total, p);
        if (n <= 0) break;
        total += n;
    }
    out[total] = '\0';
    return pclose(p);
}

// 在文本中查找 key= 后的值（值到行尾或空白/逗号），供 mWakefulness= 等使用。
static void extract_after(const char *text, const char *key, char *out, size_t out_sz) {
    out[0] = '\0';
    const char *p = strstr(text, key);
    if (!p) return;
    p += strlen(key);
    while (*p == ' ' || *p == '\t') p++;
    size_t i = 0;
    while (*p && *p != '\n' && *p != '\r' && *p != ' ' && *p != '\t'
            && *p != ',' && *p != '}' && i < out_sz - 1) {
        out[i++] = *p++;
    }
    out[i] = '\0';
}

// 从 dumpsys window 输出提取前台窗口包名：
// 格式：mCurrentFocus=Window{<hash> u0 <package>/<activity>}-[Surface(name=...)/@...]
// 只在 Window{...} 内部找 '/'（忽略 } 后面的 Surface 部分中的 '/'），
// 取 '/' 前最后一个空白后的 token 作为包名。
static void extract_front_package(const char *text, char *out, size_t out_sz) {
    out[0] = '\0';
    const char *p = strstr(text, "mCurrentFocus=Window{");
    if (!p) return;
    p += strlen("mCurrentFocus=Window{");
    // 找到 Window{...} 的闭合 }，只在其内部查找 /
    const char *close = strchr(p, '}');
    if (!close) return;
    const char *slash = strchr(p, '/');
    if (!slash || slash >= close) return; // Window{...} 内无 /（如 NotificationShade）
    const char *start = p;
    const char *scan = p;
    while (scan < slash) {
        if (*scan == ' ' || *scan == '\t') start = scan + 1;
        scan++;
    }
    size_t len = (size_t)(slash - start);
    if (len <= 0 || len >= out_sz) return;
    memcpy(out, start, len);
    out[len] = '\0';
}

// 从 dumpsys window 输出提取前台 activity 全名（'/' 之后、'}' 之前）：
// 格式：mCurrentFocus=Window{<hash> u0 <package>/<activity>}-[Surface(name=...)/@...]
// 与 extract_front_package 配对：包名判归属，activity 名区分桌面与 jar 界面。
static void extract_front_activity(const char *text, char *out, size_t out_sz) {
    out[0] = '\0';
    const char *p = strstr(text, "mCurrentFocus=Window{");
    if (!p) return;
    p += strlen("mCurrentFocus=Window{");
    const char *close = strchr(p, '}');
    if (!close) return;
    const char *slash = strchr(p, '/');
    if (!slash || slash >= close) return;
    const char *start = slash + 1;
    size_t len = (size_t)(close - start);
    if (len <= 0 || len >= out_sz) return;
    memcpy(out, start, len);
    out[len] = '\0';
}

// 判断包名是否为本应用（release / debug）。
static int is_nokia_package(const char *pkg) {
    if (!pkg || !pkg[0]) return 0;
    return strcmp(pkg, PKG_NOKIA_RELEASE) == 0 || strcmp(pkg, PKG_NOKIA_DEBUG) == 0;
}

// 从 dumpsys activity activities 输出提取 resumed activity 包名：
// topResumedActivity=ActivityRecord{<hash> u0 <package>/<activity> ...}（Android 10+）
// mFocusedActivity=ActivityRecord{<hash> u0 <package>/<activity> ...}（旧版本）
// 提取 ActivityRecord{ 之后、'/' 之前最后一个空白后的 token。
static void extract_resumed_package(const char *text, char *out, size_t out_sz) {
    out[0] = '\0';
    const char *p = strstr(text, "ActivityRecord{");
    if (!p) return;
    p += strlen("ActivityRecord{");
    const char *slash = strchr(p, '/');
    if (!slash) return;
    const char *start = p;
    const char *scan = p;
    while (scan < slash) {
        if (*scan == ' ' || *scan == '\t') start = scan + 1;
        scan++;
    }
    size_t len = (size_t)(slash - start);
    if (len <= 0 || len >= out_sz) return;
    memcpy(out, start, len);
    out[len] = '\0';
}

// 判断 activity 全名是否为 jar 界面（MicroActivity，debug/release 类路径相同）。
// jar 界面与桌面同包名、不同进程不同 Activity：只看包名会把它误判成桌面，
// 导致 jar 内按挂机键走 C 态「锁屏」而非 A 态「回桌面」（bug 361）。
static int is_midlet_activity(const char *activity) {
    static const char suffix[] = "MicroActivity";
    if (!activity || !activity[0]) return 0;
    size_t alen = strlen(activity);
    size_t slen = sizeof(suffix) - 1;
    return alen >= slen && strcmp(activity + alen - slen, suffix) == 0;
}

// 更新屏幕状态缓存。
// 优先读 /sys/class/leds/lcd-backlight/brightness（文件读取 ~1ms），
// 不可用时回退 dumpsys power（~300ms）。
static void update_screen_state() {
    int awake = -1;
    // 快速路径：读背光亮度
    FILE *f = fopen("/sys/class/leds/lcd-backlight/brightness", "r");
    if (f) {
        char buf[16];
        if (fgets(buf, sizeof(buf), f)) {
            int val = atoi(buf);
            awake = (val > 0) ? 1 : 0;
        }
        fclose(f);
    }
    // 回退：dumpsys power
    if (awake < 0) {
        char output[256];
        if (run_cmd_output("dumpsys power 2>/dev/null | grep mWakefulness",
                           output, sizeof(output)) >= 0) {
            char val[32];
            extract_after(output, "mWakefulness=", val, sizeof(val));
            if (val[0]) {
                awake = (strcmp(val, "Awake") == 0) ? 1 : 0;
            }
        }
    }
    if (awake < 0) return; // 两种方式都失败，保持当前值
    if (awake != screen_awake) {
        LOGI("state: screen %s -> %s",
             screen_awake ? "awake" : "asleep", awake ? "awake" : "asleep");
        screen_awake = awake;
    }
}

// 更新前台窗口缓存：dumpsys window | grep mCurrentFocus。
// 注意：必须用 "dumpsys window"（不带 "windows"），实测部分 ROM 下
// "dumpsys window windows" 不返回 mCurrentFocus 字段。
// 锁屏界面检测：锁屏时 mCurrentFocus 通常为 NotificationShade / StatusBar / Keyguard
// （无 / 分隔符，包名解析失败），或包名为 com.android.systemui。
static void update_front_window() {
    char output[4096];
    if (run_cmd_output("dumpsys window 2>/dev/null | grep mCurrentFocus",
                       output, sizeof(output)) < 0) {
        LOGW("state: dumpsys window failed");
        return;
    }
    char pkg[128];
    extract_front_package(output, pkg, sizeof(pkg));
    if (!pkg[0]) {
        // mCurrentFocus 无 / 分隔符（如 NotificationShade / StatusBar），
        // 检查是否为锁屏/通知栏窗口
        int isKg = (strstr(output, "NotificationShade") != NULL
                    || strstr(output, "StatusBar") != NULL
                    || strstr(output, "keyguard") != NULL
                    || strstr(output, "Keyguard") != NULL) ? 1 : 0;
        if (isKg != front_is_keyguard) {
            LOGI("state: keyguard=%d (no pkg, raw=%s)", isKg, output);
            front_is_keyguard = isKg;
        }
        // 解析失败时不改变 front_is_nokia / front_package（保守保持上一次有效值）
        return;
    }
    int isNokia = is_nokia_package(pkg);
    // jar 界面（MicroActivity）归入「非诺基亚应用」（A 态：按挂机键回桌面=挂机），
    // 行为符合《挂机键行为定义.md》界面分层；page_is_main 此时是桌面上报的陈旧值，不参与。
    char activity[256];
    extract_front_activity(output, activity, sizeof(activity));
    if (isNokia && is_midlet_activity(activity)) {
        isNokia = 0;
        LOGI("state: front is MicroActivity (jar) -> treat as non-nokia");
    }
    // SystemUI 包名承载锁屏界面（Keyguard）
    int isKeyguard = (strcmp(pkg, "com.android.systemui") == 0) ? 1 : 0;
    if (isNokia != front_is_nokia || strcmp(pkg, front_package) != 0
            || isKeyguard != front_is_keyguard) {
        LOGI("state: front window pkg=%s isNokia=%d isKeyguard=%d (was pkg=%s isNokia=%d isKeyguard=%d)",
             pkg, isNokia, isKeyguard, front_package, front_is_nokia, front_is_keyguard);
        strncpy(front_package, pkg, sizeof(front_package) - 1);
        front_package[sizeof(front_package) - 1] = '\0';
        front_is_nokia = isNokia;
        front_is_keyguard = isKeyguard;
    }
}

// 状态轮询线程：
// - 屏幕状态：每 50ms 通过背光文件快速检测（~1ms），用于 grab 管理
// - 前台窗口：仅亮屏时每 2000ms 通过 dumpsys 检测（~300ms），减少 CPU 占用
// - grab 管理：息屏→释放（系统原生处理唤醒），亮屏→重新 grab
static void* state_thread_run(void* arg) {
    LOGI("state: polling thread started");
    long long last_window_check = 0;
    while (is_running) {
        // 快速屏幕状态检测（文件读取，~1ms）
        update_screen_state();

        // grab 管理
        if (!screen_awake && grab_active && power_key_fd >= 0) {
            ioctl(power_key_fd, EVIOCGRAB, 0);
            grab_active = 0;
            LOGI("state: grab released (screen asleep)");
        } else if (screen_awake && !grab_active && power_key_fd >= 0) {
            if (ioctl(power_key_fd, EVIOCGRAB, 1) == 0) {
                grab_active = 1;
                LOGI("state: re-grabbed (screen awake)");
            }
        }

        // 前台窗口检测：仅亮屏时，每 2 秒一次（dumpsys 较慢）
        if (screen_awake) {
            long long now = now_ms();
            if (now - last_window_check >= 2000) {
                update_front_window();
                last_window_check = now;
            }
        }

        usleep(50 * 1000); // 50ms 固定轮询（屏幕状态检测极快）
    }
    LOGI("state: polling thread stopped");
    return NULL;
}

// ---- 异步注入（分离线程执行 shell 命令，不阻塞事件循环） ----

static void* inject_thread_fn(void* arg) {
    char* cmd = (char*)arg;
    int rc = run_cmd(cmd);
    LOGI("async inject: rc=%d cmd=%s", rc, cmd);
    free(cmd);
    return NULL;
}

static void inject_async(const char* cmd) {
    char* copy = strdup(cmd);
    if (!copy) return;
    pthread_t t;
    if (pthread_create(&t, NULL, inject_thread_fn, copy) == 0) {
        pthread_detach(t);
    } else {
        run_cmd(copy);
        free(copy);
    }
}

// 探测有效包名（缓存，仅首次探测），供 inject_go_home / inject_lock 共用。
static int ensure_nokia_package() {
    if (nokia_package[0] != '\0') return 1;
    char out[256];
    if (run_cmd_output("pm path " PKG_NOKIA_RELEASE " 2>/dev/null", out, sizeof(out)) >= 0
            && strstr(out, "package:")) {
        strcpy(nokia_package, PKG_NOKIA_RELEASE);
    } else if (run_cmd_output("pm path " PKG_NOKIA_DEBUG " 2>/dev/null", out, sizeof(out)) >= 0
            && strstr(out, "package:")) {
        strcpy(nokia_package, PKG_NOKIA_DEBUG);
    } else {
        LOGE("cannot find nokia package (release/debug)");
        return 0;
    }
    LOGI("resolved package=%s", nokia_package);
    return 1;
}

// 通过 socket 直连 App 的 NokiaLockServer 发送指令（~2ms，无进程创建开销）。
// 返回 0=成功，-1=失败（App 未运行等）。
static int send_via_socket(const char* msg) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return -1;
    struct timeval tv = {0, 100000}; // 100ms 超时
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(10501);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(sock);
        return -1;
    }
    send(sock, msg, strlen(msg), 0);
    close(sock);
    return 0;
}

// 注入：回原键桌面主界面。
// 优先 socket 直连（~2ms，App 内处理，若为默认桌面发送隐式 HOME Intent），失败时 fallback 到隐式/显式 am start。
static void inject_go_home() {
    if (send_via_socket("HOME\n") == 0) {
        LOGI("go home: sent via socket (fast path)");
    } else {
        LOGW("go home: socket failed, fallback to am start");
        char cmd[400];
        // 隐式 HOME 回桌面，走系统原生转场动画
        snprintf(cmd, sizeof(cmd), "am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        inject_async(cmd);
    }
}

// 注入：锁屏。
// 优先 socket 直连（~2ms），失败时 fallback 到 am broadcast（异步）。
// 锁屏后释放 grab，让系统原生处理后续唤醒。
static void inject_lock() {
    if (send_via_socket("LOCK\n") == 0) {
        LOGI("lock: sent via socket (fast path)");
    } else {
        LOGW("lock: socket failed, fallback to am broadcast");
        if (!ensure_nokia_package()) return;
        char cmd[400];
        snprintf(cmd, sizeof(cmd),
                 "am broadcast -a ru.playsoftware.j2meloader.nokia.LOCK_SCREEN "
                 "-n %s/ru.playsoftware.j2meloader.nokia.NokiaLockReceiver",
                 nokia_package);
        inject_async(cmd);
    }

    // 释放 grab：锁屏后屏幕将息屏，系统需原生处理唤醒按键
    if (power_key_fd >= 0 && grab_active) {
        ioctl(power_key_fd, EVIOCGRAB, 0);
        grab_active = 0;
        LOGI("lock: grab released");
    }
}

// 即时复核前台是否真为桌面主界面（即将走 C 态锁屏前调用，bug 365）。
// 两层校验（任一发现前台非本应用 → 返回 0，降级 go_home）：
//   1) fresh mCurrentFocus：轮询缓存最长 2000ms 陈旧，刚切走应用就按键会误判；
//   2) resumed activity（dumpsys activity）：相机/InCallUI 等浮层窗口可能让
//      mCurrentFocus 仍指向桌面（浮层非 focusable），resumed activity 反映真实前台。
// 复核输出解析失败时保守返回 1（不阻断既有锁屏行为）。
static int verify_front_really_nokia() {
    char output[4096];
    // 1) fresh mCurrentFocus
    if (run_cmd_output("dumpsys window 2>/dev/null | grep mCurrentFocus",
                       output, sizeof(output)) >= 0 && strstr(output, "mCurrentFocus")) {
        char pkg[128];
        char act[256];
        extract_front_package(output, pkg, sizeof(pkg));
        extract_front_activity(output, act, sizeof(act));
        if (pkg[0]) {
            if (!is_nokia_package(pkg)) {
                LOGI("verify: fresh focus pkg=%s 非本应用 -> 降级回桌面", pkg);
                return 0;
            }
            if (is_midlet_activity(act)) {
                LOGI("verify: fresh focus 是 jar 界面 -> 降级回桌面");
                return 0;
            }
        }
    }
    // 2) resumed activity 交叉校验（浮层窗口场景）
    if (run_cmd_output("dumpsys activity activities 2>/dev/null"
                       " | grep -E 'topResumedActivity|mFocusedActivity'",
                       output, sizeof(output)) >= 0) {
        char pkg[128];
        extract_resumed_package(output, pkg, sizeof(pkg));
        if (pkg[0] && !is_nokia_package(pkg)) {
            LOGI("verify: resumed pkg=%s 非本应用(浮层场景) -> 降级回桌面", pkg);
            return 0;
        }
    }
    return 1;
}

// 决策状态机：短按 power 键时执行（5 态：A/B/C/E/F）。
static void handle_short_press() {
    long long now = now_ms();
    if (screen_awake && last_inject_ms != 0 && now - last_inject_ms < INJECT_DEBOUNCE_MS) {
        LOGI("power: consumed (debounce, %lldms since last inject)", now - last_inject_ms);
        return;
    }
    LOGI("power: short press -> screen=%s frontIsNokia=%d isKeyguard=%d pageIsMain=%d frontPkg='%s'",
         screen_awake ? "awake" : "asleep", front_is_nokia, front_is_keyguard, page_is_main, front_package);

    if (!screen_awake) {
        // E 息屏 → 唤醒：grab 应已被状态线程释放，系统原生处理。
        // 此分支为竞态兜底（grab 尚未释放时用户已按键）：手动释放 grab。
        LOGI("power: decision=wake [E->F] (screen asleep, fallback grab release)");
        if (power_key_fd >= 0 && grab_active) {
            ioctl(power_key_fd, EVIOCGRAB, 0);
            grab_active = 0;
            LOGW("power: E state fallback, grab released manually");
        }
        // 用户需再按一次让系统看到完整 DOWN→UP（竞态窗口极小，通常 grab 已释放）
    } else if (front_is_keyguard) {
        // F 亮屏·锁屏界面 → Device Admin 锁屏（熄屏）
        LOGI("power: decision=lock [F->E] (keyguard showing)");
        inject_lock();
        last_inject_ms = now_ms();
    } else if (front_is_nokia && page_is_main) {
        // C 亮屏·原键桌面主界面 → Device Admin 锁屏（熄屏）。
        // 锁屏前即时复核前台（bug 365：相机/拨号浮层 + 2s 轮询陈旧会误判为 C 态），
        // 复核发现前台非本应用则降级 go_home。
        if (!verify_front_really_nokia()) {
            LOGI("power: decision=go_home [C 降级->A/B] (复核发现前台非桌面)");
            inject_go_home();
            last_inject_ms = now_ms();
        } else {
            LOGI("power: decision=lock [C->E] (nokia main page)");
            inject_lock();
            last_inject_ms = now_ms();
        }
    } else {
        // A 亮屏·非诺基亚应用 → 回原键桌面主界面
        // B 亮屏·原键桌面其他界面 → 回原键桌面主界面（不锁屏）
        LOGI("power: decision=go_home [A/B->C] (front=%s, nokia=%d, main=%d)",
             front_package, front_is_nokia, page_is_main);
        inject_go_home();
        last_inject_ms = now_ms();
    }
}

// ---- 长按转发 ----

// 长按监控线程：DOWN 后启动，若按住超过阈值仍未抬起，通过 uinput 注入 KEY_POWER DOWN
// 给系统，让系统自行检测长按并弹出开关机菜单。
static void* long_press_watch(void* arg) {
    while (is_running && power_is_down && !long_press_injected) {
        long long held = now_ms() - power_down_time_ms;
        if (held >= LONG_PRESS_THRESHOLD_MS) {
            long_press_injected = 1;
            long_press_inject_time_ms = now_ms();
            LOGI("power: long press detected (%lldms held), forwarding to system", held);
            if (uinput_fd >= 0) {
                // 方案1：通过 uinput 注入 KEY_POWER DOWN，系统开始长按计时
                emit(uinput_fd, EV_KEY, KEY_POWER, 1);
                emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
                LOGI("power: long press DOWN injected via uinput");
            } else {
                // 方案2（纯消费）：释放 grab 让系统看到后续物理事件，
                // 并 best-effort 尝试 input keyevent --longpress（部分 ROM 有效）
                LOGW("power: long press in consume-only mode, releasing grab");
                if (power_key_fd >= 0) {
                    ioctl(power_key_fd, EVIOCGRAB, 0);
                    grab_active = 0;
                }
                run_cmd("input keyevent --longpress 26");
            }
            break;
        }
        usleep(20 * 1000); // 每 20ms 检查一次
    }
    return NULL;
}

// 长按 UP 处理：完成注入序列或恢复 grab。
static void handle_long_press_up() {
    if (uinput_fd >= 0) {
        // 方案1：确保系统看到足够长的按住时间（≥ SYSTEM_LONG_PRESS_MS），
        // 否则系统会把短按当休眠处理。若用户提前松手，延迟补足 UP 注入。
        long long held_since_inject = now_ms() - long_press_inject_time_ms;
        if (held_since_inject < SYSTEM_LONG_PRESS_MS) {
            long long wait = SYSTEM_LONG_PRESS_MS - held_since_inject;
            LOGI("power: long press UP deferred %lldms (held_only_%lldms_since_inject)",
                 wait, held_since_inject);
            long long deadline = now_ms() + wait;
            while (now_ms() < deadline) {
                if (!is_running) break;
                usleep(20 * 1000);
            }
        }
        emit(uinput_fd, EV_KEY, KEY_POWER, 0);
        emit(uinput_fd, EV_SYN, SYN_REPORT, 0);
        LOGI("power: long press UP injected via uinput (total_held=%lldms)",
             now_ms() - power_down_time_ms);
    } else {
        // 方案2：重新 grab 恢复拦截
        if (power_key_fd >= 0 && !grab_active) {
            ioctl(power_key_fd, EVIOCGRAB, 1);
            grab_active = 1;
            LOGI("power: re-grabbed after long press (consume-only)");
        }
    }
}

// ---- 主拦截线程 ----

void* interceptor_run(void* arg) {
    char dev_path[256];

    if (find_device_with_key(KEY_POWER, dev_path, sizeof(dev_path)) < 0) {
        LOGE("Could not find input device with KEY_POWER");
        is_running = 0;
        return NULL;
    }

    LOGI("Found KEY_POWER on device: %s", dev_path);

    // 注意：必须用 O_RDONLY 打开。Android 5.0+ SELinux enforcing 下，shell 域对
    // input_device 类型只允许读（写会被 avc 拒绝，O_RDWR 打开直接失败）。
    // EVIOCGRAB 独占抓取与 read 事件流仅需读权限即可正常工作（实测验证）。
    power_key_fd = open(dev_path, O_RDONLY);
    if (power_key_fd < 0) {
        LOGE("Failed to open %s", dev_path);
        is_running = 0;
        return NULL;
    }

    // 拦截该设备（独占抓取，系统 InputReader 收不到该设备事件）
    if (ioctl(power_key_fd, EVIOCGRAB, 1) < 0) {
        LOGE("Failed to grab device, need root?");
        close(power_key_fd);
        power_key_fd = -1;
        is_running = 0;
        return NULL;
    }
    grab_active = 1;

    // 创建 uinput 虚拟设备用于回放。某些设备上 shell 无 /dev/uinput 写权限，
    // 打不开时退化为"纯消费模式"：非 power 键一并丢弃。
    uinput_fd = setup_uinput(power_key_fd);
    if (uinput_fd < 0) {
        LOGW("uinput unavailable, running in consume-only mode (方案2)");
    } else {
        LOGI("uinput replay device created (方案1)");
    }

    LOGI("Interceptor started successfully");

    // 启动状态轮询线程，并立即刷新一次缓存，避免首次按键状态不准
    is_running = 1;
    pthread_create(&state_thread, NULL, state_thread_run, NULL);
    update_screen_state();
    update_front_window();

    struct input_event ev;
    while (is_running) {
        ssize_t ret = read(power_key_fd, &ev, sizeof(ev));
        if (ret < (ssize_t)sizeof(ev)) {
            // 仅 read 返回 -1 时 errno 才有意义
            if (ret < 0 && errno == EINTR) continue;
            // 设备断开 / 被关闭 / 出错 → 退出循环
            break;
        }

        // grab 已释放（息屏态）：系统原生处理所有按键，事件循环仅读取并丢弃
        if (!grab_active) {
            continue;
        }

        if (ev.type == EV_KEY && ev.code == KEY_POWER) {
            if (ev.value == 1) {
                // DOWN：记录时间，启动长按监控线程，暂不处理（等判定短按/长按）
                power_is_down = 1;
                long_press_injected = 0;
                long_press_thread_created = 0;
                power_down_time_ms = now_ms();
                LOGI("power: DOWN (starting long-press watcher, threshold=%dms)",
                     LONG_PRESS_THRESHOLD_MS);
                if (pthread_create(&long_press_thread, NULL, long_press_watch, NULL) == 0) {
                    long_press_thread_created = 1;
                }
            } else if (ev.value == 2) {
                // REPEAT：消费丢弃，长按由监控线程处理
                // 不打日志（REPEAT 事件频繁）
            } else {
                // UP (value==0)：等待监控线程退出，按短按/长按分别处理
                power_is_down = 0;
                if (long_press_thread_created) {
                    pthread_join(long_press_thread, NULL);
                    long_press_thread_created = 0;
                }
                if (long_press_injected) {
                    // 长按已转发：完成 uinput UP 注入（或恢复 grab）
                    handle_long_press_up();
                } else {
                    // 短按：执行决策状态机
                    LOGI("power: UP short press (%lldms held)",
                         now_ms() - power_down_time_ms);
                    handle_short_press();
                }
            }
        } else if (ev.type == EV_KEY || ev.type == EV_SYN) {
            // 有 uinput 时非 power 键事件原样回放，保证其它按键行为不变；
            // 无 uinput（纯消费模式）则一并丢弃。
            if (uinput_fd >= 0) {
                emit(uinput_fd, ev.type, ev.code, ev.value);
            }
        }
    }

    LOGI("Interceptor stopped");

    // 停止状态线程（is_running 已被 stopInterceptor 置 0，sleep 醒来后退出）
    pthread_join(state_thread, NULL);

    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }

    if (power_key_fd >= 0) {
        ioctl(power_key_fd, EVIOCGRAB, 0); // Release grab
        close(power_key_fd);
        power_key_fd = -1;
    }

    is_running = 0;
    return NULL;
}

JNIEXPORT void JNICALL
Java_ru_playsoftware_mini_1shizuku_server_InterceptorNative_startInterceptor(JNIEnv *env, jclass clazz) {
    if (is_running) return;

    is_running = 1;
    if (pthread_create(&interceptor_thread, NULL, interceptor_run, NULL) != 0) {
        LOGE("Failed to create interceptor thread");
        is_running = 0;
    }
}

JNIEXPORT void JNICALL
Java_ru_playsoftware_mini_1shizuku_server_InterceptorNative_stopInterceptor(JNIEnv *env, jclass clazz) {
    if (!is_running) return;

    is_running = 0;
    // 关闭 fd 会使 read 立即返回错误从而退出循环
    if (power_key_fd >= 0) {
        close(power_key_fd);
        power_key_fd = -1;
    }

    pthread_join(interceptor_thread, NULL);
}

JNIEXPORT void JNICALL
Java_ru_playsoftware_mini_1shizuku_server_InterceptorNative_setInterceptEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {
    // 预留：动态开关拦截。当前 start/stop 已满足需求，先留空。
}

// 由 App 通过 TCP→服务端 JNI 调用，上报当前页面状态。
// state: 1 = 原键桌面主界面（待机屏），0 = 子页面（功能表/设置/百宝箱等）
JNIEXPORT void JNICALL
Java_ru_playsoftware_mini_1shizuku_server_InterceptorNative_nativeSetPageState(JNIEnv *env, jclass clazz, jint state) {
    int s = state ? 1 : 0;
    if (s != page_is_main) {
        LOGI("page state: %s -> %s", page_is_main ? "main" : "sub", s ? "main" : "sub");
        page_is_main = s;
    }
}
