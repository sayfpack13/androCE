#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/time.h>
#include <dlfcn.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <errno.h>
#include <stdint.h>
#include <pthread.h>

#ifndef SYS_clock_gettime
#define SYS_clock_gettime __NR_clock_gettime
#endif
#ifndef SYS_gettimeofday
#define SYS_gettimeofday __NR_gettimeofday
#endif

#define LOG_TAG "SpeedHook"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define SHM_PATH "/data/local/tmp/speedhack_shm"
#define SHM_SIZE 8
#define DEFAULT_SPEED 1.0f
#define MAX_SPEED 100.0f

#define HOOK_MODE_MONITOR 0u
#define HOOK_MODE_LIBC 1u
#define HOOK_MODE_PLT_GAME 2u
#define HOOK_MODE_PLT_UNIVERSAL 3u
#define HOOK_MODE_PLT_CLOCK_ONLY 4u

#define HOOK_PASS_INITIAL_SEC 5
#define HOOK_RESCAN_INTERVAL_SEC 12
#define HOOK_RESCAN_COUNT 4

/* Bump when hook strategy changes — visible in logcat to confirm deploy. */
#define SPEEDHOOK_BUILD "20260518-crashfix10"

extern int hook_stub_clock_gettime(clockid_t clk_id, struct timespec *tp);
extern int hook_stub_gettimeofday(struct timeval *tv, struct timezone *tz);
extern int got_hook_plt_all(const char *symbol, void *replacement, void **original);
extern void got_hook_reset_module_tracking(void);
extern void speedhook_set_plt_allowlist(int enable);

static volatile float g_speed_multiplier = DEFAULT_SPEED;
static volatile uint32_t g_hook_mode = HOOK_MODE_PLT_GAME;
static volatile int g_hooks_installed = 0;

static volatile int g_rebase_pending = 0;
static struct timespec g_base_monotonic = {0, 0};
static struct timeval g_base_timeval = {0, 0};

static int g_shm_fd = -1;
static volatile float *g_shm_speed = NULL;
static volatile uint32_t *g_shm_mode = NULL;

static void init_base_times(void) {
    syscall(SYS_clock_gettime, CLOCK_MONOTONIC, &g_base_monotonic);
    syscall(SYS_gettimeofday, &g_base_timeval, NULL);
}

static void read_shm_config(void) {
    if (g_shm_speed != NULL) {
        float s = *g_shm_speed;
        if (s >= 0.1f && s <= MAX_SPEED) g_speed_multiplier = s;
    }
    if (g_shm_mode != NULL) {
        uint32_t m = *g_shm_mode;
        if (m <= HOOK_MODE_PLT_CLOCK_ONLY) g_hook_mode = m;
    }
}

static void update_speed_from_shm(void) {
    if (g_shm_speed == NULL) return;
    float new_speed = *g_shm_speed;
    if (new_speed < 0.1f || new_speed > MAX_SPEED) return;
    if (new_speed != g_speed_multiplier) {
        g_speed_multiplier = new_speed;
        g_rebase_pending = 1;
    }
    if (g_shm_mode != NULL) {
        uint32_t m = *g_shm_mode;
        if (m <= HOOK_MODE_PLT_CLOCK_ONLY && m != g_hook_mode) {
            g_hook_mode = m;
        }
    }
}

static void maybe_rebase(void) {
    if (g_rebase_pending) {
        g_rebase_pending = 0;
        init_base_times();
    }
}

static void scale_timespec(struct timespec *tp, const struct timespec *base) {
    float speed = g_speed_multiplier;
    if (speed <= 1.01f && speed >= 0.99f) return;

    long long base_ns = (long long)base->tv_sec * 1000000000LL + base->tv_nsec;
    long long curr_ns = (long long)tp->tv_sec * 1000000000LL + tp->tv_nsec;
    long long diff_ns = curr_ns - base_ns;
    if (diff_ns < 0) diff_ns = 0;

    long long scaled_diff = (long long)((double)diff_ns * (double)speed);
    long long result_ns = base_ns + scaled_diff;
    tp->tv_sec = result_ns / 1000000000LL;
    tp->tv_nsec = result_ns % 1000000000LL;
}

static void scale_timeval(struct timeval *tv, const struct timeval *base) {
    float speed = g_speed_multiplier;
    if (speed <= 1.01f && speed >= 0.99f) return;

    long long base_us = (long long)base->tv_sec * 1000000LL + base->tv_usec;
    long long curr_us = (long long)tv->tv_sec * 1000000LL + tv->tv_usec;
    long long diff_us = curr_us - base_us;
    if (diff_us < 0) diff_us = 0;

    long long scaled_diff = (long long)((double)diff_us * (double)speed);
    long long result_us = base_us + scaled_diff;
    tv->tv_sec = result_us / 1000000LL;
    tv->tv_usec = result_us % 1000000LL;
}

