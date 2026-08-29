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
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    return write(g_uinput, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

bool emitValue(int value) {
    if (g_uinput < 0 || g_code <= 0 || g_code > KEY_MAX) return false;
    if (value != 2 && g_down == (value != 0)) return true;
    if (!writeEvent(EV_KEY, static_cast<__u16>(g_code), value)) return false;
    if (!writeEvent(EV_SYN, SYN_REPORT, 0)) return false;
    if (value != 2) g_down = value != 0;
    return true;
}

void cleanup() {
    if (g_uinput < 0) return;
    if (g_down) (void)emitValue(0);
    (void)ioctl(g_uinput, UI_DEV_DESTROY);
    close(g_uinput);
    g_uinput = -1;
}

bool setupDevice(const char* kind) {
    g_uinput = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_uinput < 0) return false;
    (void)ioctl(g_uinput, UI_SET_EVBIT, EV_KEY);
    (void)ioctl(g_uinput, UI_SET_KEYBIT, g_code);
    if (strcmp(kind, "keyboard") == 0) {
        (void)ioctl(g_uinput, UI_SET_EVBIT, EV_REP);
    } else if (strcmp(kind, "mouse") == 0) {
        (void)ioctl(g_uinput, UI_SET_EVBIT, EV_REL);
        (void)ioctl(g_uinput, UI_SET_RELBIT, REL_X);
        (void)ioctl(g_uinput, UI_SET_RELBIT, REL_Y);
        // Advertise the common mouse button family as well as the selected target so Android does
        // not classify a side-button-only virtual device as a generic keyboard.
        for (int code = BTN_LEFT; code <= BTN_TASK; ++code) {
            (void)ioctl(g_uinput, UI_SET_KEYBIT, code);
        }
    } else if (strcmp(kind, "gamepad") == 0) {
        // A virtual controller with only one EV_KEY bit is classified inconsistently by OEM input
        // stacks.  Advertise the normal gamepad button family; only g_code is ever emitted.
        for (int code = BTN_SOUTH; code <= BTN_THUMBR; ++code) {
            (void)ioctl(g_uinput, UI_SET_KEYBIT, code);
        }
        for (int code = 704; code <= 707; ++code) {
            (void)ioctl(g_uinput, UI_SET_KEYBIT, code);
        }
        for (int code = 0x220; code <= 0x223; ++code) {
            (void)ioctl(g_uinput, UI_SET_KEYBIT, code);
        }
    }

    uinput_setup setup{};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x4B44;
    setup.id.product = strcmp(kind, "mouse") == 0 ? 0x1011
            : (strcmp(kind, "gamepad") == 0 ? 0x1012 : 0x1010);
    setup.id.version = 1;
    const char* name = strcmp(kind, "mouse") == 0 ? "Axon Input Virtual Sync Mouse"
            : (strcmp(kind, "gamepad") == 0 ? "Axon Input Virtual Sync Gamepad"
            : "Axon Input Virtual Sync Keyboard");
    snprintf(setup.name, sizeof(setup.name), "%s", name);
    (void)ioctl(g_uinput, UI_SET_PHYS, "axon-input/virtual/sync-mapper");
    if (ioctl(g_uinput, UI_DEV_SETUP, &setup) < 0 || ioctl(g_uinput, UI_DEV_CREATE) < 0) {
        cleanup();
        return false;
    }
    usleep(60000);
    return true;
}
}

int main(int argc, char** argv) {
    const char* kind = "keyboard";
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--kind") == 0 && i + 1 < argc) kind = argv[++i];
        else if (strcmp(argv[i], "--code") == 0 && i + 1 < argc) g_code = atoi(argv[++i]);
    }
    if (g_code <= 0 || g_code > KEY_MAX) return 2;
    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (!setupDevice(kind)) {
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        return 5;
    }
    printf("READY\n");
    fflush(stdout);

    char line[16];
    while (!g_stop && fgets(line, sizeof(line), stdin) != nullptr) {
        if (line[0] == 'D') (void)emitValue(1);
        else if (line[0] == 'U') (void)emitValue(0);
        else if (line[0] == 'R' && g_down) (void)emitValue(2);
    }
    cleanup();
    return 0;
}
