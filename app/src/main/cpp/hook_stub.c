#define _GNU_SOURCE
#include <unistd.h>
#include <time.h>
#include <sys/time.h>
#include <sys/syscall.h>
#include <stdint.h>
#include <pthread.h>

#ifndef SYS_clock_gettime
#define SYS_clock_gettime __NR_clock_gettime
#endif
#ifndef SYS_gettimeofday
#define SYS_gettimeofday __NR_gettimeofday
#endif

extern void speedhook_apply_clock(clockid_t clk_id, struct timespec *tp);
extern void speedhook_apply_timeval(struct timeval *tv);

static _Thread_local int g_in_stub;

#if defined(__aarch64__)
__attribute__((target("branch-protection=bti")))
#endif
int hook_stub_clock_gettime(clockid_t clk_id, struct timespec *tp) {
    if (g_in_stub) {
        return syscall(SYS_clock_gettime, clk_id, tp);
    }
    g_in_stub = 1;

    int ret = syscall(SYS_clock_gettime, clk_id, tp);
    if (ret == 0 && tp != NULL) {
        speedhook_apply_clock(clk_id, tp);
    }

    g_in_stub = 0;
    return ret;
}

#if defined(__aarch64__)
__attribute__((target("branch-protection=bti")))
#endif
int hook_stub_gettimeofday(struct timeval *tv, struct timezone *tz) {
    (void)tz;
    if (g_in_stub) {
        return syscall(SYS_gettimeofday, tv, tz);
    }
    g_in_stub = 1;

    int ret = syscall(SYS_gettimeofday, tv, tz);
    if (ret == 0 && tv != NULL) {
        speedhook_apply_timeval(tv);
    }

    g_in_stub = 0;
    return ret;
}