void speedhook_apply_clock(clockid_t clk_id, struct timespec *tp) {
    if (g_hook_mode == HOOK_MODE_MONITOR) return;
    update_speed_from_shm();
    maybe_rebase();
    if (clk_id == CLOCK_MONOTONIC || clk_id == CLOCK_MONOTONIC_RAW ||
        clk_id == CLOCK_BOOTTIME) {
        scale_timespec(tp, &g_base_monotonic);
    }
}

void speedhook_apply_timeval(struct timeval *tv) {
    if (g_hook_mode == HOOK_MODE_MONITOR) return;
    update_speed_from_shm();
    maybe_rebase();
    scale_timeval(tv, &g_base_timeval);
}

static int install_got_hooks(uint32_t mode) {
    if (mode == HOOK_MODE_MONITOR) return 0;

    speedhook_set_plt_allowlist(mode == HOOK_MODE_PLT_GAME ? 1 : 0);
    got_hook_reset_module_tracking();

    int clk = got_hook_plt_all("clock_gettime", (void *)hook_stub_clock_gettime, NULL);
    int tv = 0;
    if (mode == HOOK_MODE_PLT_UNIVERSAL || mode == HOOK_MODE_LIBC) {
        tv = got_hook_plt_all("gettimeofday", (void *)hook_stub_gettimeofday, NULL);
    }

    LOGI("GOT hooks build=%s clock=%d tv=%d mode=%u", SPEEDHOOK_BUILD, clk, tv, mode);
    return clk + tv;
}

static void install_hooks_for_current_mode(void) {
    read_shm_config();

    switch (g_hook_mode) {
        case HOOK_MODE_MONITOR:
            LOGI("Hook mode: MONITOR (no hooks) build=%s", SPEEDHOOK_BUILD);
            return;
        case HOOK_MODE_LIBC:
            LOGI("Hook mode: GOT all app libs build=%s", SPEEDHOOK_BUILD);
            break;
        case HOOK_MODE_PLT_GAME:
            LOGI("Hook mode: GOT game engines build=%s", SPEEDHOOK_BUILD);
            break;
        case HOOK_MODE_PLT_CLOCK_ONLY:
            LOGI("Hook mode: GOT universal clock only build=%s", SPEEDHOOK_BUILD);
            break;
        case HOOK_MODE_PLT_UNIVERSAL:
        default:
            LOGI("Hook mode: GOT universal build=%s", SPEEDHOOK_BUILD);
            break;
    }

    install_got_hooks(g_hook_mode);
    g_hooks_installed = 1;
}

static void install_all_hook_strategies(void) {
    install_hooks_for_current_mode();
}

static void *hook_worker_thread(void *arg) {
    (void)arg;
    sleep(HOOK_PASS_INITIAL_SEC);
    install_all_hook_strategies();

    read_shm_config();
    if (g_hook_mode == HOOK_MODE_MONITOR) {
        return NULL;
    }

    for (int i = 0; i < HOOK_RESCAN_COUNT; i++) {
        sleep(HOOK_RESCAN_INTERVAL_SEC);
        install_got_hooks(g_hook_mode);
    }
    return NULL;
}

static void schedule_hook_install(void) {
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&tid, &attr, hook_worker_thread, NULL) != 0) {
        LOGE("pthread_create failed, installing hooks now");
        install_all_hook_strategies();
    }
    pthread_attr_destroy(&attr);
}

static void open_shm(void) {
    if (g_shm_speed != NULL) return;

    g_shm_fd = open(SHM_PATH, O_RDONLY);
    if (g_shm_fd < 0) {
        LOGE("Failed to open shared memory: %s", strerror(errno));
        return;
    }

    void *mapped = mmap(NULL, SHM_SIZE, PROT_READ, MAP_SHARED, g_shm_fd, 0);
    if (mapped == MAP_FAILED) {
        LOGE("Failed to mmap shared memory");
        g_shm_speed = NULL;
        g_shm_mode = NULL;
        return;
    }

    g_shm_speed = (volatile float *)mapped;
    g_shm_mode = (volatile uint32_t *)((uint8_t *)mapped + 4);
    read_shm_config();
}

__attribute__((constructor))
static void speedhook_init(void) {
    LOGI("speedhook loaded build=%s", SPEEDHOOK_BUILD);
    open_shm();
    if (g_hook_mode == HOOK_MODE_MONITOR) {
        LOGI("MONITOR mode — no hook thread (build=%s)", SPEEDHOOK_BUILD);
        return;
    }
    init_base_times();
    schedule_hook_install();
}
