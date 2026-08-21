// 灵敏度超频核心：读取物理 evdev，按倍率处理移动/摇杆，再通过 UHID 输出虚拟设备。
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uhid.h>
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
constexpr int kScanIntervalMs = 900;
constexpr int kConfigIntervalMs = 80;
constexpr int kMotionTelemetryIntervalMs = 8; // <= 125 Hz to keep stdout cheap.
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

bool nameIsVirtual(const char* name) {
    return name && strstr(name, kVirtualPrefix) != nullptr;
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
    return balance * 100 >= range * 35; // both sides have at least ~35% of full range.
}

int16_t mapAxis(const input_absinfo& info, int value, int gainPercent) {
    const double center = (static_cast<double>(info.minimum) + info.maximum) * 0.5;
    const double positiveRange = static_cast<double>(info.maximum) - center;
    const double negativeRange = center - static_cast<double>(info.minimum);
    double raw = static_cast<double>(value) - center;
    double denom = raw >= 0.0 ? positiveRange : negativeRange;
    if (denom <= 0.0) return 0;
    double x = raw / denom;
    if (x > 1.0) x = 1.0;
    if (x < -1.0) x = -1.0;

    double dead = 0.0;
    if (info.flat > 0) {
        double flatDenom = positiveRange < negativeRange ? positiveRange : negativeRange;
        if (flatDenom > 0.0) dead = static_cast<double>(info.flat) / flatDenom;
        if (dead > 0.35) dead = 0.35;
    }

    double sign = x < 0.0 ? -1.0 : 1.0;
    double mag = x < 0.0 ? -x : x;
    if (mag <= dead) return 0;
    mag = (mag - dead) / (1.0 - dead);

    double gain = gainPercent / 100.0;
    if (gain < 0.01) gain = 0.01;
    if (gain > 5.0) gain = 5.0;
    // Endpoint-preserving sensitivity curve: derivative at center = gain,
    // while full stick remains full stick. This avoids losing maximum range.
    double curved = (gain * mag) / (1.0 + (gain - 1.0) * mag);
    if (curved > 1.0) curved = 1.0;
    int out = static_cast<int>(sign * curved * 32767.0);
    if (out > 32767) out = 32767;
    if (out < -32767) out = -32767;
    return static_cast<int16_t>(out);
}

