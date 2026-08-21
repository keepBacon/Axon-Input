// 键盘和鼠标状态核心。使用位掩码和固定容量时间环。
#include <jni.h>
#include <atomic>
#include <cstdint>

namespace {
constexpr std::uint32_t KEY_W     = 1u << 0;
constexpr std::uint32_t KEY_A     = 1u << 1;
constexpr std::uint32_t KEY_S     = 1u << 2;
constexpr std::uint32_t KEY_D     = 1u << 3;
constexpr std::uint32_t KEY_SPACE = 1u << 4;

constexpr jint KEYCODE_A = 29;
constexpr jint KEYCODE_D = 32;
constexpr jint KEYCODE_S = 47;
constexpr jint KEYCODE_W = 51;
constexpr jint KEYCODE_SPACE = 62;

constexpr int MOUSE_LEFT = 0;
constexpr int MOUSE_RIGHT = 1;
constexpr int MOUSE_BUTTONS = 2;
constexpr int DPS_CAPACITY = 128;
constexpr std::int64_t DPS_WINDOW_MS = 1000;

std::uint32_t g_pressedMask = 0;
bool g_mousePressed[MOUSE_BUTTONS] = {false, false};
std::int64_t g_clickTimes[MOUSE_BUTTONS][DPS_CAPACITY] = {};
int g_clickHead[MOUSE_BUTTONS] = {0, 0};
int g_clickSize[MOUSE_BUTTONS] = {0, 0};
std::atomic_flag g_mouseLock = ATOMIC_FLAG_INIT;

struct MouseGuard {
    MouseGuard() noexcept {
        while (g_mouseLock.test_and_set(std::memory_order_acquire)) {}
    }
    ~MouseGuard() { g_mouseLock.clear(std::memory_order_release); }
};

constexpr std::uint32_t bitForKeyCode(jint keyCode) noexcept {
    switch (keyCode) {
        case KEYCODE_W: return KEY_W;
        case KEYCODE_A: return KEY_A;
        case KEYCODE_S: return KEY_S;
        case KEYCODE_D: return KEY_D;
        case KEYCODE_SPACE: return KEY_SPACE;
        default: return 0;
    }
}

void trimClicks(int button, std::int64_t nowMs) noexcept {
    while (g_clickSize[button] > 0) {
        const std::int64_t oldest = g_clickTimes[button][g_clickHead[button]];
        if (nowMs - oldest < DPS_WINDOW_MS) break;
        g_clickHead[button] = (g_clickHead[button] + 1) % DPS_CAPACITY;
        --g_clickSize[button];
    }
}

void recordClick(int button, std::int64_t nowMs) noexcept {
    trimClicks(button, nowMs);
    if (g_clickSize[button] == DPS_CAPACITY) {
        g_clickHead[button] = (g_clickHead[button] + 1) % DPS_CAPACITY;
        --g_clickSize[button];
    }
    const int index = (g_clickHead[button] + g_clickSize[button]) % DPS_CAPACITY;
    g_clickTimes[button][index] = nowMs;
    ++g_clickSize[button];
}

jlong packMouseStats(std::int64_t nowMs) noexcept {
    trimClicks(MOUSE_LEFT, nowMs);
    trimClicks(MOUSE_RIGHT, nowMs);
    const int leftDps = g_clickSize[MOUSE_LEFT] > 255 ? 255 : g_clickSize[MOUSE_LEFT];
    const int rightDps = g_clickSize[MOUSE_RIGHT] > 255 ? 255 : g_clickSize[MOUSE_RIGHT];

    std::uint64_t packed = 0;
    if (g_mousePressed[MOUSE_LEFT]) packed |= 1ull;
    if (g_mousePressed[MOUSE_RIGHT]) packed |= 2ull;
    packed |= static_cast<std::uint64_t>(leftDps) << 8;
    packed |= static_cast<std::uint64_t>(rightDps) << 16;
    return static_cast<jlong>(packed);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_axon_input_NativeKeyEngine_nativeUpdateKey(
        JNIEnv*, jclass, jint keyCode, jboolean pressed) {
    const std::uint32_t bit = bitForKeyCode(keyCode);
    if (bit == 0) return static_cast<jint>(g_pressedMask);
    if (pressed == JNI_TRUE) g_pressedMask |= bit;
    else g_pressedMask &= ~bit;
    return static_cast<jint>(g_pressedMask);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_axon_input_NativeKeyEngine_nativeReset(JNIEnv*, jclass) {
    g_pressedMask = 0;
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_axon_input_NativeKeyEngine_nativeIsTrackedKey(
        JNIEnv*, jclass, jint keyCode) {
    return bitForKeyCode(keyCode) != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_axon_input_NativeKeyEngine_nativeUpdateMouseButton(
        JNIEnv*, jclass, jint button, jboolean pressed, jlong eventTimeMs) {
    if (button < 0 || button >= MOUSE_BUTTONS) return 0;
    MouseGuard guard;
    const bool down = pressed == JNI_TRUE;
    if (down && !g_mousePressed[button]) recordClick(button, static_cast<std::int64_t>(eventTimeMs));
    g_mousePressed[button] = down;
    return packMouseStats(static_cast<std::int64_t>(eventTimeMs));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_axon_input_NativeKeyEngine_nativeGetMouseStats(
        JNIEnv*, jclass, jlong nowMs) {
    MouseGuard guard;
    return packMouseStats(static_cast<std::int64_t>(nowMs));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_axon_input_NativeKeyEngine_nativeResetMouse(
        JNIEnv*, jclass, jlong nowMs) {
    MouseGuard guard;
    for (int b = 0; b < MOUSE_BUTTONS; ++b) {
        g_mousePressed[b] = false;
        g_clickHead[b] = 0;
        g_clickSize[b] = 0;
    }
    return packMouseStats(static_cast<std::int64_t>(nowMs));
}
