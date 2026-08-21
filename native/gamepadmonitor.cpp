// 手柄只读监听。扫描 evdev，只在状态变化时输出。
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#ifndef BTN_DPAD_UP
#define BTN_DPAD_UP 0x220
#define BTN_DPAD_DOWN 0x221
#define BTN_DPAD_LEFT 0x222
#define BTN_DPAD_RIGHT 0x223
#endif

namespace {
constexpr int kMaxEvents = 256;
constexpr int kScanIntervalMs = 900;
constexpr const char* kVirtualPrefix = "Axon Input Virtual";
volatile sig_atomic_t gStop = 0;

long long nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

void onSignal(int) { gStop = 1; }

bool bitTest(const unsigned long* bits, int bit) {
    constexpr int kBitsPerLong = static_cast<int>(sizeof(unsigned long) * 8);
    return (bits[bit / kBitsPerLong] >> (bit % kBitsPerLong)) & 1UL;
}

template <size_t N>
bool getBits(int fd, int type, unsigned long (&bits)[N]) {
    memset(bits, 0, sizeof(bits));
    return ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits) >= 0;
}

bool getDeviceName(int fd, char* out, size_t size) {
    if (!out || size == 0) return false;
    memset(out, 0, size);
    return ioctl(fd, EVIOCGNAME(static_cast<int>(size - 1)), out) >= 0;
}

bool isGamepadDevice(int fd) {
    unsigned long evBits[8]{};
    unsigned long absBits[8]{};
    unsigned long keyBits[16]{};
    if (!getBits(fd, 0, evBits) || !bitTest(evBits, EV_ABS) || !bitTest(evBits, EV_KEY)) return false;
    if (!getBits(fd, EV_ABS, absBits) || !bitTest(absBits, ABS_X) || !bitTest(absBits, ABS_Y)) return false;
    if (!getBits(fd, EV_KEY, keyBits)) return false;
    return bitTest(keyBits, BTN_GAMEPAD) || bitTest(keyBits, BTN_SOUTH)
            || bitTest(keyBits, BTN_EAST) || bitTest(keyBits, BTN_START);
}

bool axisInfo(int fd, int code, input_absinfo* out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));
    return ioctl(fd, EVIOCGABS(code), out) >= 0 && out->maximum > out->minimum;
}

bool axisLooksCentered(const input_absinfo& info) {
    long long range = static_cast<long long>(info.maximum) - info.minimum;
    if (range <= 0) return false;
    long long center = (static_cast<long long>(info.maximum) + info.minimum) / 2;
    long long minSide = center - info.minimum;
    long long maxSide = info.maximum - center;
    long long balance = minSide < maxSide ? minSide : maxSide;
    return balance * 100 >= range * 35;
}

int mapAxis1000(const input_absinfo& info, int value) {
    double center = (static_cast<double>(info.minimum) + info.maximum) * 0.5;
    double positiveRange = static_cast<double>(info.maximum) - center;
    double negativeRange = center - static_cast<double>(info.minimum);
    double raw = static_cast<double>(value) - center;
    double denom = raw >= 0.0 ? positiveRange : negativeRange;
    if (denom <= 0.0) return 0;
    double x = raw / denom;
    if (x > 1.0) x = 1.0;
    if (x < -1.0) x = -1.0;

    double flatDenom = positiveRange < negativeRange ? positiveRange : negativeRange;
    double dead = (info.flat > 0 && flatDenom > 0.0) ? static_cast<double>(info.flat) / flatDenom : 0.0;
    if (dead > 0.35) dead = 0.35;
    double mag = x < 0.0 ? -x : x;
    if (mag <= dead) return 0;
    mag = (mag - dead) / (1.0 - dead);
    int out = static_cast<int>((x < 0.0 ? -mag : mag) * 1000.0);
    if (out > 1000) out = 1000;
    if (out < -1000) out = -1000;
    return out;
}

