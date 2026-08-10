
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
#include <sys/ioctl.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "Interceptor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x)-1)/BITS_PER_LONG)+1)
#define test_bit(bit, array) ((array[bit/BITS_PER_LONG] >> (bit%BITS_PER_LONG)) & 1)

static pthread_t interceptor_thread;
static volatile int is_running = 0;
static int uinput_fd = -1;
static int power_key_fd = -1;

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

    // 创建 uinput 虚拟设备用于回放。某些设备上 shell 无 /dev/uinput 写权限，
    // 打不开时退化为"纯消费模式"：拦截 power 键并丢弃，其它按键一并丢弃
    // （可接受，因为该设备一般只有 power 与音量键）。
    uinput_fd = setup_uinput(power_key_fd);
    if (uinput_fd < 0) {
        LOGW("uinput unavailable, running in consume-only mode");
    } else {
        LOGI("uinput replay device created");
    }

    LOGI("Interceptor started successfully");

    struct input_event ev;
    while (is_running) {
        ssize_t ret = read(power_key_fd, &ev, sizeof(ev));
        if (ret < (ssize_t)sizeof(ev)) {
            // 仅 read 返回 -1 时 errno 才有意义
            if (ret < 0 && errno == EINTR) continue;
            // 设备断开 / 被关闭 / 出错 → 退出循环
            break;
        }

        if (ev.type == EV_KEY && ev.code == KEY_POWER) {
            // 拦截 power 键：消费掉（按下/抬起都丢弃），系统收不到 power 事件。
            if (ev.value == 1) {
                LOGI("Power key consumed");
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
