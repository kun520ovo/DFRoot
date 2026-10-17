#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#define BLKROSET   0x125d
#define KSUD_STAGE "/data/user_de/0/df.root.shizuku.cn/ksud"
#define KSUD       "/data/adb/ksud"
#define PREFS_PATH "/data/user_de/0/df.root.shizuku.cn/shared_prefs/dfroot.xml"
#define REBOOT_GATE_PATH "/data/user_de/0/df.root.shizuku.cn/soft_reboot_gate"
#define MODULES_DIR "/data/adb/modules"

/* Keep this eight-byte protocol in sync with SoftRebootGate.java. */
#define GATE_SIZE 8
#define GATE_AUTH 4
#define GATE_RESULT 5
#define GATE_WAIT 0
#define GATE_ALLOW 1
#define GATE_READY 1
#define GATE_REQUESTED 2
#define GATE_FAILED 3
#define GATE_TIMED_OUT 4

static volatile unsigned char *open_reboot_gate(void)
{
    int fd = open(REBOOT_GATE_PATH, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return NULL;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size != GATE_SIZE) {
        close(fd);
        return NULL;
    }
    void *mapping = mmap(NULL, GATE_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        unsigned char failed = GATE_FAILED;
        (void)pwrite(fd, &failed, 1, GATE_RESULT);
        close(fd);
        return NULL;
    }
    close(fd);
    volatile unsigned char *gate = mapping;
    if (memcmp(mapping, "DFR1", 4) || gate[GATE_AUTH] != GATE_WAIT) {
        gate[GATE_RESULT] = GATE_FAILED;
        munmap(mapping, GATE_SIZE);
        return NULL;
    }
    gate[GATE_RESULT] = GATE_READY;
    return gate;
}

static int pref_true(const char *buf, const char *key)
{
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\"", key);
    char *p = strstr(buf, needle);
    if (!p) return 0;
    char *tag_end = strchr(p, '>');
    char *v = strstr(p, "value=\"true\"");
    return v && tag_end && v < tag_end;
}

static int read_prefs(char *su_manager, size_t su_manager_size, int *soft_reboot,
                      int *disable_modules, int *defer_reboot)
{
    int fd = open(PREFS_PATH, O_RDONLY);
    if (fd < 0) return -1;

    char buf[4096];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';

    char *p = strstr(buf, "name=\"su_manager\">");
    if (!p) return -1;
    p += strlen("name=\"su_manager\">");
    char *end = strchr(p, '<');
    if (!end) return -1;
    size_t len = end - p;
    if (len == 0 || len >= su_manager_size) return -1;
    memcpy(su_manager, p, len);
    su_manager[len] = '\0';

    *soft_reboot = pref_true(buf, "soft_reboot");
    *disable_modules = pref_true(buf, "disable_modules");
    *defer_reboot = pref_true(buf, "defer_soft_reboot");

    return 0;
}

static int adopt_zygote_env(void)
{
    FILE *f = popen("pidof zygote64 zygote", "r");
    if (!f) return -1;
    int pid = 0;
    fscanf(f, "%d", &pid);
    pclose(f);
    if (!pid) return -1;

    char path[32];
    snprintf(path, sizeof(path), "/proc/%d/environ", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    static char buf[16384];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (char *p = buf, *end = buf + n; p < end; p += strlen(p) + 1)
        putenv(p);
    return 0;
}

static int should_ro(const char *name)
{
    size_t len = strlen(name);
    if (!strcmp(name, "super"))  return 1;
    if (!strcmp(name, "misc"))   return 1;
    if (!strcmp(name, "steady")) return 1;
    if (len >= 2 && name[len - 2] == '_' &&
        (name[len - 1] == 'a' || name[len - 1] == 'b'))
        return 1;
    return 0;
}

static int set_partitions_ro(void)
{
    DIR *dir = opendir("/dev/block/by-name");
    if (!dir)
        return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (!should_ro(ent->d_name))
            continue;

        char path[128];
        snprintf(path, sizeof(path), "/dev/block/by-name/%s", ent->d_name);

        int fd = open(path, O_RDONLY);
        if (fd < 0)
            continue;

        struct stat st;
        if (fstat(fd, &st) == 0 && S_ISBLK(st.st_mode)) {
            int on = 1;
            ioctl(fd, BLKROSET, &on);
        }
        close(fd);
    }

    closedir(dir);
    return 0;
}

