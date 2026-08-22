// 灵敏度代理。读取 evdev，并通过虚拟输入设备输出处理后的鼠标、手柄和视角输入。
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uhid.h>
#include <linux/uinput.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifndef BUS_VIRTUAL
#define BUS_VIRTUAL 0x06
#endif
#ifndef BTN_DPAD_UP
#define BTN_DPAD_UP 0x220
#define BTN_DPAD_DOWN 0x221
#define BTN_DPAD_LEFT 0x222
#define BTN_DPAD_RIGHT 0x223
#endif

namespace {

constexpr int kMaxEvents = 256;
constexpr int kMaxGamepads = 8;
constexpr int kScanIntervalMs = 900;
constexpr int kConfigIntervalMs = 50;
constexpr int kMotionTelemetryIntervalMs = 8; // 输出频率不超过 125 Hz。
constexpr int kViewTickMs = 10; // 100 Hz 足够平滑，同时减少相对视角事件对按键队列的占用。
constexpr int kMaxReadBatchesPerWake = 2; // 单节点每轮最多处理 64 个事件，避免高轮询轴节点饿死按键节点。
constexpr double kViewCountsPerSecond = 420.0;
constexpr double kViewDeadzone = 0.08;
constexpr const char* kVirtualPrefix = "Axon Input Virtual";

volatile sig_atomic_t gStop = 0;

long long nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

void onSignal(int) {
    gStop = 1;
}

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

bool getDevicePhys(int fd, char* out, size_t size) {
    if (!out || size == 0) return false;
    memset(out, 0, size);
    return ioctl(fd, EVIOCGPHYS(static_cast<int>(size - 1)), out) >= 0;
}

bool getDeviceUniq(int fd, char* out, size_t size) {
    if (!out || size == 0) return false;
    memset(out, 0, size);
    return ioctl(fd, EVIOCGUNIQ(static_cast<int>(size - 1)), out) >= 0;
}

bool getDeviceId(int fd, input_id* out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));
    return ioctl(fd, EVIOCGID, out) >= 0;
}

bool nameIsVirtual(const char* name) {
    return name && strstr(name, kVirtualPrefix) != nullptr;
}

bool deviceIsAxonVirtual(int fd) {
    char name[128]{};
    char phys[128]{};
    char uniq[128]{};
    getDeviceName(fd, name, sizeof(name));
    getDevicePhys(fd, phys, sizeof(phys));
    getDeviceUniq(fd, uniq, sizeof(uniq));
    return nameIsVirtual(name)
            || strstr(phys, "axon-input/virtual") != nullptr
            || strstr(uniq, "axon-input") != nullptr;
}

bool isMouseDevice(int fd) {
    unsigned long evBits[8]{};
    unsigned long relBits[8]{};
    unsigned long keyBits[16]{};
    if (!getBits(fd, 0, evBits) || !bitTest(evBits, EV_REL) || !bitTest(evBits, EV_KEY)) return false;
    if (!getBits(fd, EV_REL, relBits) || !bitTest(relBits, REL_X) || !bitTest(relBits, REL_Y)) return false;
    if (!getBits(fd, EV_KEY, keyBits)) return false;
    return bitTest(keyBits, BTN_LEFT) || bitTest(keyBits, BTN_MOUSE);
}

bool isGamepadDevice(int fd) {
    unsigned long evBits[8]{};
    unsigned long absBits[8]{};
    unsigned long keyBits[16]{};
    if (!getBits(fd, 0, evBits) || !bitTest(evBits, EV_KEY)) return false;
    if (!getBits(fd, EV_KEY, keyBits)) return false;

    int buttonCount = 0;
    const int gamepadKeys[] = {
        BTN_GAMEPAD, BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_C, BTN_Z,
        BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START, BTN_MODE,
        BTN_THUMBL, BTN_THUMBR, BTN_TRIGGER, BTN_THUMB, BTN_THUMB2, BTN_TOP,
        BTN_TOP2, BTN_PINKIE, BTN_BASE, BTN_BASE2
    };
    for (int code : gamepadKeys) if (bitTest(keyBits, code)) ++buttonCount;

    int axisCount = 0;
    bool dualStickAxes = false;
    if (bitTest(evBits, EV_ABS) && getBits(fd, EV_ABS, absBits)) {
        const int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_BRAKE, ABS_GAS};
        for (int code : axes) if (bitTest(absBits, code)) ++axisCount;
        bool leftPair = bitTest(absBits, ABS_X) && bitTest(absBits, ABS_Y);
        bool rightPair = (bitTest(absBits, ABS_RX) && bitTest(absBits, ABS_RY))
                || (bitTest(absBits, ABS_Z) && bitTest(absBits, ABS_RZ));
        dualStickAxes = leftPair && rightPair;
    }
    return buttonCount >= 2 || (buttonCount >= 1 && axisCount >= 2) || dualStickAxes;
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

double calibratedCenter(const input_absinfo& info) {
    const double midpoint = (static_cast<double>(info.minimum) + info.maximum) * 0.5;
    const double half = (static_cast<double>(info.maximum) - info.minimum) * 0.5;
    if (half <= 0.0) return midpoint;
    const double offset = static_cast<double>(info.value) - midpoint;
    double tolerance = half * 0.08;
    if (info.flat > 0 && static_cast<double>(info.flat) * 2.0 > tolerance) {
        tolerance = static_cast<double>(info.flat) * 2.0;
    }
    return fabs(offset) <= tolerance ? static_cast<double>(info.value) : midpoint;
}

int16_t mapAxis(const input_absinfo& info, int value, int gainPercent, double center) {
    const double positiveRange = static_cast<double>(info.maximum) - center;
    const double negativeRange = center - static_cast<double>(info.minimum);
    const double raw = static_cast<double>(value) - center;
    const double denom = raw >= 0.0 ? positiveRange : negativeRange;
    if (denom <= 0.0) return 0;
    double x = raw / denom;
    if (x > 1.0) x = 1.0;
    if (x < -1.0) x = -1.0;

    double dead = 0.0;
    const double flatDenom = positiveRange < negativeRange ? positiveRange : negativeRange;
    if (info.flat > 0 && flatDenom > 0.0) {
        dead = static_cast<double>(info.flat) / flatDenom;
    }
    // 超过 100% 后保留最小中心死区，避免硬件零点噪声被倍率放大成漂移。
    if (gainPercent > 100) {
        const double progress = static_cast<double>(gainPercent - 100) / 400.0;
        const double antiDrift = 0.025 + progress * 0.035; // 2.5% -> 6%
        if (antiDrift > dead) dead = antiDrift;
    }
    if (dead > 0.35) dead = 0.35;

    const double sign = x < 0.0 ? -1.0 : 1.0;
    double mag = x < 0.0 ? -x : x;
    if (mag <= dead) return 0;
    mag = (mag - dead) / (1.0 - dead);

    double gain = gainPercent / 100.0;
    if (gain < 0.01) gain = 0.01;
    if (gain > 5.0) gain = 5.0;
    // 真正放大摇杆输出幅度。200% 时约半推即可达到满量程。
    // HID 摇杆本身最大只能输出 100%，因此高倍率通过提前饱和体现。
    double scaled = mag * gain;
    if (scaled > 1.0) scaled = 1.0;
    int out = static_cast<int>(sign * scaled * 32767.0);
    if (out > 32767) out = 32767;
    if (out < -32767) out = -32767;
    return static_cast<int16_t>(out);
}

