#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
volatile sig_atomic_t g_stop = 0;
int g_keyboard = -1;
int g_mouse = -1;
int g_gamepad = -1;

void onSignal(int) { g_stop = 1; }

bool writeEvent(int fd, __u16 type, __u16 code, __s32 value) {
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    return write(fd, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

bool emitTap(int fd, int code) {
    if (fd < 0 || code <= 0 || code > KEY_MAX) return false;
    if (!writeEvent(fd, EV_KEY, static_cast<__u16>(code), 1)) return false;
    if (!writeEvent(fd, EV_SYN, SYN_REPORT, 0)) return false;
    usleep(16000);
    if (!writeEvent(fd, EV_KEY, static_cast<__u16>(code), 0)) return false;
    return writeEvent(fd, EV_SYN, SYN_REPORT, 0);
}

void destroyDevice(int& fd) {
    if (fd < 0) return;
    (void)ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    fd = -1;
}

bool finishSetup(int fd, int product, const char* name, const char* phys) {
    uinput_setup setup{};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x4B44;
    setup.id.product = product;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "%s", name);
    (void)ioctl(fd, UI_SET_PHYS, phys);
    return ioctl(fd, UI_DEV_SETUP, &setup) >= 0 && ioctl(fd, UI_DEV_CREATE) >= 0;
}

int createKeyboard() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    (void)ioctl(fd, UI_SET_EVBIT, EV_REP);
    // Axon currently records standard keyboard targets in this evdev range.
    for (int code = 1; code <= 255; ++code) (void)ioctl(fd, UI_SET_KEYBIT, code);
    if (!finishSetup(fd, 0x1020, "Axon Input Virtual Custom Mapping Keyboard",
                     "axon-input/virtual/custom-mapping-keyboard")) {
        close(fd);
        return -1;
    }
    return fd;
}

int createMouse() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    (void)ioctl(fd, UI_SET_EVBIT, EV_REL);
    (void)ioctl(fd, UI_SET_RELBIT, REL_X);
    (void)ioctl(fd, UI_SET_RELBIT, REL_Y);
    for (int code = BTN_LEFT; code <= BTN_TASK; ++code) (void)ioctl(fd, UI_SET_KEYBIT, code);
    if (!finishSetup(fd, 0x1021, "Axon Input Virtual Custom Mapping Mouse",
                     "axon-input/virtual/custom-mapping-mouse")) {
        close(fd);
        return -1;
    }
    return fd;
}

int createGamepad() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int code = BTN_SOUTH; code <= BTN_THUMBR; ++code) (void)ioctl(fd, UI_SET_KEYBIT, code);
    for (int code = 704; code <= 707; ++code) (void)ioctl(fd, UI_SET_KEYBIT, code);
    for (int code = 0x220; code <= 0x223; ++code) (void)ioctl(fd, UI_SET_KEYBIT, code);
    if (!finishSetup(fd, 0x1022, "Axon Input Virtual Custom Mapping Gamepad",
                     "axon-input/virtual/custom-mapping-gamepad")) {
        close(fd);
        return -1;
    }
    return fd;
}

void cleanup() {
    destroyDevice(g_keyboard);
    destroyDevice(g_mouse);
    destroyDevice(g_gamepad);
}
}

int main() {
    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);

    g_keyboard = createKeyboard();
    g_mouse = createMouse();
    g_gamepad = createGamepad();
    if (g_keyboard < 0 || g_mouse < 0 || g_gamepad < 0) {
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        cleanup();
        return 5;
    }
    // Let Android classify all three devices before the first macro fires.
    usleep(70000);
    printf("READY\n");
    fflush(stdout);

    char line[64];
    while (!g_stop && fgets(line, sizeof(line), stdin) != nullptr) {
        char op = 0;
        char kind = 0;
        int code = -1;
        if (sscanf(line, " %c %c %d", &op, &kind, &code) != 3 || op != 'T') continue;
        int fd = kind == 'm' ? g_mouse : (kind == 'g' ? g_gamepad : g_keyboard);
        (void)emitTap(fd, code);
    }
    cleanup();
    return 0;
}
