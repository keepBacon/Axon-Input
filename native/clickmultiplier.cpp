#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
volatile sig_atomic_t g_stop = 0;
int g_uinput = -1;
int g_code = -1;
const char* g_kind = "keyboard";

constexpr int kMaxBurstQueue = 64;
constexpr int kMaxExtraPerBurst = 9;
constexpr int kMaxDelayMs = 1000;
constexpr useconds_t kPressHoldUs = 6000;

struct Burst {
    int remaining;
    int delayMs;
};

Burst g_bursts[kMaxBurstQueue]{};
int g_head = 0;
int g_tail = 0;
int g_size = 0;
long long g_nextTapAtMs = 0;

void onSignal(int) { g_stop = 1; }

long long monotonicMs() {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

bool writeEvent(__u16 type, __u16 code, __s32 value) {
    if (g_uinput < 0) return false;
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    return write(g_uinput, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

bool syncReport() {
    return writeEvent(EV_SYN, SYN_REPORT, 0);
}

bool emitTap() {
    if (g_uinput < 0 || g_code <= 0 || g_code > KEY_MAX) return false;
    if (!writeEvent(EV_KEY, static_cast<__u16>(g_code), 1) || !syncReport()) return false;
    usleep(kPressHoldUs);
    if (!writeEvent(EV_KEY, static_cast<__u16>(g_code), 0) || !syncReport()) return false;
    return true;
}

void destroyDevice() {
    if (g_uinput < 0) return;
    (void)ioctl(g_uinput, UI_DEV_DESTROY);
    close(g_uinput);
    g_uinput = -1;
}

void setupAbsAxis(int fd, int code, int min, int max, int flat) {
    (void)ioctl(fd, UI_SET_ABSBIT, code);
    uinput_abs_setup abs{};
    abs.code = static_cast<__u16>(code);
    abs.absinfo.minimum = min;
    abs.absinfo.maximum = max;
    abs.absinfo.flat = flat;
    (void)ioctl(fd, UI_ABS_SETUP, &abs);
}

void setupKeyboardCapabilities(int fd) {
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    (void)ioctl(fd, UI_SET_EVBIT, EV_REP);
    // 只声明真实键盘区。旧实现声明 1..KEY_MAX，把 BTN_MOUSE/BTN_GAMEPAD 也塞进键盘，
    // 部分 Android InputReader 会把设备误分类，最终表现为“倍率开启但补发完全没反应”。
    for (int code = 1; code < BTN_MISC; ++code) {
        (void)ioctl(fd, UI_SET_KEYBIT, code);
    }
    (void)ioctl(fd, UI_SET_KEYBIT, g_code);
}

void setupMouseCapabilities(int fd) {
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int code = BTN_LEFT; code <= BTN_TASK; ++code) {
        (void)ioctl(fd, UI_SET_KEYBIT, code);
    }
    (void)ioctl(fd, UI_SET_KEYBIT, g_code);
    // Android 需要 REL 能力才能稳定把 uinput 节点识别成鼠标，而不是通用按键设备。
    (void)ioctl(fd, UI_SET_EVBIT, EV_REL);
    (void)ioctl(fd, UI_SET_RELBIT, REL_X);
    (void)ioctl(fd, UI_SET_RELBIT, REL_Y);
    (void)ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
}

void setupGamepadCapabilities(int fd) {
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    const int keys[] = {
        BTN_SOUTH, BTN_EAST, BTN_C, BTN_NORTH, BTN_WEST, BTN_Z,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START,
        BTN_MODE, BTN_THUMBL, BTN_THUMBR,
        BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT,
        704, 705, 706, 707
    };
    for (int key : keys) (void)ioctl(fd, UI_SET_KEYBIT, key);
    (void)ioctl(fd, UI_SET_KEYBIT, g_code);

    // 只有 EV_KEY 的虚拟设备在部分 OEM 上会被当成键盘。补齐标准摇杆/十字轴能力，
    // 让 InputReader 稳定归类为 GAMEPAD / JOYSTICK；这里从不实际输出这些轴。
    (void)ioctl(fd, UI_SET_EVBIT, EV_ABS);
    setupAbsAxis(fd, ABS_X, -32768, 32767, 1024);
    setupAbsAxis(fd, ABS_Y, -32768, 32767, 1024);
    setupAbsAxis(fd, ABS_RX, -32768, 32767, 1024);
    setupAbsAxis(fd, ABS_RY, -32768, 32767, 1024);
    setupAbsAxis(fd, ABS_Z, 0, 1023, 0);
    setupAbsAxis(fd, ABS_RZ, 0, 1023, 0);
    setupAbsAxis(fd, ABS_HAT0X, -1, 1, 0);
    setupAbsAxis(fd, ABS_HAT0Y, -1, 1, 0);
}

bool setupDevice() {
    g_uinput = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_uinput < 0) return false;

    if (strcmp(g_kind, "mouse") == 0) setupMouseCapabilities(g_uinput);
    else if (strcmp(g_kind, "gamepad") == 0) setupGamepadCapabilities(g_uinput);
    else setupKeyboardCapabilities(g_uinput);

    uinput_setup setup{};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x4B44;
    setup.id.product = strcmp(g_kind, "mouse") == 0 ? 0x1031
            : (strcmp(g_kind, "gamepad") == 0 ? 0x1032 : 0x1030);
    setup.id.version = 2;
    const char* name = strcmp(g_kind, "mouse") == 0
            ? "Axon Input Virtual Click Multiplier Mouse"
            : (strcmp(g_kind, "gamepad") == 0
            ? "Axon Input Virtual Click Multiplier Gamepad"
            : "Axon Input Virtual Click Multiplier Keyboard");
    snprintf(setup.name, sizeof(setup.name), "%s", name);
    (void)ioctl(g_uinput, UI_SET_PHYS, "axon-input/virtual/click-multiplier");

    if (ioctl(g_uinput, UI_DEV_SETUP, &setup) < 0 || ioctl(g_uinput, UI_DEV_CREATE) < 0) {
        destroyDevice();
        return false;
    }
    // 只创建一个目标设备，给 InputReader 一点时间完成分类；后续整个会话复用，不重复创建。
    usleep(70000);
    return true;
}

void clearBursts() {
    g_head = 0;
    g_tail = 0;
    g_size = 0;
    g_nextTapAtMs = 0;
}

void pushBurst(int count, int delayMs) {
    if (count <= 0) return;
    if (count > kMaxExtraPerBurst) count = kMaxExtraPerBurst;
    if (delayMs < 0) delayMs = 0;
    if (delayMs > kMaxDelayMs) delayMs = kMaxDelayMs;

    // 高频点击时宁可丢最旧的一组未执行补发，也不能让队列无限增长到几秒以后继续乱点。
    if (g_size >= kMaxBurstQueue) {
        g_head = (g_head + 1) % kMaxBurstQueue;
        --g_size;
    }
    bool wasEmpty = g_size == 0;
    g_bursts[g_tail] = Burst{count, delayMs};
    g_tail = (g_tail + 1) % kMaxBurstQueue;
    ++g_size;
    if (wasEmpty) g_nextTapAtMs = monotonicMs();
}

void popBurst() {
    if (g_size <= 0) return;
    g_head = (g_head + 1) % kMaxBurstQueue;
    --g_size;
    if (g_size == 0) g_nextTapAtMs = 0;
}

void handleCommand(const char* line) {
    if (line == nullptr) return;
    int count = 0;
    int delayMs = 0;
    if (sscanf(line, "B %d %d", &count, &delayMs) == 2) {
        pushBurst(count, delayMs);
        return;
    }
    if (line[0] == 'C') clearBursts();
}

bool parseArgs(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--kind") == 0 && i + 1 < argc) g_kind = argv[++i];
        else if (strcmp(argv[i], "--code") == 0 && i + 1 < argc) g_code = atoi(argv[++i]);
    }
    if (g_code <= 0 || g_code > KEY_MAX) return false;
    return strcmp(g_kind, "keyboard") == 0 || strcmp(g_kind, "mouse") == 0
            || strcmp(g_kind, "gamepad") == 0;
}
}