static int late_load_running(void)
{
    DIR *d = opendir("/proc");
    if (!d)
        return -1;
    struct dirent *e;
    char path[64], buf[256];
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        char *end;
        strtol(e->d_name, &end, 10);
        if (*end) continue;
        snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        int n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        for (int i = 0; i < n; i++)
            if (!buf[i]) buf[i] = ' ';
        if (strstr(buf, "ksud") && strstr(buf, "late-load")) {
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

static int run_ctx(const char *ctx, char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        if (ctx) {
            int fd = open("/proc/self/attr/exec", O_WRONLY);
            if (fd >= 0) { write(fd, ctx, strlen(ctx)); close(fd); }
        }
        execv(argv[0], argv);
        _exit(127);
    }
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static void request_soft_reboot(volatile unsigned char *gate, int deferred)
{
    /* A missing or invalid manual acknowledgement must never become an auto run. */
    if (deferred && !gate) return;
    for (int elapsed = 0; elapsed < 120; elapsed++) {
        if (deferred && gate[GATE_AUTH] != GATE_WAIT && gate[GATE_AUTH] != GATE_ALLOW) {
            gate[GATE_RESULT] = GATE_FAILED;
            return;
        }
        int loading = late_load_running();
        if (loading < 0) {
            if (gate) gate[GATE_RESULT] = GATE_FAILED;
            return;
        }
        if (!loading && (!deferred || gate[GATE_AUTH] == GATE_ALLOW)) {
            int rc = run_ctx("u:r:ksu:s0", (char *[]){ KSUD, "soft-reboot", NULL });
            if (gate) gate[GATE_RESULT] = rc == 0 ? GATE_REQUESTED : GATE_FAILED;
            return;
        }
        sleep(1);
    }
    if (gate) gate[GATE_RESULT] = GATE_TIMED_OUT;
}

static void touch(const char *path)
{
    int fd = open(path, O_CREAT | O_WRONLY, 0666);
    if (fd >= 0)
        close(fd);
}

/* Mark every installed module disabled before ksud runs. A broken module
 * otherwise loads on the next boot and bootloops the device. */
static int disable_modules(void)
{
    DIR *dir = opendir(MODULES_DIR);
    if (!dir)
        return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (ent->d_name[0] == '.')
            continue;
        char path[256];
        snprintf(path, sizeof(path), MODULES_DIR "/%s/disable", ent->d_name);
        touch(path);
    }
    closedir(dir);
    return 0;
}

int main(void)
{
    touch("/dev/dfm1");
    char su_manager[256];
    int soft_reboot, disable_mods, defer_reboot;
    if (read_prefs(su_manager, sizeof(su_manager), &soft_reboot, &disable_mods, &defer_reboot) != 0) {
        touch("/dev/dfme0");
        return 1;
    }
    // Map before KernelSU starts enforcing SELinux. Later acknowledgements need
    // no filesystem reopen or permission change in the init/ksu context.
    volatile unsigned char *reboot_gate = soft_reboot && defer_reboot ? open_reboot_gate() : NULL;

    touch("/dev/dfm2");
    if (adopt_zygote_env())
        touch("/dev/dfmw0");

    touch("/dev/dfm3");
    if (set_partitions_ro())
        touch("/dev/dfmw1");

    if (disable_mods) {
        touch("/dev/dfm4");
        if (disable_modules()) {
            touch("/dev/dfme1");
            return 1;
        }
    }

    touch("/dev/dfm5");
    // The kernel's UMH command sets the init context before bootstrap executes.
    if (run_ctx(NULL, (char *[]){ KSUD_STAGE, "late-load", "--package-name", su_manager, NULL }) == 0) {
        touch("/dev/dfm6");
        if (soft_reboot) {
            request_soft_reboot(reboot_gate, defer_reboot);
        }
    } else {
        touch("/dev/dfme2");
    }
    if (reboot_gate) munmap((void *)reboot_gate, GATE_SIZE);

    return 0;
}