int mapTrigger1000(const input_absinfo& info, int value) {
    if (info.maximum <= info.minimum) return 0;
    long long num = static_cast<long long>(value - info.minimum) * 1000LL;
    long long den = static_cast<long long>(info.maximum) - info.minimum;
    long long out = den ? num / den : 0;
    if (out < 0) out = 0;
    if (out > 1000) out = 1000;
    return static_cast<int>(out);
}

int buttonIndex(int code) {
    switch (code) {
        case BTN_SOUTH: return 0;
        case BTN_EAST: return 1;
        case BTN_C: return 2;
        case BTN_NORTH: return 3;
        case BTN_WEST: return 4;
        case BTN_Z: return 5;
        case BTN_TL: return 6;
        case BTN_TR: return 7;
        case BTN_TL2: return 8;
        case BTN_TR2: return 9;
        case BTN_SELECT: return 10;
        case BTN_START: return 11;
        case BTN_MODE: return 12;
        case BTN_THUMBL: return 13;
        case BTN_THUMBR: return 14;
        default: return -1;
    }
}

struct GamepadState {
    int lx = 0, ly = 0, rx = 0, ry = 0;
    int lt = 0, rt = 0;
    uint16_t buttons = 0;
};

struct Device {
    int fd = -1;
    char path[64]{};
    char name[128]{};
    input_absinfo leftX{}, leftY{}, rightX{}, rightY{}, triggerL{}, triggerR{};
    int rightXCode = -1;
    int rightYCode = -1;
    int triggerLCode = -1;
    int triggerRCode = -1;
    GamepadState state{};
    GamepadState emitted{};
    bool emittedOnce = false;
};

void closeDevice(Device* d) {
    if (!d) return;
    if (d->fd >= 0) close(d->fd);
    *d = Device{};
    d->fd = -1;
}

bool selectAxes(int fd, Device* d) {
    if (!d) return false;
    if (!axisInfo(fd, ABS_X, &d->leftX) || !axisInfo(fd, ABS_Y, &d->leftY)) return false;

    input_absinfo z{}, rz{}, rx{}, ry{};
    bool hasZ = axisInfo(fd, ABS_Z, &z);
    bool hasRz = axisInfo(fd, ABS_RZ, &rz);
    bool hasRx = axisInfo(fd, ABS_RX, &rx);
    bool hasRy = axisInfo(fd, ABS_RY, &ry);
    if (hasZ && hasRz && axisLooksCentered(z) && axisLooksCentered(rz)) {
        d->rightXCode = ABS_Z;
        d->rightYCode = ABS_RZ;
        d->rightX = z;
        d->rightY = rz;
    } else if (hasRx && hasRy && axisLooksCentered(rx) && axisLooksCentered(ry)) {
        d->rightXCode = ABS_RX;
        d->rightYCode = ABS_RY;
        d->rightX = rx;
        d->rightY = ry;
    }

    input_absinfo info{};
    if (axisInfo(fd, ABS_BRAKE, &info)) {
        d->triggerLCode = ABS_BRAKE;
        d->triggerL = info;
    } else if (hasRz && d->rightYCode != ABS_RZ && !axisLooksCentered(rz)) {
        d->triggerLCode = ABS_RZ;
        d->triggerL = rz;
    }
    if (axisInfo(fd, ABS_GAS, &info)) {
        d->triggerRCode = ABS_GAS;
        d->triggerR = info;
    } else if (hasZ && d->rightXCode != ABS_Z && !axisLooksCentered(z)) {
        d->triggerRCode = ABS_Z;
        d->triggerR = z;
    }
    return true;
}

bool attachDevice(const char* path, Device* d) {
    if (!path || !d) return false;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    char name[128]{};
    getDeviceName(fd, name, sizeof(name));
    if ((name[0] && strstr(name, kVirtualPrefix)) || !isGamepadDevice(fd)) {
        close(fd);
        return false;
    }
    Device candidate{};
    candidate.fd = fd;
    if (!selectAxes(fd, &candidate)) {
        close(fd);
        return false;
    }
    snprintf(candidate.path, sizeof(candidate.path), "%s", path);
    snprintf(candidate.name, sizeof(candidate.name), "%s", name[0] ? name : "gamepad");
    *d = candidate;
    printf("STATUS gamepad-ready %s %s\n", d->path, d->name);
    fflush(stdout);
    return true;
}

