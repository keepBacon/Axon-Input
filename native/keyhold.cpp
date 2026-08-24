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
int g_uinput = -1;
int g_code = -1;
bool g_down = false;

void onSignal(int) { g_stop = 1; }

bool writeEvent(__u16 type, __u16 code, __s32 value) {
    if (g_uinput < 0) return false;
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    return write(g_uinput, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

bool syncReport() { return writeEvent(EV_SYN, SYN_REPORT, 0); }

void releaseAndDestroy() {
    if (g_uinput < 0) return;
    if (g_down && g_code > 0) {
        (void)writeEvent(EV_KEY, static_cast<__u16>(g_code), 0);
        (void)syncReport();
        g_down = false;
        usleep(8000);
    }
    (void)ioctl(g_uinput, UI_DEV_DESTROY);
    close(g_uinput);
    g_uinput = -1;
}

int parseCode(int argc, char** argv) {
    for (int i = 1; i + 1 < argc; ++i) if (strcmp(argv[i], "--code") == 0) return atoi(argv[i + 1]);
    return -1;
}

const char* parseKind(int argc, char** argv) {
    for (int i = 1; i + 1 < argc; ++i) if (strcmp(argv[i], "--kind") == 0) return argv[i + 1];
    return "keyboard";
}

void setupAbsAxis(int fd, int code) {
    (void)ioctl(fd, UI_SET_ABSBIT, code);
    uinput_abs_setup abs{};
    abs.code = static_cast<__u16>(code);
    abs.absinfo.minimum = -32768;
    abs.absinfo.maximum = 32767;
    abs.absinfo.flat = 1024;
    (void)ioctl(fd, UI_ABS_SETUP, &abs);
}

void setupCapabilities(int fd, const char* kind, int targetCode) {
    (void)ioctl(fd, UI_SET_EVBIT, EV_KEY);
    (void)ioctl(fd, UI_SET_KEYBIT, targetCode);
    if (strcmp(kind, "mouse") == 0) {
        (void)ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
        (void)ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
        (void)ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
        (void)ioctl(fd, UI_SET_KEYBIT, BTN_SIDE);
        (void)ioctl(fd, UI_SET_KEYBIT, BTN_EXTRA);
        (void)ioctl(fd, UI_SET_EVBIT, EV_REL);
        (void)ioctl(fd, UI_SET_RELBIT, REL_X);
        (void)ioctl(fd, UI_SET_RELBIT, REL_Y);
    } else if (strcmp(kind, "gamepad") == 0) {
        const int keys[] = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_C, BTN_Z,
                            BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START,
                            BTN_MODE, BTN_THUMBL, BTN_THUMBR, BTN_DPAD_UP, BTN_DPAD_DOWN,
                            BTN_DPAD_LEFT, BTN_DPAD_RIGHT};
        for (int key : keys) (void)ioctl(fd, UI_SET_KEYBIT, key);
        (void)ioctl(fd, UI_SET_EVBIT, EV_ABS);
        setupAbsAxis(fd, ABS_X);
        setupAbsAxis(fd, ABS_Y);
    } else {
        (void)ioctl(fd, UI_SET_EVBIT, EV_REP);
        for (int code = 1; code < BTN_MISC; ++code) (void)ioctl(fd, UI_SET_KEYBIT, code);
    }
}
}

int main(int argc, char** argv) {
    g_code = parseCode(argc, argv);
    const char* kind = parseKind(argc, argv);
    if (g_code <= 0 || g_code > KEY_MAX) {
        fprintf(stderr, "invalid key code\n");
        return 2;
    }
    if (strcmp(kind, "keyboard") != 0 && strcmp(kind, "mouse") != 0 && strcmp(kind, "gamepad") != 0) {
        fprintf(stderr, "invalid input kind\n");
        return 2;
    }

    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);

    g_uinput = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_uinput < 0) {
        fprintf(stderr, "uinput open failed: %s\n", strerror(errno));
        return 3;
    }
    setupCapabilities(g_uinput, kind, g_code);

    uinput_setup setup{};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x4B44;
    setup.id.product = 0x1004;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "%s", "Axon Input Virtual Force Hold");
    (void)ioctl(g_uinput, UI_SET_PHYS, "axon-input/virtual/force-hold");

    if (ioctl(g_uinput, UI_DEV_SETUP, &setup) < 0 || ioctl(g_uinput, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        releaseAndDestroy();
        return 5;
    }

    usleep(60000);
    if (!writeEvent(EV_KEY, static_cast<__u16>(g_code), 1) || !syncReport()) {
        fprintf(stderr, "key down failed: %s\n", strerror(errno));
        releaseAndDestroy();
        return 6;
    }
    g_down = true;
    printf("READY\n");
    fflush(stdout);

    while (!g_stop) pause();
    releaseAndDestroy();
    return 0;
}