uint8_t mapTrigger(const input_absinfo& info, int value) {
    if (info.maximum <= info.minimum) return 0;
    long long numerator = static_cast<long long>(value - info.minimum) * 255LL;
    long long denominator = static_cast<long long>(info.maximum) - info.minimum;
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

int createUhid(const char* name, const uint8_t* descriptor, size_t descriptorSize,
               uint32_t vendor, uint32_t product) {
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
    event.u.create2.bus = BUS_VIRTUAL;
    event.u.create2.vendor = vendor;
    event.u.create2.product = product;
    event.u.create2.version = 1;
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
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x02,       // Usage (Mouse)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x01,       //   Usage (Pointer)
    0xA1, 0x00,       //   Collection (Physical)
    0x05, 0x09,       //     Usage Page (Button)
    0x19, 0x01,       //     Usage Minimum (1)
    0x29, 0x05,       //     Usage Maximum (5)
    0x15, 0x00,       //     Logical Minimum (0)
    0x25, 0x01,       //     Logical Maximum (1)
    0x95, 0x05,       //     Report Count (5)
    0x75, 0x01,       //     Report Size (1)
    0x81, 0x02,       //     Input (Data,Var,Abs)
    0x95, 0x03,       //     Report Count (3)
    0x75, 0x01,       //     Report Size (1)
    0x81, 0x03,       //     Input (Const,Var,Abs)
    0x05, 0x01,       //     Usage Page (Generic Desktop)
    0x09, 0x30,       //     Usage (X)
    0x09, 0x31,       //     Usage (Y)
    0x16, 0x01, 0x80, //     Logical Minimum (-32767)
    0x26, 0xFF, 0x7F, //     Logical Maximum (32767)
    0x75, 0x10,       //     Report Size (16)
    0x95, 0x02,       //     Report Count (2)
    0x81, 0x06,       //     Input (Data,Var,Rel)
    0x09, 0x38,       //     Usage (Wheel)
    0x15, 0x81,       //     Logical Minimum (-127)
    0x25, 0x7F,       //     Logical Maximum (127)
    0x75, 0x08,       //     Report Size (8)
    0x95, 0x01,       //     Report Count (1)
    0x81, 0x06,       //     Input (Data,Var,Rel)
    0x05, 0x0C,       //     Usage Page (Consumer)
    0x0A, 0x38, 0x02, //     Usage (AC Pan)
    0x15, 0x81,
    0x25, 0x7F,
    0x75, 0x08,
    0x95, 0x01,
    0x81, 0x06,
    0xC0,             //   End Collection
    0xC0              // End Collection
};

constexpr uint8_t kGamepadDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x10,       //   Usage Maximum (16)
    0x15, 0x00,
    0x25, 0x01,
    0x75, 0x01,
    0x95, 0x10,
    0x81, 0x02,       //   16 buttons
    0x05, 0x01,
    0x09, 0x39,       //   Hat switch
    0x15, 0x00,
    0x25, 0x07,
    0x35, 0x00,
    0x46, 0x3B, 0x01, //   Physical max 315 deg
    0x65, 0x14,       //   Unit degrees
    0x75, 0x04,
    0x95, 0x01,
    0x81, 0x42,       //   Data,Var,Abs,Null
    0x65, 0x00,
    0x75, 0x04,
    0x95, 0x01,
    0x81, 0x03,       //   padding
    0x05, 0x01,
    0x09, 0x30,       //   X (left X)
    0x09, 0x31,       //   Y (left Y)
    0x09, 0x32,       //   Z (right X on Android generic pads)
    0x09, 0x35,       //   Rz (right Y)
    0x16, 0x01, 0x80,
    0x26, 0xFF, 0x7F,
    0x75, 0x10,
    0x95, 0x04,
    0x81, 0x02,
    0x05, 0x02,       //   Usage Page (Simulation Controls)
    0x09, 0xC4,       //   Accelerator -> ABS_GAS / RTRIGGER
    0x09, 0xC5,       //   Brake -> ABS_BRAKE / LTRIGGER
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
    int64_t residualX = 0; // fixed-point, denominator 100.
    int64_t residualY = 0;
};

struct GamepadProxy {
    int fd = -1;
    int uhid = -1;
    char path[64]{};
    char name[128]{};
    GamepadReport report{};
    input_absinfo leftX{};
    input_absinfo leftY{};
    input_absinfo rightX{};
    input_absinfo rightY{};
    input_absinfo triggerR{};
    input_absinfo triggerL{};
    int rightXCode = -1;
    int rightYCode = -1;
    int triggerRCode = -1;
    int triggerLCode = -1;
    int hatX = 0;
    int hatY = 0;
    bool dpadUp = false;
    bool dpadDown = false;
    bool dpadLeft = false;
    bool dpadRight = false;
    GamepadReport lastTelemetry{};
    bool telemetryInitialized = false;
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
    memset(p, 0, sizeof(*p));
    p->fd = -1;
    p->uhid = -1;
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
    memset(p, 0, sizeof(*p));
    p->fd = -1;
    p->uhid = -1;
}

