// 手柄只读监听。扫描 evdev，只在状态变化时输出。
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/hidraw.h>
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
constexpr int kMaxHidraw = 64;
constexpr int kMaxVaderRaw = 8;
constexpr int kScanIntervalMs = 900;
constexpr uint16_t kFlydigiVendor = 0x37d7;
constexpr uint16_t kVader5ProProduct = 0x2401;
constexpr uint32_t kBackButtonMask = (1u << 15) | (1u << 16) | (1u << 17) | (1u << 18);
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

int gamepadDeviceScore(int fd) {
    unsigned long evBits[8]{};
    unsigned long absBits[8]{};
    unsigned long keyBits[16]{};
    if (!getBits(fd, 0, evBits) || !bitTest(evBits, EV_KEY)) return 0;
    if (!getBits(fd, EV_KEY, keyBits)) return 0;

    int buttonCount = 0;
    const int gamepadKeys[] = {
        BTN_GAMEPAD, BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_C, BTN_Z,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START, BTN_MODE,
        BTN_THUMBL, BTN_THUMBR, BTN_TRIGGER, BTN_THUMB, BTN_THUMB2, BTN_TOP,
        BTN_TOP2, BTN_PINKIE, BTN_BASE, BTN_BASE2, BTN_BASE3, BTN_BASE4, BTN_BASE5, BTN_BASE6,
        BTN_TRIGGER_HAPPY1, BTN_TRIGGER_HAPPY2, BTN_TRIGGER_HAPPY3, BTN_TRIGGER_HAPPY4,
        BTN_TRIGGER_HAPPY5, BTN_TRIGGER_HAPPY6, BTN_TRIGGER_HAPPY7, BTN_TRIGGER_HAPPY8,
        BTN_TRIGGER_HAPPY9, BTN_TRIGGER_HAPPY10, BTN_TRIGGER_HAPPY11, BTN_TRIGGER_HAPPY12, BTN_TRIGGER_HAPPY13
    };
    for (int code : gamepadKeys) if (bitTest(keyBits, code)) ++buttonCount;

    int axisCount = 0;
    if (bitTest(evBits, EV_ABS) && getBits(fd, EV_ABS, absBits)) {
        const int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_BRAKE, ABS_GAS, ABS_HAT0X, ABS_HAT0Y};
        for (int code : axes) if (bitTest(absBits, code)) ++axisCount;
    }

    // 至少有两个典型手柄按键，或同时具备摇杆轴和手柄按键。
    if (buttonCount < 2 && !(buttonCount >= 1 && axisCount >= 2)) return 0;
    int score = buttonCount * 10 + axisCount * 3;
    if (bitTest(keyBits, BTN_GAMEPAD) || bitTest(keyBits, BTN_SOUTH)) score += 30;
    if (axisCount >= 4) score += 20;
    return score;
}

bool isGamepadDevice(int fd) {
    return gamepadDeviceScore(fd) > 0;
}

bool axisInfo(int fd, int code, input_absinfo* out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));
    return ioctl(fd, EVIOCGABS(code), out) >= 0 && out->maximum > out->minimum;
}

int axisCenterScore(const input_absinfo& info) {
    long long range = static_cast<long long>(info.maximum) - info.minimum;
    if (range <= 0) return 1000000;
    long long center = (static_cast<long long>(info.maximum) + info.minimum) / 2;
    long long half = range / 2;
    if (half <= 0) return 1000000;
    long long distance = info.value >= center ? info.value - center : center - info.value;
    return static_cast<int>((distance * 1000LL) / half);
}

bool axisLooksCentered(const input_absinfo& info) {
    int score = axisCenterScore(info);
    long long range = static_cast<long long>(info.maximum) - info.minimum;
    long long tolerance = range / 5;
    if (info.flat > 0 && static_cast<long long>(info.flat) * 2 > tolerance) {
        tolerance = static_cast<long long>(info.flat) * 2;
    }
    long long center = (static_cast<long long>(info.maximum) + info.minimum) / 2;
    long long distance = info.value >= center ? info.value - center : center - info.value;
    return score <= 450 || distance <= tolerance;
}

bool axisCrossesZero(const input_absinfo& info) {
    return info.minimum < 0 && info.maximum > 0;
}

bool axisIsOneSided(const input_absinfo& info) {
    return info.minimum >= 0 || info.maximum <= 0;
}