uint8_t mapTrigger(const input_absinfo& info, int value, bool restAtMax) {
    if (info.maximum <= info.minimum) return 0;
    long long denominator = static_cast<long long>(info.maximum) - info.minimum;
    long long numerator = restAtMax
            ? static_cast<long long>(info.maximum - value) * 255LL
            : static_cast<long long>(value - info.minimum) * 255LL;
    long long out = denominator ? numerator / denominator : 0;
    if (out < 0) out = 0;
    if (out > 255) out = 255;
    return static_cast<uint8_t>(out);
}

struct Gains {
    int mouse = 100;
    int gamepad = 100;
};

bool readGains(const char* path, Gains* gains) {
    if (!path || !gains) return false;
    FILE* f = fopen(path, "r");
    if (!f) return false;
    int mouse = gains->mouse;
    int gamepad = gains->gamepad;
    char line[96];
    while (fgets(line, sizeof(line), f)) {
        int value = 0;
        if (sscanf(line, "mouse=%d", &value) == 1) mouse = value;
        else if (sscanf(line, "gamepad=%d", &value) == 1) gamepad = value;
    }
    fclose(f);
    if (mouse < 1) mouse = 1;
    if (mouse > 500) mouse = 500;
    if (gamepad < 1) gamepad = 1;
    if (gamepad > 500) gamepad = 500;
    bool changed = mouse != gains->mouse || gamepad != gains->gamepad;
    gains->mouse = mouse;
    gains->gamepad = gamepad;
    return changed;
}

// 配置文件通过原子替换更新。inode 未变化时不重复读取内容。
bool readGainsIfChanged(const char* path, Gains* gains, uint64_t* inodeStamp) {
    if (!path || !gains || !inodeStamp) return false;
    struct stat info{};
    if (stat(path, &info) != 0) return false;
    uint64_t stamp = static_cast<uint64_t>(info.st_ino);
    if (*inodeStamp != 0 && *inodeStamp == stamp) return false;

    Gains next = *gains;
    if (!readGains(path, &next)) return false;
    bool changed = next.mouse != gains->mouse || next.gamepad != gains->gamepad;
    *gains = next;
    *inodeStamp = stamp;
    return changed;
}

