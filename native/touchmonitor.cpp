// Axon Input touchscreen monitor.
// Runs under Shizuku shell identity and reads evdev directly. It never EVIOCGRABs or injects input.
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

namespace {
constexpr int kMaxEvents = 256;
constexpr int kMaxSlots = 20;
constexpr int kMaxTypeAContacts = 20;
constexpr int kScanIntervalMs = 900;
constexpr int kCoordScale = 100000;
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

bool getAbsInfo(int fd, int code, input_absinfo* out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));
    return ioctl(fd, EVIOCGABS(code), out) >= 0 && out->maximum > out->minimum;
}

bool containsInsensitive(const char* text, const char* needle) {
    if (!text || !needle || !*needle) return false;
    char lowerText[160]{};
    char lowerNeedle[64]{};
    size_t tn = strlen(text);
    size_t nn = strlen(needle);
    if (tn >= sizeof(lowerText)) tn = sizeof(lowerText) - 1;
    if (nn >= sizeof(lowerNeedle)) nn = sizeof(lowerNeedle) - 1;
    for (size_t i = 0; i < tn; ++i) {
        char c = text[i];
        lowerText[i] = (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
    }
    for (size_t i = 0; i < nn; ++i) {
        char c = needle[i];
        lowerNeedle[i] = (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
    }
    return strstr(lowerText, lowerNeedle) != nullptr;
}

struct Contact {
    bool active = false;
    int id = -1;
    int x = 0;
    int y = 0;
    bool hasX = false;
    bool hasY = false;
};

struct Device {
    int fd = -1;
    char path[64]{};
    char name[128]{};
    input_absinfo xInfo{};
    input_absinfo yInfo{};
    bool useMt = false;
    bool hasSlots = false;
    bool hasTracking = false;
    bool hasBtnTouch = false;
    bool direct = false;
    int currentSlot = 0;
    bool buttonDown = false;
    Contact slots[kMaxSlots]{};
    Contact typeACurrent{};
    Contact typeAContacts[kMaxTypeAContacts]{};
    int typeACount = 0;
    bool emittedOnce = false;
    int lastCount = -1;
    int lastIds[kMaxSlots]{};
    int lastXs[kMaxSlots]{};
    int lastYs[kMaxSlots]{};
};

int normCoord(int value, const input_absinfo& info) {
    long long den = static_cast<long long>(info.maximum) - info.minimum;
    if (den <= 0) return 0;
    long long num = static_cast<long long>(value) - info.minimum;
    long long out = num * kCoordScale / den;
    if (out < 0) out = 0;
    if (out > kCoordScale) out = kCoordScale;
    return static_cast<int>(out);
}

int touchDeviceScore(int fd, char* nameOut, size_t nameSize, Device* capsOut) {
    unsigned long evBits[8]{};
    unsigned long absBits[8]{};
    unsigned long keyBits[16]{};
    unsigned long propBits[2]{};
    if (!getBits(fd, 0, evBits) || !bitTest(evBits, EV_ABS)) return 0;
    if (!getBits(fd, EV_ABS, absBits)) return 0;

    char name[128]{};
    getDeviceName(fd, name, sizeof(name));
    if (name[0] && strstr(name, kVirtualPrefix)) return 0;

    const bool mtX = bitTest(absBits, ABS_MT_POSITION_X);
    const bool mtY = bitTest(absBits, ABS_MT_POSITION_Y);
    const bool absX = bitTest(absBits, ABS_X);
    const bool absY = bitTest(absBits, ABS_Y);
    const bool useMt = mtX && mtY;
    if (!useMt && !(absX && absY)) return 0;

    input_absinfo xInfo{}, yInfo{};
    if (!getAbsInfo(fd, useMt ? ABS_MT_POSITION_X : ABS_X, &xInfo)
            || !getAbsInfo(fd, useMt ? ABS_MT_POSITION_Y : ABS_Y, &yInfo)) return 0;

    bool hasSlots = useMt && bitTest(absBits, ABS_MT_SLOT);
    bool hasTracking = useMt && bitTest(absBits, ABS_MT_TRACKING_ID);
    bool hasBtnTouch = false;
    if (bitTest(evBits, EV_KEY) && getBits(fd, EV_KEY, keyBits)) {
        hasBtnTouch = bitTest(keyBits, BTN_TOUCH);
    }
    memset(propBits, 0, sizeof(propBits));
    bool direct = ioctl(fd, EVIOCGPROP(sizeof(propBits)), propBits) >= 0
            && bitTest(propBits, INPUT_PROP_DIRECT);

    int score = useMt ? 1200 : 300;
    if (direct) score += 1800;
    if (hasTracking) score += 240;
    if (hasSlots) score += 180;
    if (hasBtnTouch) score += 120;
    if (containsInsensitive(name, "touchscreen") || containsInsensitive(name, "touch screen")
            || containsInsensitive(name, "touchpanel") || containsInsensitive(name, "touch panel")) score += 1200;
    if (containsInsensitive(name, "touchpad") || containsInsensitive(name, "trackpad")) score -= 1800;
    if (containsInsensitive(name, "mouse")) score -= 2200;
    if (containsInsensitive(name, "stylus") || containsInsensitive(name, "pen")) score -= 1400;

    // Real touch panels usually expose a large 2D range. Penalize tiny sensor/control axes.
    long long area = static_cast<long long>(xInfo.maximum - xInfo.minimum)
            * static_cast<long long>(yInfo.maximum - yInfo.minimum);
    if (area > 500000) score += 300;
    if (area < 10000) score -= 1000;
    if (score <= 0) return 0;

    if (nameOut && nameSize) snprintf(nameOut, nameSize, "%s", name[0] ? name : "touchscreen");
    if (capsOut) {
        capsOut->xInfo = xInfo;
        capsOut->yInfo = yInfo;
        capsOut->useMt = useMt;
        capsOut->hasSlots = hasSlots;
        capsOut->hasTracking = hasTracking;
        capsOut->hasBtnTouch = hasBtnTouch;
        capsOut->direct = direct;
    }
    return score;
}

void resetContacts(Device* d) {
    if (!d) return;
    d->currentSlot = 0;
    d->buttonDown = false;
    for (auto& c : d->slots) c = Contact{};
    d->typeACurrent = Contact{};
    for (auto& c : d->typeAContacts) c = Contact{};
    d->typeACount = 0;
    d->emittedOnce = false;
    d->lastCount = -1;
}

void closeDevice(Device* d) {
    if (!d) return;
    if (d->fd >= 0) close(d->fd);
    d->fd = -1;
    resetContacts(d);
}

bool attachDevice(const char* path, Device* d) {
    if (!path || !d) return false;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    Device caps{};
    char name[128]{};
    if (touchDeviceScore(fd, name, sizeof(name), &caps) <= 0) {
        close(fd);
        return false;
    }
    caps.fd = fd;
    snprintf(caps.path, sizeof(caps.path), "%s", path);
    snprintf(caps.name, sizeof(caps.name), "%s", name);
    *d = caps;
    resetContacts(d);
    d->fd = fd;
    printf("STATUS touch-ready %s | %s | %s%s%s\n",
           d->path, d->name, d->useMt ? "MT" : "ABS",
           d->hasSlots ? "+SLOT" : "", d->direct ? "+DIRECT" : "");
    fflush(stdout);
    return true;
}

bool scan(Device* d) {
    if (!d || d->fd >= 0) return true;
    int bestScore = 0;
    char bestPath[64]{};
    int existing = 0;
    int readable = 0;
    for (int i = 0; i < kMaxEvents; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        if (access(path, F_OK) != 0) continue;
        ++existing;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        ++readable;
        int score = touchDeviceScore(fd, nullptr, 0, nullptr);
        close(fd);
        if (score > bestScore) {
            bestScore = score;
            snprintf(bestPath, sizeof(bestPath), "%s", path);
        }
    }
    if (bestScore > 0 && attachDevice(bestPath, d)) return true;
    if (existing > 0 && readable == 0) printf("STATUS input-permission-denied\n");
    else if (readable > 0) printf("STATUS no-touchscreen-candidate\n");
    else printf("STATUS no-input-devices\n");
    fflush(stdout);
    return false;
}

void finishTypeAContact(Device* d) {
    if (!d || d->hasSlots || d->typeACount >= kMaxTypeAContacts) return;
    Contact c = d->typeACurrent;
    if (!c.hasX || !c.hasY) {
        d->typeACurrent = Contact{};
        return;
    }
    c.active = d->buttonDown || d->hasTracking || !d->hasBtnTouch;
    if (c.id < 0) c.id = d->typeACount;
    if (c.active) d->typeAContacts[d->typeACount++] = c;
    d->typeACurrent = Contact{};
}

int collectContacts(Device* d, Contact* out, int capacity) {
    if (!d || !out || capacity <= 0) return 0;
    int count = 0;
    if (d->useMt && d->hasSlots) {
        for (int i = 0; i < kMaxSlots && count < capacity; ++i) {
            Contact c = d->slots[i];
            if (!c.active || !c.hasX || !c.hasY) continue;
            if (c.id < 0) c.id = i;
            out[count++] = c;
        }
    } else if (d->useMt) {
        for (int i = 0; i < d->typeACount && count < capacity; ++i) {
            if (d->typeAContacts[i].active && d->typeAContacts[i].hasX && d->typeAContacts[i].hasY) {
                out[count++] = d->typeAContacts[i];
            }
        }
        if (count == 0 && d->typeACurrent.hasX && d->typeACurrent.hasY
                && (d->buttonDown || !d->hasBtnTouch)) {
            Contact c = d->typeACurrent;
            c.active = true;
            if (c.id < 0) c.id = 0;
            out[count++] = c;
        }
    } else {
        Contact c = d->slots[0];
        if (d->buttonDown && c.hasX && c.hasY) {
            c.active = true;
            c.id = 0;
            out[count++] = c;
        }
    }
    return count;
}

bool frameSame(Device* d, Contact* contacts, int count) {
    if (!d->emittedOnce || d->lastCount != count) return false;
    for (int i = 0; i < count; ++i) {
        int x = normCoord(contacts[i].x, d->xInfo);
        int y = normCoord(contacts[i].y, d->yInfo);
        if (d->lastIds[i] != contacts[i].id || d->lastXs[i] != x || d->lastYs[i] != y) return false;
    }
    return true;
}

void emitFrame(Device* d, bool force = false) {
    if (!d) return;
    if (d->useMt && !d->hasSlots) finishTypeAContact(d);
    Contact contacts[kMaxSlots]{};
    int count = collectContacts(d, contacts, kMaxSlots);
    if (!force && frameSame(d, contacts, count)) {
        if (d->useMt && !d->hasSlots) d->typeACount = 0;
        return;
    }
    printf("TOUCH %d", count);
    for (int i = 0; i < count; ++i) {
        int x = normCoord(contacts[i].x, d->xInfo);
        int y = normCoord(contacts[i].y, d->yInfo);
        printf(" %d %d %d", contacts[i].id, x, y);
        d->lastIds[i] = contacts[i].id;
        d->lastXs[i] = x;
        d->lastYs[i] = y;
    }
    printf("\n");
    fflush(stdout);
    d->lastCount = count;
    d->emittedOnce = true;
    if (d->useMt && !d->hasSlots) d->typeACount = 0;
}

void processEvent(Device* d, const input_event& ev) {
    if (!d) return;
    if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
        d->buttonDown = ev.value != 0;
        if (!d->buttonDown && d->useMt && !d->hasSlots) {
            d->typeACurrent = Contact{};
            d->typeACount = 0;
        }
        return;
    }
    if (ev.type == EV_ABS) {
        if (d->useMt) {
            if (d->hasSlots && ev.code == ABS_MT_SLOT) {
                int slot = ev.value;
                if (slot < 0) slot = 0;
                if (slot >= kMaxSlots) slot = kMaxSlots - 1;
                d->currentSlot = slot;
                return;
            }
            Contact* c = d->hasSlots ? &d->slots[d->currentSlot] : &d->typeACurrent;
            if (ev.code == ABS_MT_TRACKING_ID) {
                if (ev.value < 0) {
                    if (d->hasSlots) *c = Contact{};
                    else finishTypeAContact(d);
                } else {
                    if (d->hasSlots) {
                        *c = Contact{};
                        c->active = true;
                        c->id = ev.value;
                    } else {
                        if (c->hasX || c->hasY) finishTypeAContact(d);
                        c->active = true;
                        c->id = ev.value;
                    }
                }
                return;
            }
            if (ev.code == ABS_MT_POSITION_X) {
                c->x = ev.value;
                c->hasX = true;
                if (!d->hasTracking) c->active = d->buttonDown || !d->hasBtnTouch;
                return;
            }
            if (ev.code == ABS_MT_POSITION_Y) {
                c->y = ev.value;
                c->hasY = true;
                if (!d->hasTracking) c->active = d->buttonDown || !d->hasBtnTouch;
                return;
            }
        } else {
            Contact& c = d->slots[0];
            if (ev.code == ABS_X) { c.x = ev.value; c.hasX = true; }
            else if (ev.code == ABS_Y) { c.y = ev.value; c.hasY = true; }
            return;
        }
    }
    if (ev.type == EV_SYN) {
        if (ev.code == SYN_MT_REPORT && d->useMt && !d->hasSlots) {
            finishTypeAContact(d);
            return;
        }
        if (ev.code == SYN_REPORT) emitFrame(d);
    }
}

bool readEvents(Device* d) {
    input_event events[48];
    for (;;) {
        ssize_t n = read(d->fd, events, sizeof(events));
        if (n > 0) {
            size_t count = static_cast<size_t>(n) / sizeof(input_event);
            for (size_t i = 0; i < count; ++i) processEvent(d, events[i]);
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        return n != 0;
    }
}
} // namespace

int main() {
    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    setvbuf(stdout, nullptr, _IOLBF, 0);

    Device device{};
    device.fd = -1;
    long long lastScan = 0;
    long long lastHeartbeat = 0;

    printf("STATUS starting\n");
    while (!gStop) {
        long long now = nowMs();
        if (now - lastHeartbeat >= 1000) {
            printf("PING\n");
            fflush(stdout);
            lastHeartbeat = now;
        }
        if (device.fd < 0 && now - lastScan >= kScanIntervalMs) {
            scan(&device);
            if (device.fd >= 0) emitFrame(&device, true);
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
        if (result == 0) continue;
        if (pfd.revents & (POLLERR | POLLHUP)) {
            emitFrame(&device, true);
            printf("STATUS touch-disconnected\n");
            closeDevice(&device);
            continue;
        }
        if ((pfd.revents & POLLIN) && !readEvents(&device)) {
            emitFrame(&device, true);
            printf("STATUS touch-disconnected\n");
            closeDevice(&device);
        }
    }
    if (device.fd >= 0) {
        for (auto& c : device.slots) c = Contact{};
        device.buttonDown = false;
        emitFrame(&device, true);
    }
    closeDevice(&device);
    printf("STATUS stopped\n");
    return 0;
}
