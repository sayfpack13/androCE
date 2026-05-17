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
#include <pthread.h>

#ifndef SYS_clock_gettime
#define SYS_clock_gettime __NR_clock_gettime
#endif
#ifndef SYS_gettimeofday
#define SYS_gettimeofday __NR_gettimeofday
#endif

#define LOG_TAG "SpeedHook"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SHM_PATH "/data/local/tmp/speedhack_shm"
#define DEFAULT_SPEED 1.0f
#define MAX_SPEED 100.0f

typedef int (*clock_gettime_fn)(clockid_t clk_id, struct timespec *tp);
typedef int (*gettimeofday_fn)(struct timeval *tv, struct timezone *tz);
typedef time_t (*time_fn)(time_t *tloc);

static clock_gettime_fn orig_clock_gettime = NULL;
static gettimeofday_fn orig_gettimeofday = NULL;
static time_fn orig_time = NULL;

static volatile float g_speed_multiplier = DEFAULT_SPEED;
static volatile int g_initialized = 0;
static volatile int g_hook_active = 1;
static volatile int g_hooks_installed = 0;

static struct timespec g_base_monotonic = {0, 0};
static struct timespec g_base_real = {0, 0};
static struct timeval g_base_timeval = {0, 0};
static time_t g_base_time = 0;

static int g_shm_fd = -1;
static volatile float *g_shm_speed = NULL;

extern int arm64_hook(void *target, void *replacement, void **orig_trampoline);

static void init_base_times(void) {
    syscall(SYS_clock_gettime, CLOCK_MONOTONIC, &g_base_monotonic);
    syscall(SYS_clock_gettime, CLOCK_REALTIME, &g_base_real);
    syscall(SYS_gettimeofday, &g_base_timeval, NULL);
    g_base_time = g_base_timeval.tv_sec;
}

static void update_speed_from_shm(void) {
    if (g_shm_speed != NULL) {
        float new_speed = *g_shm_speed;
        if (new_speed >= 0.1f && new_speed <= MAX_SPEED) {
            if (new_speed != g_speed_multiplier) {
                init_base_times();
                g_speed_multiplier = new_speed;
            }
        }
    }
}

static void scale_timespec(struct timespec *tp, const struct timespec *base) {
    if (!g_hook_active || g_speed_multiplier == 1.0f) return;

    long long base_ns = (long long)base->tv_sec * 1000000000LL + base->tv_nsec;
    long long curr_ns = (long long)tp->tv_sec * 1000000000LL + tp->tv_nsec;
    long long diff_ns = curr_ns - base_ns;
    if (diff_ns < 0) diff_ns = 0;

    long long scaled_diff = (long long)((double)diff_ns * g_speed_multiplier);
    long long result_ns = base_ns + scaled_diff;
    tp->tv_sec = result_ns / 1000000000LL;
    tp->tv_nsec = result_ns % 1000000000LL;
}

static void scale_timeval(struct timeval *tv, const struct timeval *base) {
    if (!g_hook_active || g_speed_multiplier == 1.0f) return;

    long long base_us = (long long)base->tv_sec * 1000000LL + base->tv_usec;
    long long curr_us = (long long)tv->tv_sec * 1000000LL + tv->tv_usec;
    long long diff_us = curr_us - base_us;
    if (diff_us < 0) diff_us = 0;

    long long scaled_diff = (long long)((double)diff_us * g_speed_multiplier);
    long long result_us = base_us + scaled_diff;
    tv->tv_sec = result_us / 1000000LL;
    tv->tv_usec = result_us % 1000000LL;
}

static int hooked_clock_gettime(clockid_t clk_id, struct timespec *tp) {
    update_speed_from_shm();

    int ret;
    if (orig_clock_gettime) {
        ret = orig_clock_gettime(clk_id, tp);
    } else {
        ret = syscall(SYS_clock_gettime, clk_id, tp);
    }

    if (ret == 0 && tp != NULL) {
        if (clk_id == CLOCK_MONOTONIC || clk_id == CLOCK_MONOTONIC_RAW ||
            clk_id == CLOCK_BOOTTIME) {
            scale_timespec(tp, &g_base_monotonic);
        } else if (clk_id == CLOCK_REALTIME) {
            scale_timespec(tp, &g_base_real);
        }
    }
    return ret;
}

