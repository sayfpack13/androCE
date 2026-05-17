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
#include <sys/stat.h>
#include <sys/syscall.h>
#include <errno.h>

// Android NDK doesn't expose __NR_ constants directly in some versions
// Use SYS_ constants from syscall.h
#ifndef SYS_clock_gettime
#define SYS_clock_gettime __NR_clock_gettime
#endif
#ifndef SYS_gettimeofday
#define SYS_gettimeofday __NR_gettimeofday
#endif

#define LOG_TAG "SpeedHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SHM_PATH "/data/local/tmp/speedhack_shm"
#define DEFAULT_SPEED 1.0f
#define MAX_SPEED 100.0f

typedef int (*clock_gettime_t)(clockid_t clk_id, struct timespec *tp);
typedef int (*gettimeofday_t)(struct timeval *tv, struct timezone *tz);
typedef time_t (*time_t_func)(time_t *tloc);

static clock_gettime_t orig_clock_gettime = NULL;
static gettimeofday_t orig_gettimeofday = NULL;
static time_t_func orig_time = NULL;

static volatile float g_speed_multiplier = DEFAULT_SPEED;
static volatile int g_initialized = 0;
static volatile int g_hook_active = 1;

static struct timespec g_base_monotonic = {0, 0};
static struct timespec g_base_real = {0, 0};
static struct timeval g_base_timeval = {0, 0};
static time_t g_base_time = 0;

static int g_shm_fd = -1;
static volatile float* g_shm_speed = NULL;

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
                LOGD("Speed updated: %.2fx", g_speed_multiplier);
            }
        }
    }
}

static void ensure_init(void) {
    if (g_initialized) return;
    
    orig_clock_gettime = (clock_gettime_t)dlsym(RTLD_NEXT, "clock_gettime");
    orig_gettimeofday = (gettimeofday_t)dlsym(RTLD_NEXT, "gettimeofday");
    orig_time = (time_t_func)dlsym(RTLD_NEXT, "time");
    
    init_base_times();
    
    // Use regular file for IPC instead of shm_open (not available on Android)
    g_shm_fd = open(SHM_PATH, O_RDONLY, 0666);
    if (g_shm_fd >= 0) {
        g_shm_speed = mmap(NULL, sizeof(float), PROT_READ, MAP_SHARED, g_shm_fd, 0);
        if (g_shm_speed == MAP_FAILED) {
            g_shm_speed = NULL;
            LOGE("Failed to mmap shared memory");
        } else {
            LOGD("Shared memory mapped successfully");
        }
    } else {
        LOGE("Failed to open shared memory: %s", strerror(errno));
    }
    
    g_initialized = 1;
    LOGD("SpeedHook initialized, speed=%.2f", g_speed_multiplier);
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

int clock_gettime(clockid_t clk_id, struct timespec *tp) {
    ensure_init();
    update_speed_from_shm();
    
    int ret;
    if (orig_clock_gettime) {
        ret = orig_clock_gettime(clk_id, tp);
    } else {
        ret = syscall(SYS_clock_gettime, clk_id, tp);
    }
    
    if (ret == 0) {
        if (clk_id == CLOCK_MONOTONIC || clk_id == CLOCK_MONOTONIC_RAW) {
            scale_timespec(tp, &g_base_monotonic);
        } else if (clk_id == CLOCK_REALTIME) {
            scale_timespec(tp, &g_base_real);
        }
    }
    
    return ret;
}

int gettimeofday(struct timeval *tv, struct timezone *tz) {
    ensure_init();
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

time_t time(time_t *tloc) {
    ensure_init();
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
    
    if (tloc != NULL) {
        *tloc = ret;
    }
    
    return ret;
}

// Speed control can be set via shared memory - no JNI needed for now
// Keeping this simple to avoid JNI complexity

// Constructor attribute to auto-init
__attribute__((constructor))
static void speedhook_init(void) {
    LOGD("SpeedHook library loaded");
    ensure_init();
}