int scaleMouseDelta(int delta, int gainPercent, int64_t* residual) {
    if (!residual) return delta;
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

int gamepadButtonIndex(int code) {
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

bool pickGamepadAxes(int fd, GamepadProxy* p) {
    if (!p) return false;
    if (!axisInfo(fd, ABS_X, &p->leftX) || !axisInfo(fd, ABS_Y, &p->leftY)) return false;

    input_absinfo z{}, rz{}, rx{}, ry{};
    bool hasZ = axisInfo(fd, ABS_Z, &z);
    bool hasRz = axisInfo(fd, ABS_RZ, &rz);
    bool hasRx = axisInfo(fd, ABS_RX, &rx);
    bool hasRy = axisInfo(fd, ABS_RY, &ry);

    if (hasZ && hasRz && axisLooksCentered(z) && axisLooksCentered(rz)) {
        p->rightXCode = ABS_Z;
        p->rightYCode = ABS_RZ;
        p->rightX = z;
        p->rightY = rz;
    } else if (hasRx && hasRy && axisLooksCentered(rx) && axisLooksCentered(ry)) {
        p->rightXCode = ABS_RX;
        p->rightYCode = ABS_RY;
        p->rightX = rx;
        p->rightY = ry;
    } else {
        return false; // sensitivity needs a real right-stick pair; don't guess.
    }

    input_absinfo info{};
    if (axisInfo(fd, ABS_GAS, &info)) {
        p->triggerRCode = ABS_GAS;
        p->triggerR = info;
    } else if (hasZ && p->rightXCode != ABS_Z && !axisLooksCentered(z)) {
        p->triggerRCode = ABS_Z;
        p->triggerR = z;
    }
    if (axisInfo(fd, ABS_BRAKE, &info)) {
        p->triggerLCode = ABS_BRAKE;
        p->triggerL = info;
    } else if (hasRz && p->rightYCode != ABS_RZ && !axisLooksCentered(rz)) {
        p->triggerLCode = ABS_RZ;
        p->triggerL = rz;
    }
    return true;
}

bool attachMouse(const char* path, MouseProxy* p) {
    if (!path || !p) return false;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    char name[128]{};
    getDeviceName(fd, name, sizeof(name));
    if (nameIsVirtual(name) || !isMouseDevice(fd)) {
        close(fd);
        return false;
    }
    int uhid = createUhid("Axon Input Virtual Mouse", kMouseDescriptor,
                         sizeof(kMouseDescriptor), 0x4B44, 0x0001);
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
    memset(p, 0, sizeof(*p));
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
    if (nameIsVirtual(name) || !isGamepadDevice(fd)) {
        close(fd);
        return false;
    }
    GamepadProxy candidate{};
    candidate.fd = fd;
    candidate.uhid = -1;
    if (!pickGamepadAxes(fd, &candidate)) {
        close(fd);
        return false;
    }
    int uhid = createUhid("Axon Input Virtual Gamepad", kGamepadDescriptor,
                         sizeof(kGamepadDescriptor), 0x4B44, 0x0002);
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
    *p = candidate;
    p->uhid = uhid;
    snprintf(p->path, sizeof(p->path), "%s", path);
    snprintf(p->name, sizeof(p->name), "%s", name[0] ? name : "gamepad");
    p->report.hat = 8;
    p->report.lx = 0;
    p->report.ly = 0;
    p->report.rx = 0;
    p->report.ry = 0;
    p->report.rt = 0;
    p->report.lt = 0;
    sendUhidReport(p->uhid, &p->report, sizeof(p->report));
    printf("STATUS gamepad-ready %s %s\n", p->path, p->name);
    fflush(stdout);
    return true;
}

void scanDevices(MouseProxy* mouse, GamepadProxy* gamepad) {
    for (int i = 0; i < kMaxEvents && (!mouse || mouse->fd < 0 || !gamepad || gamepad->fd < 0); ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        if (access(path, R_OK) != 0) continue;
        if (mouse && mouse->fd < 0) (void)attachMouse(path, mouse);
        if (gamepad && gamepad->fd < 0) (void)attachGamepad(path, gamepad);
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

bool processGamepadEvent(GamepadProxy* p, const input_event& ev, const Gains& gains) {
    if (!p || p->fd < 0 || p->uhid < 0) return false;
    if (ev.type == EV_KEY) {
        int idx = gamepadButtonIndex(ev.code);
        if (idx >= 0 && idx < 16) {
            uint16_t bit = static_cast<uint16_t>(1u << idx);
            if (ev.value != 0) p->report.buttons |= bit;
            else p->report.buttons &= static_cast<uint16_t>(~bit);
        } else if (ev.code == BTN_DPAD_UP || ev.code == BTN_DPAD_DOWN
                || ev.code == BTN_DPAD_LEFT || ev.code == BTN_DPAD_RIGHT) {
            setDpadButton(p, ev.code, ev.value != 0);
        }
        return true;
    }
    if (ev.type == EV_ABS) {
        if (ev.code == ABS_X) p->report.lx = mapAxis(p->leftX, ev.value, 100);
        else if (ev.code == ABS_Y) p->report.ly = mapAxis(p->leftY, ev.value, 100);
        else if (ev.code == p->rightXCode) p->report.rx = mapAxis(p->rightX, ev.value, gains.gamepad);
        else if (ev.code == p->rightYCode) p->report.ry = mapAxis(p->rightY, ev.value, gains.gamepad);
        else if (ev.code == ABS_HAT0X) {
            p->hatX = ev.value < 0 ? -1 : (ev.value > 0 ? 1 : 0);
            p->report.hat = computeHat(*p);
        } else if (ev.code == ABS_HAT0Y) {
            p->hatY = ev.value < 0 ? -1 : (ev.value > 0 ? 1 : 0);
            p->report.hat = computeHat(*p);
        } else if (ev.code == p->triggerRCode) {
            p->report.rt = mapTrigger(p->triggerR, ev.value);
        } else if (ev.code == p->triggerLCode) {
            p->report.lt = mapTrigger(p->triggerL, ev.value);
        }
        return true;
    }
    if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
        bool ok = sendUhidReport(p->uhid, &p->report, sizeof(p->report));
        emitGamepadTelemetry(p);
        return ok;
    }
    return true;
}

bool readAndProcessMouse(MouseProxy* p, const Gains& gains) {
    input_event events[32];
    for (;;) {
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
}

bool readAndProcessGamepad(GamepadProxy* p, const Gains& gains) {
    input_event events[32];
    for (;;) {
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
}

void drainUhid(int fd) {
    if (fd < 0) return;
    uhid_event event{};
    while (read(fd, &event, sizeof(event)) > 0) {
        // Output / feature reports are intentionally ignored. This proxy handles input only.
    }
}

bool probeUhid() {
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    close(fd);
    return true;
}

} // namespace

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

    if (!probeUhid()) {
        printf("ERROR uhid-open errno=%d\n", errno);
        return 12;
    }
    if (probeOnly) {
        printf("OK uhid\n");
        return 0;
    }
    if (!gainFile) {
        printf("ERROR missing-gain-file\n");
        return 2;
    }

    Gains gains{};
    (void)readGains(gainFile, &gains);
    printf("STATUS starting mouse=%d gamepad=%d\n", gains.mouse, gains.gamepad);

    MouseProxy mouse{};
    mouse.fd = -1;
    mouse.uhid = -1;
    GamepadProxy gamepad{};
    gamepad.fd = -1;
    gamepad.uhid = -1;

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
            if (readGains(gainFile, &gains)) {
                printf("STATUS gain mouse=%d gamepad=%d\n", gains.mouse, gains.gamepad);
            }
            lastConfig = now;
        }
        if ((mouse.fd < 0 || gamepad.fd < 0) && now - lastScan >= kScanIntervalMs) {
            scanDevices(&mouse, &gamepad);
            if (mouse.fd < 0 && gamepad.fd < 0) {
                printf("STATUS waiting-device\n");
            }
            lastScan = now;
        }

        pollfd fds[4]{};
        int count = 0;
        int mouseIndex = -1;
        int gamepadIndex = -1;
        int mouseUhidIndex = -1;
        int gamepadUhidIndex = -1;
        if (mouse.fd >= 0) {
            mouseIndex = count;
            fds[count++] = {mouse.fd, POLLIN | POLLERR | POLLHUP, 0};
        }
        if (gamepad.fd >= 0) {
            gamepadIndex = count;
            fds[count++] = {gamepad.fd, POLLIN | POLLERR | POLLHUP, 0};
        }
        if (mouse.uhid >= 0) {
            mouseUhidIndex = count;
            fds[count++] = {mouse.uhid, POLLIN | POLLERR | POLLHUP, 0};
        }
        if (gamepad.uhid >= 0) {
            gamepadUhidIndex = count;
            fds[count++] = {gamepad.uhid, POLLIN | POLLERR | POLLHUP, 0};
        }

        int result = poll(fds, count, 50);
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
        if (gamepadIndex >= 0 && fds[gamepadIndex].revents) {
            if ((fds[gamepadIndex].revents & (POLLERR | POLLHUP)) || !readAndProcessGamepad(&gamepad, gains)) {
                printf("STATUS gamepad-disconnected\n");
                closeGamepad(&gamepad);
            }
        }
        if (mouseUhidIndex >= 0 && (fds[mouseUhidIndex].revents & POLLIN)) drainUhid(mouse.uhid);
        if (gamepadUhidIndex >= 0 && (fds[gamepadUhidIndex].revents & POLLIN)) drainUhid(gamepad.uhid);
        emitMouseTelemetry(&mouse);
    }

    closeMouse(&mouse);
    closeGamepad(&gamepad);
    printf("STATUS stopped\n");
    return 0;
}