static int hooked_gettimeofday(struct timeval *tv, struct timezone *tz) {
    update_speed_from_shm();

    int ret;
    if (orig_gettimeofday) {
        ret = orig_gettimeofday(tv, tz);
    } else {
        ret = syscall(SYS_gettimeofday, tv, tz);
    }

    if (ret == 0 && tv != NULL) {
        scale_timeval(tv, &g_base_timeval);
    }
    return ret;
}

static time_t hooked_time(time_t *tloc) {
    update_speed_from_shm();

    time_t ret;
    if (orig_time) {
        ret = orig_time(tloc);
    } else {
        struct timeval tv;
        syscall(SYS_gettimeofday, &tv, NULL);
        ret = tv.tv_sec;
    }

    if (g_hook_active && g_speed_multiplier != 1.0f) {
        time_t base = g_base_time;
        time_t diff = ret - base;
        if (diff < 0) diff = 0;
        ret = base + (time_t)((double)diff * g_speed_multiplier);
    }

    if (tloc != NULL) *tloc = ret;
    return ret;
}

static void install_hooks(void) {
    void *libc = dlopen("libc.so", RTLD_NOW | RTLD_NOLOAD);
    if (!libc) libc = dlopen("libc.so", RTLD_NOW);
    if (!libc) {
        LOGE("Failed to open libc.so");
        return;
    }

    void *sym;

    sym = dlsym(libc, "clock_gettime");
    if (sym && arm64_hook(sym, (void *)hooked_clock_gettime, (void **)&orig_clock_gettime) != 0) {
        LOGE("Failed to hook clock_gettime");
        orig_clock_gettime = (clock_gettime_fn)sym;
    }

    sym = dlsym(libc, "gettimeofday");
    if (sym && arm64_hook(sym, (void *)hooked_gettimeofday, (void **)&orig_gettimeofday) != 0) {
        LOGE("Failed to hook gettimeofday");
        orig_gettimeofday = (gettimeofday_fn)sym;
    }

    sym = dlsym(libc, "time");
    if (sym && arm64_hook(sym, (void *)hooked_time, (void **)&orig_time) != 0) {
        LOGE("Failed to hook time");
        orig_time = (time_fn)sym;
    }

    dlclose(libc);
}

static void open_shm_only(void) {
    if (g_shm_speed != NULL) return;

    g_shm_fd = open(SHM_PATH, O_RDONLY);
    if (g_shm_fd >= 0) {
        g_shm_speed = mmap(NULL, sizeof(float), PROT_READ, MAP_SHARED, g_shm_fd, 0);
        if (g_shm_speed == MAP_FAILED) {
            g_shm_speed = NULL;
            LOGE("Failed to mmap shared memory");
        }
    } else {
        LOGE("Failed to open shared memory: %s", strerror(errno));
    }
}

/* Install libc hooks only after the game is running (not during ptrace freeze). */
static void *deferred_install_thread(void *arg) {
    (void)arg;
    usleep(2500000); /* 2.5s — let the process resume fully after injector detach */
    if (g_hooks_installed) return NULL;

    init_base_times();
    install_hooks();
    g_hooks_installed = 1;
    g_initialized = 1;
    return NULL;
}

static void schedule_deferred_hooks(void) {
    if (g_hooks_installed) return;

    pthread_t th;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &attr, deferred_install_thread, NULL) != 0) {
        LOGE("pthread_create for deferred hooks failed");
    }
    pthread_attr_destroy(&attr);
}

static void ensure_init(void) {
    if (!g_initialized && g_hooks_installed) g_initialized = 1;
    update_speed_from_shm();
}

__attribute__((constructor))
static void speedhook_init(void) {
    open_shm_only();
    schedule_deferred_hooks();
}
