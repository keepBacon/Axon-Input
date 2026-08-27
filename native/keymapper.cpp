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
bool g_down[KEY_MAX + 1]{};

void onSignal(int) { g_stop = 1; }

bool writeEvent(__u16 type, __u16 code, __s32 value) {
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    return write(g_uinput, &event, sizeof(event)) == static_cast<ssize_t>(sizeof(event));
}

bool emitKey(int code, bool down) {
    if (code <= 0 || code > KEY_MAX) return false;
    if (g_down[code] == down) return true;
    if (!writeEvent(EV_KEY, static_cast<__u16>(code), down ? 1 : 0)) return false;
    if (!writeEvent(EV_SYN, SYN_REPORT, 0)) return false;
    g_down[code] = down;
    return true;
}

void cleanup() {
    if (g_uinput < 0) return;
    bool any = false;
    for (int code = 1; code <= KEY_MAX; ++code) {
        if (!g_down[code]) continue;
        input_event event{};
        event.type = EV_KEY;
        event.code = static_cast<__u16>(code);
        event.value = 0;
        (void)write(g_uinput, &event, sizeof(event));
        g_down[code] = false;
        any = true;
    }
    if (any) {
        input_event sync{};
        sync.type = EV_SYN;
        sync.code = SYN_REPORT;
        (void)write(g_uinput, &sync, sizeof(sync));
        usleep(6000);
    }
    (void)ioctl(g_uinput, UI_DEV_DESTROY);
    close(g_uinput);
    g_uinput = -1;
}
}

int main() {
    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);

    g_uinput = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_uinput < 0) {
        fprintf(stderr, "uinput open failed: %s\n", strerror(errno));
        return 3;
    }
    (void)ioctl(g_uinput, UI_SET_EVBIT, EV_KEY);
    (void)ioctl(g_uinput, UI_SET_EVBIT, EV_REP);
    for (int code = 1; code < BTN_MISC; ++code) (void)ioctl(g_uinput, UI_SET_KEYBIT, code);

    uinput_setup setup{};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x4B44;
    setup.id.product = 0x1005;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "%s", "Axon Input Virtual Gamepad Mapper");
    (void)ioctl(g_uinput, UI_SET_PHYS, "axon-input/virtual/gamepad-mapper");
    if (ioctl(g_uinput, UI_DEV_SETUP, &setup) < 0 || ioctl(g_uinput, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        cleanup();
        return 5;
    }
    usleep(60000);
    printf("READY\n");
    fflush(stdout);

    char line[64];
    while (!g_stop && fgets(line, sizeof(line), stdin) != nullptr) {
        char op = 0;
        int code = -1;
        if (sscanf(line, " %c %d", &op, &code) != 2) continue;
        bool ok = false;
        if (op == 'D') ok = emitKey(code, true);
        else if (op == 'U') ok = emitKey(code, false);
        else if (op == 'R') {
            if (code > 0 && code <= KEY_MAX && g_down[code]) {
                ok = writeEvent(EV_KEY, static_cast<__u16>(code), 2)
                        && writeEvent(EV_SYN, SYN_REPORT, 0);
            }
        }
        if (!ok && (op == 'D' || op == 'U' || op == 'R')) {
            fprintf(stderr, "emit failed code=%d errno=%d\n", code, errno);
            fflush(stderr);
        }
    }
    cleanup();
    return 0;
}