bool triggerRestAtMax(const input_absinfo& info) {
    long long toMin = static_cast<long long>(info.value) - info.minimum;
    long long toMax = static_cast<long long>(info.maximum) - info.value;
    if (toMin < 0) toMin = -toMin;
    if (toMax < 0) toMax = -toMax;
    return toMax < toMin;
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

int mapTrigger1000(const input_absinfo& info, int value, bool restAtMax) {
    if (info.maximum <= info.minimum) return 0;
    long long den = static_cast<long long>(info.maximum) - info.minimum;
    long long num = restAtMax
            ? static_cast<long long>(info.maximum - value) * 1000LL
            : static_cast<long long>(value - info.minimum) * 1000LL;
    long long out = den ? num / den : 0;
    if (out < 0) out = 0;
    if (out > 1000) out = 1000;
    return static_cast<int>(out);
}

int buttonIndex(int code, bool hasStandardEast, bool hasStandardWest) {
    switch (code) {
        case BTN_SOUTH: return 0;
        case BTN_EAST: return 1;
        case BTN_C: return hasStandardWest ? -1 : 2;
        case BTN_NORTH: return 3;
        case BTN_WEST: return 4;
        case BTN_Z: return hasStandardEast ? -1 : 5;
        case BTN_TL: return 6;
        case BTN_TR: return 7;
        case BTN_TL2: return 8;
        case BTN_TR2: return 9;
        case BTN_SELECT: return 10;
        case BTN_START: return 11;
        case BTN_MODE: return 12;
        case BTN_THUMBL: return 13;
        case BTN_THUMBR: return 14;
        // 旧式 HID/蓝牙手柄可能只上报 BTN_TRIGGER 系列。
        case BTN_TRIGGER: return 0;
        case BTN_THUMB: return 1;
        case BTN_THUMB2: return 4;
        case BTN_TOP: return 3;
        case BTN_TOP2: return 6;
        case BTN_PINKIE: return 7;
        case BTN_BASE: return 8;
        case BTN_BASE2: return 9;
        case BTN_BASE3: case BTN_TRIGGER_HAPPY1: return 15;
        case BTN_BASE4: case BTN_TRIGGER_HAPPY2: return 16;
        case BTN_BASE5: case BTN_TRIGGER_HAPPY3: return 17;
        case BTN_BASE6: case BTN_TRIGGER_HAPPY4: return 18;
        // Flydigi Vader 5 Pro / SDL paddle range. Kernel Flydigi driver exposes M1..M4 here.
        case BTN_TRIGGER_HAPPY5: return 15;
        case BTN_TRIGGER_HAPPY6: return 16;
        case BTN_TRIGGER_HAPPY7: return 17;
        case BTN_TRIGGER_HAPPY8: return 18;
        default: return -1;
    }
}

struct GamepadState {
    int lx = 0, ly = 0, rx = 0, ry = 0;
    int lt = 0, rt = 0;
    uint32_t buttons = 0;
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
    bool triggerLRestAtMax = false;
    bool triggerRRestAtMax = false;
    int analogLt = 0;
    int analogRt = 0;
    bool digitalLt = false;
    bool digitalRt = false;
    bool hasStandardEast = false;
    bool hasStandardWest = false;
    bool vader5Pro = false;
    uint16_t vendor = 0;
    uint16_t product = 0;
    uint32_t evdevBackMask = 0;
    uint32_t rawBackMask = 0;
    GamepadState state{};
    GamepadState emitted{};
    bool emittedOnce = false;
};


struct VaderRaw {
    int fd = -1;
    bool writable = false;
    bool extendedSeen = false;
    uint32_t backMask = 0;
    char path[64]{};
};

void refreshBackButtons(Device* d) {
    if (!d) return;
    d->state.buttons = (d->state.buttons & ~kBackButtonMask)
            | (d->evdevBackMask & kBackButtonMask)
            | (d->rawBackMask & kBackButtonMask);
}

bool isVader5Id(int fd, input_id* out = nullptr) {
    input_id id{};
    if (ioctl(fd, EVIOCGID, &id) < 0) return false;
    if (out) *out = id;
    return id.vendor == kFlydigiVendor && id.product == kVader5ProProduct;
}

ssize_t writeHidrawPacket(int fd, const uint8_t payload[32]) {
    if (fd < 0 || !payload) return -1;
    // Vader 5 Pro 的 interface 1 是 unnumbered 32-byte report。先发送原始 32 字节；
    // 只有某些内核 hidraw 明确要求 report-id 时才回退到前置 0 的兼容形式。
    ssize_t n = write(fd, payload, 32);
    if (n >= 0) return n;
    uint8_t numbered[33]{};
    memcpy(numbered + 1, payload, 32);
    return write(fd, numbered, sizeof(numbered));
}

void sendVader5Command(int fd, const uint8_t* prefix, size_t prefixSize) {
    if (fd < 0 || !prefix || prefixSize == 0 || prefixSize > 32) return;
    uint8_t packet[32]{};
    memcpy(packet, prefix, prefixSize);
    (void)writeHidrawPacket(fd, packet);
}

void enableVader5Extended(VaderRaw* raw) {
    if (!raw || raw->fd < 0 || !raw->writable) return;
    static const uint8_t kInfo[] = {0x5a, 0xa5, 0x01, 0x02, 0x03};
    static const uint8_t kSerial[] = {0x5a, 0xa5, 0xa1, 0x02, 0xa3};
    static const uint8_t kConfigRead[] = {0x5a, 0xa5, 0x02, 0x02, 0x04};
    static const uint8_t kConfigData[] = {0x5a, 0xa5, 0x04, 0x02, 0x06};
    static const uint8_t kTestMode[] = {0x5a, 0xa5, 0x11, 0x07, 0xff, 0x01, 0xff, 0xff, 0xff, 0x15};
    sendVader5Command(raw->fd, kInfo, sizeof(kInfo));
    usleep(2500);
    sendVader5Command(raw->fd, kSerial, sizeof(kSerial));
    usleep(2500);
    sendVader5Command(raw->fd, kConfigRead, sizeof(kConfigRead));
    usleep(2500);
    sendVader5Command(raw->fd, kConfigData, sizeof(kConfigData));
    usleep(2500);
    sendVader5Command(raw->fd, kTestMode, sizeof(kTestMode));
}

void closeVaderRaw(VaderRaw* raw) {
    if (!raw) return;
    if (raw->fd >= 0 && raw->writable && raw->extendedSeen) {
        static const uint8_t kDisable[] = {0x5a, 0xa5, 0x11, 0x07, 0xff, 0x00, 0xff, 0xff, 0xff, 0x14};
        sendVader5Command(raw->fd, kDisable, sizeof(kDisable));
    }
    if (raw->fd >= 0) close(raw->fd);
    *raw = VaderRaw{};
    raw->fd = -1;
}

void closeVaderRaws(VaderRaw* raws, int* count, Device* d) {
    if (!raws || !count) return;
    for (int i = 0; i < *count; ++i) closeVaderRaw(&raws[i]);
    *count = 0;
    if (d) {
        d->rawBackMask = 0;
        refreshBackButtons(d);
    }
}

int scanVaderRaws(VaderRaw* raws, int capacity) {
    if (!raws || capacity <= 0) return 0;
    int count = 0;
    for (int i = 0; i < kMaxHidraw && count < capacity; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/hidraw%d", i);
        int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        bool writable = fd >= 0;
        if (fd < 0) fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        hidraw_devinfo info{};
        if (ioctl(fd, HIDIOCGRAWINFO, &info) < 0
                || static_cast<uint16_t>(info.vendor) != kFlydigiVendor
                || static_cast<uint16_t>(info.product) != kVader5ProProduct) {
            close(fd);
            continue;
        }
        VaderRaw raw{};
        raw.fd = fd;
        raw.writable = writable;
        snprintf(raw.path, sizeof(raw.path), "%s", path);
        raws[count] = raw;
        enableVader5Extended(&raws[count]);
        ++count;
    }
    if (count > 0) {
        printf("STATUS vader5-pro-raw-ready %d\n", count);
        fflush(stdout);
    }
    return count;
}

uint32_t parseVader5BackMask(const uint8_t* report, size_t size) {
    if (!report || size < 14) return 0;
    size_t off = 0;
    if (!(report[0] == 0x5a && report[1] == 0xa5 && report[2] == 0xef)) {
        // Compatibility with stacks that prepend a zero report-id on read.
        if (size >= 15 && report[0] == 0x00
                && report[1] == 0x5a && report[2] == 0xa5 && report[3] == 0xef) {
            off = 1;
        } else {
            return UINT32_MAX;
        }
    }
    uint8_t ext = report[off + 13];
    uint32_t mask = 0;
    if (ext & (1u << 2)) mask |= (1u << 15); // M1
    if (ext & (1u << 3)) mask |= (1u << 16); // M2
    if (ext & (1u << 4)) mask |= (1u << 17); // M3
    if (ext & (1u << 5)) mask |= (1u << 18); // M4
    return mask;
}

bool readVaderRaw(VaderRaw* raw) {
    if (!raw || raw->fd < 0) return false;
    bool changed = false;
    uint8_t buffer[256];
    for (;;) {
        ssize_t n = read(raw->fd, buffer, sizeof(buffer));
        if (n > 0) {
            size_t size = static_cast<size_t>(n);
            bool parsed = false;
            // hidraw normally returns one report per read, but scan the buffer defensively.
            for (size_t off = 0; off + 14 <= size; ++off) {
                uint32_t mask = parseVader5BackMask(buffer + off, size - off);
                if (mask == UINT32_MAX) continue;
                if (raw->backMask != mask) {
                    raw->backMask = mask;
                    changed = true;
                }
                raw->extendedSeen = true;
                parsed = true;
                break;
            }
            if (parsed) continue;
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return changed;
        if (n == 0) return changed;
        closeVaderRaw(raw);
        return true;
    }
}

uint32_t aggregateVaderBackMask(const VaderRaw* raws, int count) {
    uint32_t mask = 0;
    for (int i = 0; raws && i < count; ++i) {
        if (raws[i].fd >= 0 && raws[i].extendedSeen) mask |= raws[i].backMask;
    }
    return mask & kBackButtonMask;
}

void closeDevice(Device* d) {
    if (!d) return;
    if (d->fd >= 0) close(d->fd);
    *d = Device{};
    d->fd = -1;
}

bool selectAxes(int fd, Device* d) {
    if (!d) return false;
    // 有些手柄把按键和摇杆拆成不同 event 节点。按键节点不能因为缺少 X/Y 被丢弃。
    bool hasLeftX = axisInfo(fd, ABS_X, &d->leftX);
    bool hasLeftY = axisInfo(fd, ABS_Y, &d->leftY);
    (void)hasLeftX;
    (void)hasLeftY;

    input_absinfo z{}, rz{}, rx{}, ry{};
    bool hasZ = axisInfo(fd, ABS_Z, &z);
    bool hasRz = axisInfo(fd, ABS_RZ, &rz);
    bool hasRx = axisInfo(fd, ABS_RX, &rx);
    bool hasRy = axisInfo(fd, ABS_RY, &ry);

    // 优先按轴范围判断。RX/RY 为有符号摇杆，Z/RZ 为单向扳机时直接固定映射。
    bool rrSignedPair = hasRx && hasRy && axisCrossesZero(rx) && axisCrossesZero(ry);
    bool zrTriggerPair = hasZ && hasRz && axisIsOneSided(z) && axisIsOneSided(rz);
    int zrScore = (hasZ && hasRz) ? axisCenterScore(z) + axisCenterScore(rz) : 1000000;
    int rrScore = (hasRx && hasRy) ? axisCenterScore(rx) + axisCenterScore(ry) : 1000000;
    bool zrCentered = hasZ && hasRz && axisLooksCentered(z) && axisLooksCentered(rz);
    bool rrCentered = hasRx && hasRy && axisLooksCentered(rx) && axisLooksCentered(ry);
    if (rrSignedPair && zrTriggerPair) {
        d->rightXCode = ABS_RX;
        d->rightYCode = ABS_RY;
        d->rightX = rx;
        d->rightY = ry;
    } else if (rrCentered && (!zrCentered || rrScore <= zrScore)) {
        d->rightXCode = ABS_RX;
        d->rightYCode = ABS_RY;
        d->rightX = rx;
        d->rightY = ry;
    } else if (zrCentered) {
        d->rightXCode = ABS_Z;
        d->rightYCode = ABS_RZ;
        d->rightX = z;
        d->rightY = rz;
    } else if (hasRx && hasRy && rrScore < zrScore) {
        d->rightXCode = ABS_RX;
        d->rightYCode = ABS_RY;
        d->rightX = rx;
        d->rightY = ry;
    } else if (hasZ && hasRz) {
        d->rightXCode = ABS_Z;
        d->rightYCode = ABS_RZ;
        d->rightX = z;
        d->rightY = rz;
    }

    // Android 常见映射：L2=ABS_BRAKE，R2=ABS_GAS。
    input_absinfo info{};
    if (axisInfo(fd, ABS_BRAKE, &info)) {
        d->triggerLCode = ABS_BRAKE;
        d->triggerL = info;
    }
    if (axisInfo(fd, ABS_GAS, &info)) {
        d->triggerRCode = ABS_GAS;
        d->triggerR = info;
    }

    // XInput 常见映射：L2=ABS_Z，R2=ABS_RZ。
    if (d->triggerLCode < 0 && hasZ && d->rightXCode != ABS_Z && d->rightYCode != ABS_Z) {
        d->triggerLCode = ABS_Z;
        d->triggerL = z;
    }
    if (d->triggerRCode < 0 && hasRz && d->rightXCode != ABS_RZ && d->rightYCode != ABS_RZ) {
        d->triggerRCode = ABS_RZ;
        d->triggerR = rz;
    }

    // 少量设备把扳机放在未被右摇杆占用的 RX/RY。
    if (d->triggerLCode < 0 && hasRx && d->rightXCode != ABS_RX && d->rightYCode != ABS_RX) {
        d->triggerLCode = ABS_RX;
        d->triggerL = rx;
    }
    if (d->triggerRCode < 0 && hasRy && d->rightXCode != ABS_RY && d->rightYCode != ABS_RY) {
        d->triggerRCode = ABS_RY;
        d->triggerR = ry;
    }

    if (d->triggerLCode >= 0) {
        d->triggerLRestAtMax = triggerRestAtMax(d->triggerL);
        d->analogLt = mapTrigger1000(d->triggerL, d->triggerL.value, d->triggerLRestAtMax);
    }
    if (d->triggerRCode >= 0) {
        d->triggerRRestAtMax = triggerRestAtMax(d->triggerR);
        d->analogRt = mapTrigger1000(d->triggerR, d->triggerR.value, d->triggerRRestAtMax);
    }
    d->state.lt = d->analogLt;
    d->state.rt = d->analogRt;
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
    input_id id{};
    if (ioctl(fd, EVIOCGID, &id) >= 0) {
        candidate.vendor = id.vendor;
        candidate.product = id.product;
        candidate.vader5Pro = id.vendor == kFlydigiVendor && id.product == kVader5ProProduct;
    }
    if (!selectAxes(fd, &candidate)) {
        close(fd);
        return false;
    }
    unsigned long keyBits[16]{};
    if (getBits(fd, EV_KEY, keyBits)) {
        candidate.hasStandardEast = bitTest(keyBits, BTN_EAST);
        candidate.hasStandardWest = bitTest(keyBits, BTN_WEST);
    }
    snprintf(candidate.path, sizeof(candidate.path), "%s", path);
    snprintf(candidate.name, sizeof(candidate.name), "%s", name[0] ? name : "gamepad");
    *d = candidate;
    printf("STATUS gamepad-ready %s %s%s\n", d->path, d->name, d->vader5Pro ? " vader5-pro" : "");
    fflush(stdout);
    return true;
}

void scan(Device* d) {
    if (!d || d->fd >= 0) return;
    int bestScore = 0;
    char bestPath[64]{};
    for (int i = 0; i < kMaxEvents; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        if (access(path, R_OK) != 0) continue;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        char name[128]{};
        getDeviceName(fd, name, sizeof(name));
        int score = (name[0] && strstr(name, kVirtualPrefix)) ? 0 : gamepadDeviceScore(fd);
        input_id id{};
        if (score > 0 && ioctl(fd, EVIOCGID, &id) >= 0
                && id.vendor == kFlydigiVendor && id.product == kVader5ProProduct) {
            score += 500;
        }
        close(fd);
        if (score > bestScore) {
            bestScore = score;
            snprintf(bestPath, sizeof(bestPath), "%s", path);
        }
    }
    if (bestScore > 0) (void)attachDevice(bestPath, d);
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
        int index = buttonIndex(ev.code, d->hasStandardEast, d->hasStandardWest);
        if (index >= 0 && index < 32) {
            uint32_t bit = static_cast<uint32_t>(1u << index);
            bool pressed = ev.value != 0;
            if (index >= 15 && index <= 18) {
                if (pressed) d->evdevBackMask |= bit;
                else d->evdevBackMask &= ~bit;
                refreshBackButtons(d);
            } else {
                if (pressed) d->state.buttons |= bit;
                else d->state.buttons &= ~bit;
            }
            if (ev.code == BTN_TL2) d->digitalLt = pressed;
            else if (ev.code == BTN_TR2) d->digitalRt = pressed;
            d->state.lt = d->digitalLt ? 1000 : d->analogLt;
            d->state.rt = d->digitalRt ? 1000 : d->analogRt;
        }
        return true;
    }
    if (ev.type == EV_ABS) {
        if (ev.code == ABS_X) d->state.lx = mapAxis1000(d->leftX, ev.value);
        else if (ev.code == ABS_Y) d->state.ly = mapAxis1000(d->leftY, ev.value);
        else if (ev.code == d->rightXCode) d->state.rx = mapAxis1000(d->rightX, ev.value);
        else if (ev.code == d->rightYCode) d->state.ry = mapAxis1000(d->rightY, ev.value);
        else if (ev.code == d->triggerLCode) {
            d->analogLt = mapTrigger1000(d->triggerL, ev.value, d->triggerLRestAtMax);
            d->state.lt = d->digitalLt ? 1000 : d->analogLt;
        } else if (ev.code == d->triggerRCode) {
            d->analogRt = mapTrigger1000(d->triggerR, ev.value, d->triggerRRestAtMax);
            d->state.rt = d->digitalRt ? 1000 : d->analogRt;
        }
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
    VaderRaw vaderRaws[kMaxVaderRaw]{};
    for (auto& raw : vaderRaws) raw.fd = -1;
    int vaderRawCount = 0;
    long long lastScan = 0;
    long long lastRawScan = 0;
    long long lastRawInitRetry = 0;
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

        bool anyRawOpen = false;
        for (int i = 0; i < vaderRawCount; ++i) {
            if (vaderRaws[i].fd >= 0) { anyRawOpen = true; break; }
        }
        if (!anyRawOpen && now - lastRawScan >= kScanIntervalMs) {
            closeVaderRaws(vaderRaws, &vaderRawCount, &device);
            vaderRawCount = scanVaderRaws(vaderRaws, kMaxVaderRaw);
            lastRawScan = now;
            lastRawInitRetry = now;
        }
        if (anyRawOpen && now - lastRawInitRetry >= 1500) {
            for (int i = 0; i < vaderRawCount; ++i) {
                if (vaderRaws[i].fd >= 0 && !vaderRaws[i].extendedSeen) {
                    enableVader5Extended(&vaderRaws[i]);
                }
            }
            lastRawInitRetry = now;
        }

        pollfd pfds[1 + kMaxVaderRaw]{};
        int kinds[1 + kMaxVaderRaw]{};
        int refs[1 + kMaxVaderRaw]{};
        int count = 0;
        if (device.fd >= 0) {
            pfds[count] = pollfd{device.fd, POLLIN | POLLERR | POLLHUP, 0};
            kinds[count] = 0;
            refs[count] = -1;
            ++count;
        }
        for (int i = 0; i < vaderRawCount && count < 1 + kMaxVaderRaw; ++i) {
            if (vaderRaws[i].fd < 0) continue;
            pfds[count] = pollfd{vaderRaws[i].fd, POLLIN | POLLERR | POLLHUP, 0};
            kinds[count] = 1;
            refs[count] = i;
            ++count;
        }

        if (count == 0) {
            poll(nullptr, 0, 120);
            continue;
        }

        int result = poll(pfds, static_cast<nfds_t>(count), 120);
        if (result < 0) {
            if (errno == EINTR) continue;
            break;
        }

        bool rawChanged = false;
        if (result > 0) {
            for (int i = 0; i < count; ++i) {
                if (!pfds[i].revents) continue;
                if (kinds[i] == 0) {
                    if ((pfds[i].revents & (POLLERR | POLLHUP)) || !readEvents(&device)) {
                        printf("STATUS gamepad-disconnected\n");
                        closeDevice(&device);
                    }
                } else {
                    int rawIndex = refs[i];
                    if (rawIndex < 0 || rawIndex >= vaderRawCount) continue;
                    VaderRaw& raw = vaderRaws[rawIndex];
                    if (pfds[i].revents & (POLLERR | POLLHUP)) {
                        if (raw.backMask != 0) rawChanged = true;
                        closeVaderRaw(&raw);
                    } else if (pfds[i].revents & POLLIN) {
                        if (readVaderRaw(&raw)) rawChanged = true;
                    }
                }
            }
        }

        if (rawChanged && device.fd >= 0) {
            uint32_t newRawMask = aggregateVaderBackMask(vaderRaws, vaderRawCount);
            if (device.rawBackMask != newRawMask) {
                device.rawBackMask = newRawMask;
                refreshBackButtons(&device);
                emit(&device, true);
            }
        }
    }

    closeVaderRaws(vaderRaws, &vaderRawCount, &device);
    closeDevice(&device);
    printf("STATUS stopped\n");
    return 0;
}