int main(int argc, char** argv) {
    if (!parseArgs(argc, argv)) {
        fprintf(stderr, "invalid click multiplier target\n");
        return 2;
    }

    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);

    if (!setupDevice()) {
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        return 5;
    }

    printf("READY %s %d\n", g_kind, g_code);
    fflush(stdout);

    char readBuffer[256];
    char lineBuffer[128];
    int lineLength = 0;

    while (!g_stop) {
        int timeoutMs = -1;
        if (g_size > 0) {
            long long now = monotonicMs();
            long long remain = g_nextTapAtMs - now;
            timeoutMs = remain <= 0 ? 0 : static_cast<int>(remain > 1000 ? 1000 : remain);
        }

        pollfd pfd{};
        pfd.fd = STDIN_FILENO;
        pfd.events = POLLIN | POLLHUP | POLLERR;
        int polled = poll(&pfd, 1, timeoutMs);
        if (polled < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (polled > 0 && (pfd.revents & POLLIN) != 0) {
            ssize_t count = read(STDIN_FILENO, readBuffer, sizeof(readBuffer));
            if (count <= 0) break;
            for (ssize_t i = 0; i < count; ++i) {
                char c = readBuffer[i];
                if (c == '\n' || c == '\r') {
                    if (lineLength > 0) {
                        lineBuffer[lineLength] = '\0';
                        handleCommand(lineBuffer);
                        lineLength = 0;
                    }
                } else if (lineLength < static_cast<int>(sizeof(lineBuffer)) - 1) {
                    lineBuffer[lineLength++] = c;
                } else {
                    // 异常长命令直接丢弃这一行，避免越界或错误倍率。
                    lineLength = 0;
                }
            }
        }
        if (polled > 0 && (pfd.revents & (POLLHUP | POLLERR)) != 0
                && (pfd.revents & POLLIN) == 0) {
            break;
        }

        // poll 超时或命令处理完成后都检查一次到期的补发。C 命令会先清空队列，因此可立即取消。
        if (g_size > 0 && monotonicMs() >= g_nextTapAtMs) {
            Burst& burst = g_bursts[g_head];
            int delayAfterThisTap = burst.delayMs;
            if (!emitTap()) {
                fprintf(stderr, "tap failed: %s\n", strerror(errno));
                fflush(stderr);
                break;
            }
            printf("TAP\n");
            fflush(stdout);

            --burst.remaining;
            if (burst.remaining <= 0) popBurst();
            if (g_size > 0) {
                // “延迟”定义为一次补发完成后到下一次补发开始之间的等待。
                g_nextTapAtMs = monotonicMs() + delayAfterThisTap;
            }
        }
    }

    clearBursts();
    destroyDevice();
    return 0;
}