int createUinputClone(int sourceFd, const char* name) {
    if (sourceFd < 0) return -1;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;

    unsigned long evBits[8]{};
    unsigned long keyBits[(KEY_MAX / (sizeof(unsigned long) * 8)) + 2]{};
    unsigned long absBits[(ABS_MAX / (sizeof(unsigned long) * 8)) + 2]{};
    if (!getBits(sourceFd, 0, evBits)) {
        close(fd);
        return -1;
    }

    const int eventTypes[] = {EV_KEY, EV_ABS, EV_MSC};
    for (int type : eventTypes) {
        if (bitTest(evBits, type) && ioctl(fd, UI_SET_EVBIT, type) < 0) {
            int saved = errno;
            close(fd);
            errno = saved;
            return -1;
        }
    }

    if (bitTest(evBits, EV_KEY) && getBits(sourceFd, EV_KEY, keyBits)) {
        for (int code = 0; code <= KEY_MAX; ++code) {
            if (bitTest(keyBits, code)) (void)ioctl(fd, UI_SET_KEYBIT, code);
        }
    }
    if (bitTest(evBits, EV_MSC)) {
        unsigned long mscBits[2]{};
        if (getBits(sourceFd, EV_MSC, mscBits)) {
            for (int code = 0; code <= MSC_MAX; ++code) {
                if (bitTest(mscBits, code)) (void)ioctl(fd, UI_SET_MSCBIT, code);
            }
        }
    }
    if (bitTest(evBits, EV_ABS) && getBits(sourceFd, EV_ABS, absBits)) {
        for (int code = 0; code <= ABS_MAX; ++code) {
            if (!bitTest(absBits, code)) continue;
            input_absinfo info{};
            if (!axisInfo(sourceFd, code, &info)) continue;
            if (ioctl(fd, UI_SET_ABSBIT, code) < 0) continue;
            uinput_abs_setup absSetup{};
            absSetup.code = static_cast<__u16>(code);
            absSetup.absinfo = info;
            (void)ioctl(fd, UI_ABS_SETUP, &absSetup);
        }
    }

    input_id id{};
    getDeviceId(sourceFd, &id);
    uinput_setup setup{};
    setup.id = id;
    snprintf(setup.name, sizeof(setup.name), "%s", (name && name[0]) ? name : "Axon Input Gamepad");
    if (ioctl(fd, UI_SET_PHYS, "axon-input/virtual/gamepad") < 0) {
        // 部分内核不支持设置 phys，不影响输入代理。
    }
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

void destroyUinput(int* fd) {
    if (!fd || *fd < 0) return;
    (void)ioctl(*fd, UI_DEV_DESTROY);
    close(*fd);
    *fd = -1;
}

bool sendUinputEvent(int fd, const input_event& event) {
    if (fd < 0) return false;
    return write(fd, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

bool sendUinputEvents(int fd, const input_event* events, size_t count) {
    if (fd < 0 || !events || count == 0) return false;
    const size_t bytes = sizeof(input_event) * count;
    return write(fd, events, bytes) == static_cast<ssize_t>(bytes);
}

int mapAxisNative(const input_absinfo& info, int value, int gainPercent, double center) {
    int16_t normalized = mapAxis(info, value, gainPercent, center);
    double ratio = static_cast<double>(normalized) / 32767.0;
    double out = center;
    if (ratio >= 0.0) out += (static_cast<double>(info.maximum) - center) * ratio;
    else out += (center - static_cast<double>(info.minimum)) * ratio;
    long long rounded = llround(out);
    if (rounded < info.minimum) rounded = info.minimum;
    if (rounded > info.maximum) rounded = info.maximum;
    return static_cast<int>(rounded);
}

int createUhid(const char* name, const uint8_t* descriptor, size_t descriptorSize,
               uint16_t bus, uint32_t vendor, uint32_t product, uint32_t version) {
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    if (descriptorSize > HID_MAX_DESCRIPTOR_SIZE) {
        close(fd);
        errno = E2BIG;
        return -1;
    }
    uhid_event event{};
    event.type = UHID_CREATE2;
    snprintf(reinterpret_cast<char*>(event.u.create2.name), sizeof(event.u.create2.name), "%s", name);
    snprintf(reinterpret_cast<char*>(event.u.create2.phys), sizeof(event.u.create2.phys), "axon-input/virtual");
    snprintf(reinterpret_cast<char*>(event.u.create2.uniq), sizeof(event.u.create2.uniq), "axon-input");
    event.u.create2.rd_size = static_cast<uint16_t>(descriptorSize);
    event.u.create2.bus = bus;
    event.u.create2.vendor = vendor;
    event.u.create2.product = product;
    event.u.create2.version = version;
    memcpy(event.u.create2.rd_data, descriptor, descriptorSize);
    ssize_t written = write(fd, &event, sizeof(event));
    if (written != static_cast<ssize_t>(sizeof(event))) {
        int saved = errno;
        close(fd);
        errno = saved ? saved : EIO;
        return -1;
    }
    return fd;
}

bool sendUhidReport(int fd, const void* data, size_t size) {
    if (fd < 0 || !data || size == 0 || size > UHID_DATA_MAX) return false;
    uhid_event event{};
    event.type = UHID_INPUT2;
    event.u.input2.size = static_cast<uint16_t>(size);
    memcpy(event.u.input2.data, data, size);
    return write(fd, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

void destroyUhid(int* fd) {
    if (!fd || *fd < 0) return;
    uhid_event event{};
    event.type = UHID_DESTROY;
    (void)write(*fd, &event, sizeof(event));
    close(*fd);
    *fd = -1;
}

constexpr uint8_t kMouseDescriptor[] = {
    0x05, 0x01,       // 用途页：通用桌面
    0x09, 0x02,       // 用途：鼠标
    0xA1, 0x01,       // 集合：应用
    0x09, 0x01,       // 用途：指针
    0xA1, 0x00,       // 集合：物理
    0x05, 0x09,       // 用途页：按键
    0x19, 0x01,       // 用途最小值：1
    0x29, 0x05,       // 用途最大值：5
    0x15, 0x00,       // 逻辑最小值：0
    0x25, 0x01,       // 逻辑最大值：1
    0x95, 0x05,       // 报告数量：5
    0x75, 0x01,       // 报告位宽：1
    0x81, 0x02,       // 输入：Data,Var,Abs
    0x95, 0x03,       // 报告数量：3
    0x75, 0x01,       // 报告位宽：1
    0x81, 0x03,       // 输入：Const,Var,Abs
    0x05, 0x01,       // 用途页：通用桌面
    0x09, 0x30,       // 用途：X
    0x09, 0x31,       // 用途：Y
    0x16, 0x01, 0x80, // 逻辑最小值：-32767
    0x26, 0xFF, 0x7F, // 逻辑最大值：32767
    0x75, 0x10,       // 报告位宽：16
    0x95, 0x02,       // 报告数量：2
    0x81, 0x06,       // 输入：Data,Var,Rel
    0x09, 0x38,       // 用途：滚轮
    0x15, 0x81,       // 逻辑最小值：-127
    0x25, 0x7F,       // 逻辑最大值：127
    0x75, 0x08,       // 报告位宽：8
    0x95, 0x01,       // 报告数量：1
    0x81, 0x06,       // 输入：Data,Var,Rel
    0x05, 0x0C,       // 用途页：消费设备
    0x0A, 0x38, 0x02, // 用途：AC Pan
    0x15, 0x81,
    0x25, 0x7F,
    0x75, 0x08,
    0x95, 0x01,
    0x81, 0x06,
    0xC0,             // 结束集合
    0xC0              // 结束集合
};


constexpr uint8_t kGamepadDescriptor[] = {
    0x05, 0x01,       // 用途页：通用桌面
    0x09, 0x05,       // 用途：手柄
    0xA1, 0x01,       // 集合：应用
    0x05, 0x09,       // 用途页：按键
    0x19, 0x01,       // 用途最小值：1
    0x29, 0x10,       // 用途最大值：16
    0x15, 0x00,
    0x25, 0x01,
    0x75, 0x01,
    0x95, 0x10,
    0x81, 0x02,       // 16 个按键
    0x05, 0x01,
    0x09, 0x39,       // 方向帽
    0x15, 0x00,
    0x25, 0x07,
    0x35, 0x00,
    0x46, 0x3B, 0x01, // 物理最大值：315 度
    0x65, 0x14,       // 单位：度
    0x75, 0x04,
    0x95, 0x01,
    0x81, 0x42,       // 数据字段：Data,Var,Abs,Null
    0x65, 0x00,
    0x75, 0x04,
    0x95, 0x01,
    0x81, 0x03,       // 填充位
    0x05, 0x01,
    0x09, 0x30,       // X：左摇杆 X
    0x09, 0x31,       // Y：左摇杆 Y
    0x09, 0x32,       // Z：右摇杆 X
    0x09, 0x35,       // Rz：右摇杆 Y
    0x16, 0x01, 0x80,
    0x26, 0xFF, 0x7F,
    0x75, 0x10,
    0x95, 0x04,
    0x81, 0x02,
    0x05, 0x02,       // 用途页：模拟控制
    0x09, 0xC4,       // 油门对应 ABS_GAS / RTRIGGER
    0x09, 0xC5,       // 刹车对应 ABS_BRAKE / LTRIGGER
    0x15, 0x00,
    0x26, 0xFF, 0x00,
    0x75, 0x08,
    0x95, 0x02,
    0x81, 0x02,
    0xC0
};

#pragma pack(push, 1)
struct MouseReport {
    uint8_t buttons;
    int16_t x;
    int16_t y;
    int8_t wheel;
    int8_t hWheel;
};

struct GamepadReport {
    uint16_t buttons;
    uint8_t hat;
    int16_t lx;
    int16_t ly;
    int16_t rx;
    int16_t ry;
    uint8_t rt;
    uint8_t lt;
};
#pragma pack(pop)

static_assert(sizeof(MouseReport) == 7, "Mouse HID report size mismatch");
static_assert(sizeof(GamepadReport) == 13, "Gamepad HID report size mismatch");

struct MouseProxy {
    int fd = -1;
    int uhid = -1;
    char path[64]{};
    char name[128]{};
    MouseReport report{};
    int pendingX = 0;
    int pendingY = 0;
    int pendingWheel = 0;
    int pendingHWheel = 0;
    int telemetryX = 0;
    int telemetryY = 0;
    long long lastTelemetryMs = 0;
    int64_t residualX = 0; // 定点数，分母为 100。
    int64_t residualY = 0;
};

struct GamepadProxy {
    int fd = -1;
    int uhid = -1;
    int uinput = -1;
    bool useUinput = false;
    char path[64]{};
    char name[128]{};
    GamepadReport report{};
    input_absinfo leftX{};
    input_absinfo leftY{};
    input_absinfo rightX{};
    input_absinfo rightY{};
    double leftCenterX = 0.0;
    double leftCenterY = 0.0;
    double rightCenterX = 0.0;
    double rightCenterY = 0.0;
    input_absinfo triggerR{};
    input_absinfo triggerL{};
    int rightXCode = -1;
    int rightYCode = -1;
    int triggerRCode = -1;
    int triggerLCode = -1;
    bool triggerRRestAtMax = false;
    bool triggerLRestAtMax = false;
    uint8_t analogRt = 0;
    uint8_t analogLt = 0;
    bool digitalRt = false;
    bool digitalLt = false;
    bool hasStandardEast = false;
    bool hasStandardWest = false;
    bool hasButtonKeys = false; // 主循环优先处理实体按键节点。
    int hatX = 0;
    int hatY = 0;
    bool dpadUp = false;
    bool dpadDown = false;
    bool dpadLeft = false;
    bool dpadRight = false;

    // 常见手柄会把右摇杆放在 RX/RY 或 Z/RZ。
    // 逐轴判断后再放大，避免倍率打到错误轴或扳机。
    bool gainAxisEnabled[ABS_MAX + 1]{};
    input_absinfo gainAxisInfo[ABS_MAX + 1]{};
    double gainAxisCenter[ABS_MAX + 1]{};

    // 右摇杆原始标准化值。高倍率视角加速使用它持续生成相对视角输入。
    int16_t viewX = 0;
    int16_t viewY = 0;

    GamepadReport lastTelemetry{};
    bool telemetryInitialized = false;
    GamepadReport lastOutput{};
    bool outputInitialized = false;
};

struct ViewPointer {
    int uinput = -1;
    int uhid = -1;
    long long lastTickMs = 0;
    double residualX = 0.0;
    double residualY = 0.0;
};

void closeMouse(MouseProxy* p) {
    if (!p) return;
    if (p->fd >= 0) {
        int zero = 0;
        (void)ioctl(p->fd, EVIOCGRAB, zero);
        close(p->fd);
        p->fd = -1;
    }
    destroyUhid(&p->uhid);
    *p = MouseProxy{};
}

void closeGamepad(GamepadProxy* p) {
    if (!p) return;
    if (p->fd >= 0) {
        int zero = 0;
        (void)ioctl(p->fd, EVIOCGRAB, zero);
        close(p->fd);
        p->fd = -1;
    }
    destroyUhid(&p->uhid);
    destroyUinput(&p->uinput);
    *p = GamepadProxy{};
}

int scaleMouseDelta(int delta, int gainPercent, int64_t* residual) {
    if (!residual) return delta;
    if (gainPercent == 100) {
        *residual = 0;
        return delta;
    }
    int64_t scaled = static_cast<int64_t>(delta) * gainPercent + *residual;
    int out = static_cast<int>(scaled / 100);
    *residual = scaled - static_cast<int64_t>(out) * 100;
    return out;
}

int16_t clamp16(int value) {
    if (value > 32767) return 32767;
    if (value < -32767) return -32767;
    return static_cast<int16_t>(value);
}

int8_t clamp8(int value) {
    if (value > 127) return 127;
    if (value < -127) return -127;
    return static_cast<int8_t>(value);
}

int createUinputViewPointer() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    if (ioctl(fd, UI_SET_EVBIT, EV_REL) < 0
            || ioctl(fd, UI_SET_RELBIT, REL_X) < 0
            || ioctl(fd, UI_SET_RELBIT, REL_Y) < 0
            || ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0
            || ioctl(fd, UI_SET_KEYBIT, BTN_LEFT) < 0
            || ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    uinput_setup setup{};
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x4B44;
    setup.id.product = 0x1003;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "%s", "Axon Input View Mouse");
    (void)ioctl(fd, UI_SET_PHYS, "axon-input/virtual/view");
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

bool ensureViewPointer(ViewPointer* view) {
    if (!view) return false;
    if (view->uinput >= 0 || view->uhid >= 0) return true;

    view->uinput = createUinputViewPointer();
    if (view->uinput >= 0) {
        printf("STATUS view-ready backend=uinput\n");
        fflush(stdout);
        return true;
    }

    view->uhid = createUhid("Axon Input Virtual View Mouse", kMouseDescriptor,
                            sizeof(kMouseDescriptor), BUS_VIRTUAL, 0x4B44, 0x1003, 1);
    if (view->uhid >= 0) {
        printf("STATUS view-ready backend=uhid\n");
        fflush(stdout);
        return true;
    }
    return false;
}

void closeViewPointer(ViewPointer* view) {
    if (!view) return;
    destroyUinput(&view->uinput);
    destroyUhid(&view->uhid);
    *view = ViewPointer{};
}

bool sendViewDelta(ViewPointer* view, int dx, int dy) {
    if (!view || (dx == 0 && dy == 0)) return true;
    if (view->uinput >= 0) {
        input_event events[3]{};
        size_t count = 0;
        if (dx != 0) {
            events[count].type = EV_REL;
            events[count].code = REL_X;
            events[count].value = dx;
            ++count;
        }
        if (dy != 0) {
            events[count].type = EV_REL;
            events[count].code = REL_Y;
            events[count].value = dy;
            ++count;
        }
        events[count].type = EV_SYN;
        events[count].code = SYN_REPORT;
        ++count;
        return sendUinputEvents(view->uinput, events, count);
    }
    if (view->uhid >= 0) {
        MouseReport report{};
        report.x = clamp16(dx);
        report.y = clamp16(dy);
        return sendUhidReport(view->uhid, &report, sizeof(report));
    }
    return false;
}

uint8_t mouseButtonMaskForCode(int code) {
    switch (code) {
        case BTN_LEFT: return 1u << 0;
        case BTN_RIGHT: return 1u << 1;
        case BTN_MIDDLE: return 1u << 2;
        case BTN_SIDE: return 1u << 3;
        case BTN_EXTRA: return 1u << 4;
        default: return 0;
    }
}

int gamepadButtonIndex(int code, bool hasStandardEast, bool hasStandardWest) {
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
        default: return -1;
    }
}

uint8_t computeHat(const GamepadProxy& p) {
    int x = p.hatX;
    int y = p.hatY;
    if (p.dpadLeft) x = -1;
    else if (p.dpadRight) x = 1;
    if (p.dpadUp) y = -1;
    else if (p.dpadDown) y = 1;
    if (x == 0 && y < 0) return 0;
    if (x > 0 && y < 0) return 1;
    if (x > 0 && y == 0) return 2;
    if (x > 0 && y > 0) return 3;
    if (x == 0 && y > 0) return 4;
    if (x < 0 && y > 0) return 5;
    if (x < 0 && y == 0) return 6;
    if (x < 0 && y < 0) return 7;
    return 8;
}

void setDpadButton(GamepadProxy* p, int code, bool pressed) {
    if (!p) return;
    if (code == BTN_DPAD_UP) p->dpadUp = pressed;
    else if (code == BTN_DPAD_DOWN) p->dpadDown = pressed;
    else if (code == BTN_DPAD_LEFT) p->dpadLeft = pressed;
    else if (code == BTN_DPAD_RIGHT) p->dpadRight = pressed;
    p->report.hat = computeHat(*p);
}

bool configureGainAxes(int fd, GamepadProxy* p) {
    if (!p) return false;
    bool any = false;
    const int candidates[] = {ABS_RX, ABS_RY, ABS_Z, ABS_RZ};
    for (int code : candidates) {
        if (code < 0 || code > ABS_MAX) continue;
        input_absinfo info{};
        if (!axisInfo(fd, code, &info)) continue;

        // 扳机一般静止在量程边缘，摇杆一般静止在中点附近。
        if (!axisLooksCentered(info)) continue;

        p->gainAxisEnabled[code] = true;
        p->gainAxisInfo[code] = info;
        p->gainAxisCenter[code] = calibratedCenter(info);
        any = true;
    }
    return any;
}

bool pickGamepadAxes(int fd, GamepadProxy* p) {
    if (!p) return false;
    if (!axisInfo(fd, ABS_X, &p->leftX) || !axisInfo(fd, ABS_Y, &p->leftY)) return false;

    input_absinfo z{}, rz{}, rx{}, ry{};
    bool hasZ = axisInfo(fd, ABS_Z, &z);
    bool hasRz = axisInfo(fd, ABS_RZ, &rz);
    bool hasRx = axisInfo(fd, ABS_RX, &rx);
    bool hasRy = axisInfo(fd, ABS_RY, &ry);

    bool rrSignedPair = hasRx && hasRy && axisCrossesZero(rx) && axisCrossesZero(ry);
    bool zrTriggerPair = hasZ && hasRz && axisIsOneSided(z) && axisIsOneSided(rz);
    int zrScore = (hasZ && hasRz) ? axisCenterScore(z) + axisCenterScore(rz) : 1000000;
    int rrScore = (hasRx && hasRy) ? axisCenterScore(rx) + axisCenterScore(ry) : 1000000;
    bool zrCentered = hasZ && hasRz && axisLooksCentered(z) && axisLooksCentered(rz);
    bool rrCentered = hasRx && hasRy && axisLooksCentered(rx) && axisLooksCentered(ry);
    if (rrSignedPair && zrTriggerPair) {
        p->rightXCode = ABS_RX;
        p->rightYCode = ABS_RY;
        p->rightX = rx;
        p->rightY = ry;
    } else if (rrCentered && (!zrCentered || rrScore <= zrScore)) {
        p->rightXCode = ABS_RX;
        p->rightYCode = ABS_RY;
        p->rightX = rx;
        p->rightY = ry;
    } else if (zrCentered) {
        p->rightXCode = ABS_Z;
        p->rightYCode = ABS_RZ;
        p->rightX = z;
        p->rightY = rz;
    } else if (hasRx && hasRy && rrScore < zrScore) {
        p->rightXCode = ABS_RX;
        p->rightYCode = ABS_RY;
        p->rightX = rx;
        p->rightY = ry;
    } else if (hasZ && hasRz) {
        p->rightXCode = ABS_Z;
        p->rightYCode = ABS_RZ;
        p->rightX = z;
        p->rightY = rz;
    } else {
        return false;
    }

    input_absinfo info{};
    if (axisInfo(fd, ABS_BRAKE, &info)) {
        p->triggerLCode = ABS_BRAKE;
        p->triggerL = info;
    }
    if (axisInfo(fd, ABS_GAS, &info)) {
        p->triggerRCode = ABS_GAS;
        p->triggerR = info;
    }

    if (p->triggerLCode < 0 && hasZ && p->rightXCode != ABS_Z && p->rightYCode != ABS_Z) {
        p->triggerLCode = ABS_Z;
        p->triggerL = z;
    }
    if (p->triggerRCode < 0 && hasRz && p->rightXCode != ABS_RZ && p->rightYCode != ABS_RZ) {
        p->triggerRCode = ABS_RZ;
        p->triggerR = rz;
    }
    if (p->triggerLCode < 0 && hasRx && p->rightXCode != ABS_RX && p->rightYCode != ABS_RX) {
        p->triggerLCode = ABS_RX;
        p->triggerL = rx;
    }
    if (p->triggerRCode < 0 && hasRy && p->rightXCode != ABS_RY && p->rightYCode != ABS_RY) {
        p->triggerRCode = ABS_RY;
        p->triggerR = ry;
    }

    if (p->triggerLCode >= 0) {
        p->triggerLRestAtMax = triggerRestAtMax(p->triggerL);
        p->analogLt = mapTrigger(p->triggerL, p->triggerL.value, p->triggerLRestAtMax);
    }
    if (p->triggerRCode >= 0) {
        p->triggerRRestAtMax = triggerRestAtMax(p->triggerR);
        p->analogRt = mapTrigger(p->triggerR, p->triggerR.value, p->triggerRRestAtMax);
    }
    p->leftCenterX = calibratedCenter(p->leftX);
    p->leftCenterY = calibratedCenter(p->leftY);
    p->rightCenterX = calibratedCenter(p->rightX);
    p->rightCenterY = calibratedCenter(p->rightY);
    return true;
}


bool attachMouse(const char* path, MouseProxy* p) {
    if (!path || !p) return false;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    char name[128]{};
    getDeviceName(fd, name, sizeof(name));
    if (deviceIsAxonVirtual(fd) || !isMouseDevice(fd)) {
        close(fd);
        return false;
    }
    int uhid = createUhid("Axon Input Virtual Mouse", kMouseDescriptor,
                         sizeof(kMouseDescriptor), BUS_VIRTUAL, 0x4B44, 0x0001, 1);
    if (uhid < 0) {
        close(fd);
        return false;
    }
    int one = 1;
    if (ioctl(fd, EVIOCGRAB, one) < 0) {
        destroyUhid(&uhid);
        close(fd);
        return false;
    }
    *p = MouseProxy{};
    p->fd = fd;
    p->uhid = uhid;
    snprintf(p->path, sizeof(p->path), "%s", path);
    snprintf(p->name, sizeof(p->name), "%s", name[0] ? name : "mouse");
    p->lastTelemetryMs = nowMs();
    sendUhidReport(p->uhid, &p->report, sizeof(p->report));
    printf("STATUS mouse-ready %s %s\n", p->path, p->name);
    fflush(stdout);
    return true;
}

bool attachGamepad(const char* path, GamepadProxy* p) {
    if (!path || !p) return false;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    char name[128]{};
    getDeviceName(fd, name, sizeof(name));
    if (deviceIsAxonVirtual(fd) || !isGamepadDevice(fd)) {
        close(fd);
        return false;
    }
    GamepadProxy candidate{};
    candidate.fd = fd;
    candidate.uhid = -1;
    bool hasTelemetryAxes = pickGamepadAxes(fd, &candidate);
    bool hasGainAxes = configureGainAxes(fd, &candidate);
    if (!hasTelemetryAxes && !hasGainAxes) {
        close(fd);
        return false;
    }
    unsigned long keyBits[16]{};
    if (getBits(fd, EV_KEY, keyBits)) {
        candidate.hasStandardEast = bitTest(keyBits, BTN_EAST);
        candidate.hasStandardWest = bitTest(keyBits, BTN_WEST);
        const int fastKeys[] = {
            BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
            BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR,
            BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT
        };
        for (int code : fastKeys) {
            if (bitTest(keyBits, code)) {
                candidate.hasButtonKeys = true;
                break;
            }
        }
    }
    input_id physicalId{};
    getDeviceId(fd, &physicalId);
    // uinput 直接克隆物理 evdev 设备，保留原始键码、轴码、VID/PID 和设备名。
    // 这样游戏看到的是同类型手柄，而不是另一套通用 HID 映射。
    int uinput = createUinputClone(fd, name);
    int uhid = -1;
    if (uinput < 0) {
        uhid = createUhid("Axon Input Virtual Gamepad", kGamepadDescriptor,
                          sizeof(kGamepadDescriptor), BUS_VIRTUAL, 0x4B44, 0x0002, 1);
        if (uhid < 0) {
            printf("STATUS gamepad-virtual-failed uinput_errno=%d uhid_errno=%d\n", errno, errno);
            fflush(stdout);
            close(fd);
            return false;
        }
    }
    int one = 1;
    if (ioctl(fd, EVIOCGRAB, one) < 0) {
        printf("STATUS gamepad-grab-failed errno=%d\n", errno);
        fflush(stdout);
        destroyUinput(&uinput);
        destroyUhid(&uhid);
        close(fd);
        return false;
    }
    *p = candidate;
    p->uinput = uinput;
    p->useUinput = uinput >= 0;
    p->uhid = uhid;
    snprintf(p->path, sizeof(p->path), "%s", path);
    snprintf(p->name, sizeof(p->name), "%s", name[0] ? name : "gamepad");
    p->report.hat = 8;
    p->report.lx = 0;
    p->report.ly = 0;
    p->report.rx = mapAxis(p->rightX, p->rightX.value, 100, p->rightCenterX);
    p->report.ry = mapAxis(p->rightY, p->rightY.value, 100, p->rightCenterY);
    p->viewX = p->report.rx;
    p->viewY = p->report.ry;
    p->report.rt = p->analogRt;
    p->report.lt = p->analogLt;
    if (!p->useUinput) sendUhidReport(p->uhid, &p->report, sizeof(p->report));
    int gainAxes = 0;
    for (int code = 0; code <= ABS_MAX; ++code) {
        if (p->gainAxisEnabled[code]) ++gainAxes;
    }
    printf("STATUS gamepad-ready %s %s backend=%s vid=%04x pid=%04x bus=%04x gain_axes=%d\n",
           p->path, p->name, p->useUinput ? "uinput" : "uhid",
           static_cast<unsigned>(physicalId.vendor),
           static_cast<unsigned>(physicalId.product),
           static_cast<unsigned>(physicalId.bustype),
           gainAxes);
    fflush(stdout);
    return true;
}

bool gamepadPathActive(const GamepadProxy* gamepads, int count, const char* path) {
    if (!gamepads || !path) return false;
    for (int i = 0; i < count; ++i) {
        if (gamepads[i].fd >= 0 && strcmp(gamepads[i].path, path) == 0) return true;
    }
    return false;
}

int activeGamepadCount(const GamepadProxy* gamepads, int count) {
    if (!gamepads) return 0;
    int active = 0;
    for (int i = 0; i < count; ++i) {
        if (gamepads[i].fd >= 0) ++active;
    }
    return active;
}

int firstFreeGamepad(GamepadProxy* gamepads, int count) {
    if (!gamepads) return -1;
    for (int i = 0; i < count; ++i) {
        if (gamepads[i].fd < 0) return i;
    }
    return -1;
}

void scanDevices(MouseProxy* mouse, GamepadProxy* gamepads, int gamepadCount) {
    if (mouse && mouse->fd < 0) {
        for (int i = 0; i < kMaxEvents && mouse->fd < 0; ++i) {
            char path[64];
            snprintf(path, sizeof(path), "/dev/input/event%d", i);
            if (access(path, R_OK) == 0) (void)attachMouse(path, mouse);
        }
    }

    // 一个物理手柄可能暴露多个 evdev 节点。
    // 只接管一个节点时，未接管节点仍会把原始摇杆直接送进游戏。
    if (gamepads && gamepadCount > 0) {
        for (int i = 0; i < kMaxEvents; ++i) {
            int slot = firstFreeGamepad(gamepads, gamepadCount);
            if (slot < 0) break;

            char path[64];
            snprintf(path, sizeof(path), "/dev/input/event%d", i);
            if (access(path, R_OK) != 0 || gamepadPathActive(gamepads, gamepadCount, path)) continue;
            (void)attachGamepad(path, &gamepads[slot]);
        }
    }
}

void emitMouseTelemetry(MouseProxy* p) {
    if (!p) return;
    long long now = nowMs();
    if ((p->telemetryX == 0 && p->telemetryY == 0)
            || now - p->lastTelemetryMs < kMotionTelemetryIntervalMs) return;
    printf("MOTION %d %d\n", p->telemetryX, p->telemetryY);
    fflush(stdout);
    p->telemetryX = 0;
    p->telemetryY = 0;
    p->lastTelemetryMs = now;
}

bool processMouseEvent(MouseProxy* p, const input_event& ev, const Gains& gains) {
    if (!p || p->fd < 0 || p->uhid < 0) return false;
    if (ev.type == EV_REL) {
        if (ev.code == REL_X) {
            p->pendingX += ev.value;
            p->telemetryX += ev.value;
        } else if (ev.code == REL_Y) {
            p->pendingY += ev.value;
            p->telemetryY += ev.value;
        } else if (ev.code == REL_WHEEL) {
            p->pendingWheel += ev.value;
        } else if (ev.code == REL_HWHEEL) {
            p->pendingHWheel += ev.value;
        }
        return true;
    }
    if (ev.type == EV_KEY) {
        uint8_t bit = mouseButtonMaskForCode(ev.code);
        if (bit) {
            bool pressed = ev.value != 0;
            uint8_t old = p->report.buttons;
            if (pressed) p->report.buttons |= bit;
            else p->report.buttons &= static_cast<uint8_t>(~bit);
            if (old != p->report.buttons) {
                printf("BUTTONS %u\n", static_cast<unsigned>(p->report.buttons));
                fflush(stdout);
            }
        }
        return true;
    }
    if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
        p->report.x = clamp16(scaleMouseDelta(p->pendingX, gains.mouse, &p->residualX));
        p->report.y = clamp16(scaleMouseDelta(p->pendingY, gains.mouse, &p->residualY));
        p->report.wheel = clamp8(p->pendingWheel);
        p->report.hWheel = clamp8(p->pendingHWheel);
        bool ok = sendUhidReport(p->uhid, &p->report, sizeof(p->report));
        p->pendingX = p->pendingY = p->pendingWheel = p->pendingHWheel = 0;
        p->report.x = p->report.y = 0;
        p->report.wheel = p->report.hWheel = 0;
        emitMouseTelemetry(p);
        return ok;
    }
    return true;
}


double viewAxisValue(int16_t value) {
    double x = static_cast<double>(value) / 32767.0;
    if (x > 1.0) x = 1.0;
    if (x < -1.0) x = -1.0;
    double sign = x < 0.0 ? -1.0 : 1.0;
    double mag = fabs(x);
    if (mag <= kViewDeadzone) return 0.0;
    mag = (mag - kViewDeadzone) / (1.0 - kViewDeadzone);
    // 轻推区域保留精度，推杆越大加速越明显。
    mag = pow(mag, 1.12);
    return sign * mag;
}

void strongestViewStick(const GamepadProxy* gamepads, int count, double* outX, double* outY) {
    if (!outX || !outY) return;
    *outX = 0.0;
    *outY = 0.0;
    double best = 0.0;
    for (int i = 0; gamepads && i < count; ++i) {
        const GamepadProxy& p = gamepads[i];
        if (p.fd < 0 || p.rightXCode < 0 || p.rightYCode < 0) continue;
        double x = viewAxisValue(p.viewX);
        double y = viewAxisValue(p.viewY);
        double strength = x * x + y * y;
        if (strength > best) {
            best = strength;
            *outX = x;
            *outY = y;
        }
    }
}

void emitViewMotion(ViewPointer* view, const GamepadProxy* gamepads, int count,
                    const Gains& gains, long long now) {
    if (!view) return;
    if (gains.gamepad <= 100) {
        view->lastTickMs = now;
        view->residualX = 0.0;
        view->residualY = 0.0;
        return;
    }

    double x = 0.0;
    double y = 0.0;
    strongestViewStick(gamepads, count, &x, &y);
    if (x == 0.0 && y == 0.0) {
        view->lastTickMs = now;
        view->residualX = 0.0;
        view->residualY = 0.0;
        return;
    }
    if (!ensureViewPointer(view)) return;

    long long dt = view->lastTickMs > 0 ? now - view->lastTickMs : kViewTickMs;
    if (dt < 1) return;
    if (dt > 24) dt = 24;
    view->lastTickMs = now;

    // 100% 完全使用原生手柄输入。超过 100% 的部分转成相对鼠标移动，
    // 这样不受标准摇杆 [-1, 1] 上限限制，游戏能得到额外的视角转动量。
    const double extraGain = static_cast<double>(gains.gamepad - 100) / 100.0;
    const double scale = kViewCountsPerSecond * extraGain * static_cast<double>(dt) / 1000.0;
    double sx = x * scale + view->residualX;
    double sy = y * scale + view->residualY;
    int dx = static_cast<int>(sx);
    int dy = static_cast<int>(sy);
    view->residualX = sx - dx;
    view->residualY = sy - dy;
    if (dx == 0 && dy == 0) return;

    if (!sendViewDelta(view, dx, dy)) {
        printf("STATUS view-disconnected\n");
        fflush(stdout);
        closeViewPointer(view);
    }
}

void emitGamepadTelemetry(GamepadProxy* p) {
    if (!p) return;
    if (p->telemetryInitialized && memcmp(&p->report, &p->lastTelemetry, sizeof(GamepadReport)) == 0) return;
    auto axis1000 = [](int16_t value) -> int {
        int out = static_cast<int>((static_cast<long long>(value) * 1000LL) / 32767LL);
        if (out > 1000) out = 1000;
        if (out < -1000) out = -1000;
        return out;
    };
    int lt = static_cast<int>((static_cast<unsigned>(p->report.lt) * 1000U) / 255U);
    int rt = static_cast<int>((static_cast<unsigned>(p->report.rt) * 1000U) / 255U);
    printf("GAMEPAD %d %d %d %d %d %d %u\n",
           axis1000(p->report.lx), axis1000(p->report.ly),
           axis1000(p->report.rx), axis1000(p->report.ry),
           lt, rt, static_cast<unsigned>(p->report.buttons));
    fflush(stdout);
    p->lastTelemetry = p->report;
    p->telemetryInitialized = true;
}

bool sendGamepadUhidIfChanged(GamepadProxy* p) {
    if (!p || p->uhid < 0) return false;
    if (p->outputInitialized
            && memcmp(&p->report, &p->lastOutput, sizeof(GamepadReport)) == 0) return true;
    if (!sendUhidReport(p->uhid, &p->report, sizeof(p->report))) return false;
    p->lastOutput = p->report;
    p->outputInitialized = true;
    return true;
}

bool processGamepadEvent(GamepadProxy* p, const input_event& ev, const Gains& gains) {
    if (!p || p->fd < 0 || (p->uinput < 0 && p->uhid < 0)) return false;

    input_event forwarded = ev;
    if (ev.type == EV_KEY) {
        int idx = gamepadButtonIndex(ev.code, p->hasStandardEast, p->hasStandardWest);
        if (idx >= 0 && idx < 16) {
            uint16_t bit = static_cast<uint16_t>(1u << idx);
            bool pressed = ev.value != 0;
            if (pressed) p->report.buttons |= bit;
            else p->report.buttons &= static_cast<uint16_t>(~bit);
            if (ev.code == BTN_TL2) p->digitalLt = pressed;
            else if (ev.code == BTN_TR2) p->digitalRt = pressed;
            p->report.lt = p->digitalLt ? 255 : p->analogLt;
            p->report.rt = p->digitalRt ? 255 : p->analogRt;
        } else if (ev.code == BTN_DPAD_UP || ev.code == BTN_DPAD_DOWN
                || ev.code == BTN_DPAD_LEFT || ev.code == BTN_DPAD_RIGHT) {
            setDpadButton(p, ev.code, ev.value != 0);
        }
    } else if (ev.type == EV_ABS) {
        if (ev.code == ABS_X) p->report.lx = mapAxis(p->leftX, ev.value, 100, p->leftCenterX);
        else if (ev.code == ABS_Y) p->report.ly = mapAxis(p->leftY, ev.value, 100, p->leftCenterY);
        else if (ev.code == p->rightXCode) {
            p->viewX = mapAxis(p->rightX, ev.value, 100, p->rightCenterX);
            int axisGain = gains.gamepad > 100 ? 100 : gains.gamepad;
            p->report.rx = mapAxis(p->rightX, ev.value, axisGain, p->rightCenterX);
        } else if (ev.code == p->rightYCode) {
            p->viewY = mapAxis(p->rightY, ev.value, 100, p->rightCenterY);
            int axisGain = gains.gamepad > 100 ? 100 : gains.gamepad;
            p->report.ry = mapAxis(p->rightY, ev.value, axisGain, p->rightCenterY);
        } else if (ev.code == ABS_HAT0X) {
            p->hatX = ev.value < 0 ? -1 : (ev.value > 0 ? 1 : 0);
            p->report.hat = computeHat(*p);
        } else if (ev.code == ABS_HAT0Y) {
            p->hatY = ev.value < 0 ? -1 : (ev.value > 0 ? 1 : 0);
            p->report.hat = computeHat(*p);
        } else if (ev.code == p->triggerRCode) {
            p->analogRt = mapTrigger(p->triggerR, ev.value, p->triggerRRestAtMax);
            p->report.rt = p->digitalRt ? 255 : p->analogRt;
        } else if (ev.code == p->triggerLCode) {
            p->analogLt = mapTrigger(p->triggerL, ev.value, p->triggerLRestAtMax);
            p->report.lt = p->digitalLt ? 255 : p->analogLt;
        }
    }

    if (p->useUinput) {
        if (ev.type == EV_ABS && gains.gamepad <= 100) {
            if (ev.code == p->rightXCode) {
                forwarded.value = mapAxisNative(p->rightX, ev.value, gains.gamepad, p->rightCenterX);
            } else if (ev.code == p->rightYCode) {
                forwarded.value = mapAxisNative(p->rightY, ev.value, gains.gamepad, p->rightCenterY);
            }
        }
        if (!sendUinputEvent(p->uinput, forwarded)) return false;
        if (ev.type == EV_KEY) {
            // 按键立即提交，不等待设备稍后的 SYN_REPORT。
            input_event sync{};
            sync.type = EV_SYN;
            sync.code = SYN_REPORT;
            if (!sendUinputEvent(p->uinput, sync)) return false;
        }
        if (ev.type == EV_SYN && ev.code == SYN_REPORT) emitGamepadTelemetry(p);
        return true;
    }

    if (ev.type == EV_KEY && !sendGamepadUhidIfChanged(p)) return false;
    if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
        bool ok = sendGamepadUhidIfChanged(p);
        emitGamepadTelemetry(p);
        return ok;
    }
    return true;
}

bool readAndProcessMouse(MouseProxy* p, const Gains& gains) {
    input_event events[32];
    for (int batch = 0; batch < kMaxReadBatchesPerWake; ++batch) {
        ssize_t n = read(p->fd, events, sizeof(events));
        if (n > 0) {
            size_t count = static_cast<size_t>(n) / sizeof(input_event);
            for (size_t i = 0; i < count; ++i) {
                if (!processMouseEvent(p, events[i], gains)) return false;
            }
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        return n != 0;
    }
    return true;
}

bool readAndProcessGamepad(GamepadProxy* p, const Gains& gains) {
    input_event events[32];
    for (int batch = 0; batch < kMaxReadBatchesPerWake; ++batch) {
        ssize_t n = read(p->fd, events, sizeof(events));
        if (n > 0) {
            size_t count = static_cast<size_t>(n) / sizeof(input_event);
            for (size_t i = 0; i < count; ++i) {
                if (!processGamepadEvent(p, events[i], gains)) return false;
            }
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        return n != 0;
    }
    return true;
}

void drainUhid(int fd) {
    if (fd < 0) return;
    uhid_event event{};
    while (read(fd, &event, sizeof(event)) > 0) {
        // 忽略输出和功能报告，只处理输入。
    }
}

bool probeUinput() {
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    close(fd);
    return true;
}

bool probeUhid() {
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    close(fd);
    return true;
}

} // 命名空间

int main(int argc, char** argv) {
    const char* gainFile = nullptr;
    bool probeOnly = false;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--gain-file") == 0 && i + 1 < argc) gainFile = argv[++i];
        else if (strcmp(argv[i], "--probe") == 0) probeOnly = true;
    }

    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    setvbuf(stdout, nullptr, _IOLBF, 0);

    bool canUinput = probeUinput();
    int uinputError = canUinput ? 0 : errno;
    bool canUhid = probeUhid();
    int uhidError = canUhid ? 0 : errno;
    if (!canUinput && !canUhid) {
        printf("ERROR input-backend-open uinput_errno=%d uhid_errno=%d\n", uinputError, uhidError);
        return 12;
    }
    if (probeOnly) {
        printf("OK input-backend uinput=%d uhid=%d\n", canUinput ? 1 : 0, canUhid ? 1 : 0);
        return 0;
    }
    if (!gainFile) {
        printf("ERROR missing-gain-file\n");
        return 2;
    }

    Gains gains{};
    uint64_t gainInode = 0;
    (void)readGainsIfChanged(gainFile, &gains, &gainInode);
    printf("STATUS starting mouse=%d gamepad=%d\n", gains.mouse, gains.gamepad);

    MouseProxy mouse{};
    mouse.fd = -1;
    mouse.uhid = -1;
    ViewPointer view{};
    view.uinput = -1;
    view.uhid = -1;
    GamepadProxy gamepads[kMaxGamepads]{};
    for (int i = 0; i < kMaxGamepads; ++i) {
        gamepads[i].fd = -1;
        gamepads[i].uhid = -1;
        gamepads[i].uinput = -1;
    }

    long long lastScan = 0;
    long long lastConfig = 0;
    long long lastHeartbeat = 0;
    while (!gStop) {
        long long now = nowMs();
        if (now - lastHeartbeat >= 1000) {
            printf("PING\n");
            fflush(stdout);
            lastHeartbeat = now;
        }
        if (now - lastConfig >= kConfigIntervalMs) {
            if (readGainsIfChanged(gainFile, &gains, &gainInode)) {
                printf("STATUS gain mouse=%d gamepad=%d\n", gains.mouse, gains.gamepad);
            }
            lastConfig = now;
        }
        if (now - lastScan >= kScanIntervalMs) {
            scanDevices(&mouse, gamepads, kMaxGamepads);
            if (mouse.fd < 0 && activeGamepadCount(gamepads, kMaxGamepads) == 0) {
                printf("STATUS waiting-device\n");
            }
            lastScan = now;
        }

        pollfd fds[3 + kMaxGamepads * 2]{};
        int count = 0;
        int mouseIndex = -1;
        int mouseUhidIndex = -1;
        int viewUhidIndex = -1;
        int gamepadIndex[kMaxGamepads];
        int gamepadUhidIndex[kMaxGamepads];
        for (int i = 0; i < kMaxGamepads; ++i) {
            gamepadIndex[i] = -1;
            gamepadUhidIndex[i] = -1;
        }
        if (mouse.fd >= 0) {
            mouseIndex = count;
            fds[count++] = {mouse.fd, POLLIN | POLLERR | POLLHUP, 0};
        }
        for (int i = 0; i < kMaxGamepads; ++i) {
            if (gamepads[i].fd < 0) continue;
            gamepadIndex[i] = count;
            fds[count++] = {gamepads[i].fd, POLLIN | POLLERR | POLLHUP, 0};
        }
        if (mouse.uhid >= 0) {
            mouseUhidIndex = count;
            fds[count++] = {mouse.uhid, POLLIN | POLLERR | POLLHUP, 0};
        }
        if (view.uhid >= 0) {
            viewUhidIndex = count;
            fds[count++] = {view.uhid, POLLIN | POLLERR | POLLHUP, 0};
        }
        for (int i = 0; i < kMaxGamepads; ++i) {
            if (gamepads[i].uhid < 0) continue;
            gamepadUhidIndex[i] = count;
            fds[count++] = {gamepads[i].uhid, POLLIN | POLLERR | POLLHUP, 0};
        }

        int pollTimeout = gains.gamepad > 100 ? kViewTickMs : 50;
        int result = poll(fds, count, pollTimeout);
        if (result < 0) {
            if (errno == EINTR) continue;
            printf("ERROR poll errno=%d\n", errno);
            break;
        }
        if (mouseIndex >= 0 && fds[mouseIndex].revents) {
            if ((fds[mouseIndex].revents & (POLLERR | POLLHUP)) || !readAndProcessMouse(&mouse, gains)) {
                printf("STATUS mouse-disconnected\n");
                closeMouse(&mouse);
            }
        }
        // 先处理带实体按键的节点，再处理纯摇杆/轴节点。
        for (int priority = 0; priority < 2; ++priority) {
            for (int i = 0; i < kMaxGamepads; ++i) {
                int index = gamepadIndex[i];
                if (index < 0 || !fds[index].revents) continue;
                bool buttonNode = gamepads[i].hasButtonKeys;
                if ((priority == 0) != buttonNode) continue;
                if ((fds[index].revents & (POLLERR | POLLHUP))
                        || !readAndProcessGamepad(&gamepads[i], gains)) {
                    printf("STATUS gamepad-disconnected %s\n", gamepads[i].path);
                    closeGamepad(&gamepads[i]);
                }
            }
        }
        if (mouseUhidIndex >= 0 && (fds[mouseUhidIndex].revents & POLLIN)) drainUhid(mouse.uhid);
        if (viewUhidIndex >= 0 && (fds[viewUhidIndex].revents & POLLIN)) drainUhid(view.uhid);
        for (int i = 0; i < kMaxGamepads; ++i) {
            int index = gamepadUhidIndex[i];
            if (index >= 0 && (fds[index].revents & POLLIN)) drainUhid(gamepads[i].uhid);
        }
        emitMouseTelemetry(&mouse);
        emitViewMotion(&view, gamepads, kMaxGamepads, gains, nowMs());
    }

    closeViewPointer(&view);
    closeMouse(&mouse);
    for (int i = 0; i < kMaxGamepads; ++i) closeGamepad(&gamepads[i]);
    printf("STATUS stopped\n");
    return 0;
}