void scan(Device* d) {
    if (!d || d->fd >= 0) return;
    for (int i = 0; i < kMaxEvents && d->fd < 0; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        if (access(path, R_OK) != 0) continue;
        (void)attachDevice(path, d);
    }
}

bool same(const GamepadState& a, const GamepadState& b) {
    return a.lx == b.lx && a.ly == b.ly && a.rx == b.rx && a.ry == b.ry
            && a.lt == b.lt && a.rt == b.rt && a.buttons == b.buttons;
}

void emit(Device* d, bool force = false) {
    if (!d) return;
    if (!force && d->emittedOnce && same(d->state, d->emitted)) return;
    printf("GAMEPAD %d %d %d %d %d %d %u\n",
           d->state.lx, d->state.ly, d->state.rx, d->state.ry,
           d->state.lt, d->state.rt, static_cast<unsigned>(d->state.buttons));
    fflush(stdout);
    d->emitted = d->state;
    d->emittedOnce = true;
}

bool process(Device* d, const input_event& ev) {
    if (!d || d->fd < 0) return false;
    if (ev.type == EV_KEY) {
        int index = buttonIndex(ev.code);
        if (index >= 0 && index < 16) {
            uint16_t bit = static_cast<uint16_t>(1u << index);
            if (ev.value != 0) d->state.buttons |= bit;
            else d->state.buttons &= static_cast<uint16_t>(~bit);
        }
        return true;
    }
    if (ev.type == EV_ABS) {
        if (ev.code == ABS_X) d->state.lx = mapAxis1000(d->leftX, ev.value);
        else if (ev.code == ABS_Y) d->state.ly = mapAxis1000(d->leftY, ev.value);
        else if (ev.code == d->rightXCode) d->state.rx = mapAxis1000(d->rightX, ev.value);
        else if (ev.code == d->rightYCode) d->state.ry = mapAxis1000(d->rightY, ev.value);
        else if (ev.code == d->triggerLCode) d->state.lt = mapTrigger1000(d->triggerL, ev.value);
        else if (ev.code == d->triggerRCode) d->state.rt = mapTrigger1000(d->triggerR, ev.value);
        return true;
    }
    if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
        emit(d);
        return true;
    }
    return true;
}

bool readEvents(Device* d) {
    input_event events[32];
    for (;;) {
        ssize_t n = read(d->fd, events, sizeof(events));
        if (n > 0) {
            size_t count = static_cast<size_t>(n) / sizeof(input_event);
            for (size_t i = 0; i < count; ++i) if (!process(d, events[i])) return false;
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        return n != 0;
    }
}
} // 命名空间

int main() {
    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    setvbuf(stdout, nullptr, _IOLBF, 0);

    Device device{};
    device.fd = -1;
    long long lastScan = 0;
    long long lastHeartbeat = 0;
    while (!gStop) {
        long long now = nowMs();
        if (now - lastHeartbeat >= 1000) {
            printf("PING\n");
            fflush(stdout);
            lastHeartbeat = now;
        }
        if (device.fd < 0 && now - lastScan >= kScanIntervalMs) {
            scan(&device);
            if (device.fd < 0) printf("STATUS waiting-gamepad\n");
            else emit(&device, true);
            lastScan = now;
        }
        if (device.fd < 0) {
            poll(nullptr, 0, 120);
            continue;
        }
        pollfd pfd{device.fd, POLLIN | POLLERR | POLLHUP, 0};
        int result = poll(&pfd, 1, 120);
        if (result < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (result > 0 && pfd.revents) {
            if ((pfd.revents & (POLLERR | POLLHUP)) || !readEvents(&device)) {
                printf("STATUS gamepad-disconnected\n");
                closeDevice(&device);
            }
        }
    }
    closeDevice(&device);
    printf("STATUS stopped\n");
    return 0;
}
