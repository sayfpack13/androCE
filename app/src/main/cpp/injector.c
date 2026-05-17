#define _GNU_SOURCE
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <sys/syscall.h>

#ifndef __NR_process_vm_writev
#if defined(__aarch64__)
#define __NR_process_vm_writev 271
#elif defined(__arm__)
#define __NR_process_vm_writev 377
#elif defined(__x86_64__)
#define __NR_process_vm_writev 311
#else
#define __NR_process_vm_writev 311
#endif
#endif

static ssize_t process_vm_writev_wrapper(pid_t pid,
                                         const struct iovec *local,
                                         unsigned long liovcnt,
                                         const struct iovec *remote,
                                         unsigned long riovcnt,
                                         unsigned long flags) {
    return syscall(__NR_process_vm_writev, pid, local, liovcnt, remote, riovcnt, flags);
}
#include <android/log.h>
#include <errno.h>
#include <signal.h>
#include <elf.h>

#define LOG_TAG "SpeedInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define NT_PRSTATUS 1
#define RTLD_NOW    2

/* ARM64 user_pt_regs */
struct pt_regs_arm64 {
    unsigned long long regs[31];
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static void out(const char *msg) {
    fputs(msg, stdout);
    fputc('\n', stdout);
    fflush(stdout);
    LOGI("%s", msg);
}

static int remote_write(int pid, unsigned long addr, const void *buf, size_t len) {
    struct iovec local = { .iov_base = (void *)buf, .iov_len = len };
    struct iovec remote = { .iov_base = (void *)addr, .iov_len = len };
    ssize_t n = process_vm_writev_wrapper(pid, &local, 1, &remote, 1, 0);
    if (n == (ssize_t)len) return 0;

    char mem_path[64];
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);
    int fd = open(mem_path, O_RDWR);
    if (fd < 0) return -1;
    ssize_t w = pwrite(fd, buf, len, (off_t)addr);
    close(fd);
    return (w == (ssize_t)len) ? 0 : -1;
}

static unsigned long find_sym_in_maps(int pid, const char *lib_substr, const char *sym_name) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long result = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, lib_substr)) continue;
        if (!strstr(line, "r-xp") && !strstr(line, "r--p")) continue;

        unsigned long map_start = 0, map_end = 0, map_offset = 0;
        char perms[8], dev[16], path[256] = {0};
        unsigned long inode = 0;
        if (sscanf(line, "%lx-%lx %4s %lx %s %lu %255s",
                   &map_start, &map_end, perms, &map_offset, dev, &inode, path) < 3)
            continue;
        if (path[0] == '\0') continue;

        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;

        unsigned char ehdr[64];
        if (pread(fd, ehdr, sizeof(ehdr), 0) < (int)sizeof(ehdr)) { close(fd); continue; }
        if (ehdr[0] != 0x7f || ehdr[1] != 'E') { close(fd); continue; }

        int is64 = (ehdr[4] == 2);
        unsigned long sh_off = is64 ? *(unsigned long *)(ehdr + 40) : *(unsigned int *)(ehdr + 32);
        unsigned short sh_entsz = is64 ? *(unsigned short *)(ehdr + 58) : *(unsigned short *)(ehdr + 46);
        unsigned short sh_num = is64 ? *(unsigned short *)(ehdr + 60) : *(unsigned short *)(ehdr + 48);

        unsigned long dynsym_off = 0, dynsym_sz = 0, dynstr_off = 0;

        for (unsigned short i = 0; i < sh_num; i++) {
            unsigned char shdr[64];
            if (pread(fd, shdr, sh_entsz, sh_off + (unsigned long)i * sh_entsz) < (int)sh_entsz)
                break;

            unsigned int sh_type = *(unsigned int *)(shdr + 4);
            unsigned long s_off = is64 ? *(unsigned long *)(shdr + 24) : *(unsigned int *)(shdr + 16);
            unsigned long s_sz = is64 ? *(unsigned long *)(shdr + 32) : *(unsigned int *)(shdr + 20);
            unsigned int s_link = is64 ? *(unsigned int *)(shdr + 40) : *(unsigned int *)(shdr + 24);

            if (sh_type == SHT_DYNSYM) {
                dynsym_off = s_off;
                dynsym_sz = s_sz;
                unsigned char strshdr[64];
                if (pread(fd, strshdr, sh_entsz, sh_off + s_link * sh_entsz) >= (int)sh_entsz) {
                    dynstr_off = is64 ? *(unsigned long *)(strshdr + 24) : *(unsigned int *)(strshdr + 16);
                }
                break;
            }
        }

        if (dynsym_off == 0 || dynstr_off == 0) { close(fd); continue; }

        unsigned long sym_entsz = is64 ? 24 : 16;
        unsigned long sym_count = dynsym_sz / sym_entsz;

        for (unsigned long si = 0; si < sym_count; si++) {
            unsigned char sym[24];
            if (pread(fd, sym, sym_entsz, dynsym_off + si * sym_entsz) < (int)sym_entsz) break;

            unsigned int name_off = *(unsigned int *)(sym + 0);
            unsigned long sym_val = is64 ? *(unsigned long *)(sym + 8) : *(unsigned int *)(sym + 4);

            char sym_buf[128] = {0};
            pread(fd, sym_buf, sizeof(sym_buf) - 1, dynstr_off + name_off);

            if (strcmp(sym_buf, sym_name) == 0 && sym_val != 0) {
                result = map_start + sym_val - map_offset;
                LOGD("Found %s at 0x%lx in %s", sym_name, result, path);
                break;
            }
        }
        close(fd);
        if (result) break;
    }
    fclose(fp);
    return result;
}

static unsigned long find_dlopen_addr(int pid) {
    static const char *syms[] = {
        "__loader_dlopen",
        "android_dlopen_ext",
        "dlopen",
        NULL
    };
    static const char *libs[] = {
        "linker64",
        "linker",
        "libdl.so",
        "libc.so",
        NULL
    };

    for (int l = 0; libs[l]; l++) {
        for (int s = 0; syms[s]; s++) {
            unsigned long addr = find_sym_in_maps(pid, libs[l], syms[s]);
            if (addr) {
                LOGI("Using %s from %s at 0x%lx", syms[s], libs[l], addr);
                return addr;
            }
        }
    }
    return 0;
}

static unsigned long find_rw_region(int pid, unsigned long *out_size) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long best = 0;
    unsigned long best_size = 0;

    while (fgets(line, sizeof(line), fp)) {
        unsigned long start, end;
        char perms[8];
        char path[256] = {0};
        int n = sscanf(line, "%lx-%lx %4s %*s %*s %*s %255s", &start, &end, perms, path);
        unsigned long size = end - start;
        if (size < 4096) continue;
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        int is_anon = (n < 4) || path[0] == '\0' || path[0] == '[';
        if (is_anon && size > best_size) {
            best = start;
            best_size = size;
        }
    }
    fclose(fp);
    *out_size = best_size;
    return best;
}

static int is_lib_loaded(int pid, const char *lib_name) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    int found = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, lib_name)) { found = 1; break; }
    }
    fclose(fp);
    return found;
}

static int arm64_inject(int pid, unsigned long dlopen_addr, const char *lib_path,
                        unsigned long long *out_dlopen_ret) {
    unsigned long region_size = 0;
    unsigned long region = find_rw_region(pid, &region_size);
    if (!region || region_size < 512) {
        LOGE("No suitable RW region");
        return -1;
    }

    size_t path_len = strlen(lib_path) + 1;
    unsigned long sc_off = (path_len + 7) & ~7UL;
    unsigned long path_addr = region;
    unsigned long sc_addr = region + sc_off;
    unsigned long path_ptr = path_addr;
    unsigned long dlopen_ptr = dlopen_addr;

    unsigned int sc[10];
    sc[0] = 0x580000C0U; /* ldr x0, #24 */
    sc[1] = 0x52800041U; /* mov w1, #2  RTLD_NOW */
    sc[2] = 0x580000C2U; /* ldr x2, #24 */
    sc[3] = 0xD63F0040U; /* blr x2 */
    sc[4] = 0xD4200000U; /* brk #0 */
    sc[5] = 0x00000000U;
    memcpy(&sc[6], &path_ptr, sizeof(unsigned long));
    memcpy(&sc[8], &dlopen_ptr, sizeof(unsigned long));

    if (remote_write(pid, path_addr, lib_path, path_len) < 0) {
        LOGE("Failed to write library path");
        return -1;
    }
    if (remote_write(pid, sc_addr, sc, sizeof(sc)) < 0) {
        LOGE("Failed to write shellcode");
        return -1;
    }

    struct pt_regs_arm64 orig_regs, new_regs, result_regs;
    struct iovec iov = { &orig_regs, sizeof(orig_regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void *)(long)NT_PRSTATUS, &iov) < 0) {
        LOGE("PTRACE_GETREGSET: %s", strerror(errno));
        return -1;
    }

    new_regs = orig_regs;
    new_regs.pc = sc_addr;
    new_regs.pstate &= ~0x20ULL;

    iov.iov_base = &new_regs;
    if (ptrace(PTRACE_SETREGSET, pid, (void *)(long)NT_PRSTATUS, &iov) < 0) {
        LOGE("PTRACE_SETREGSET: %s", strerror(errno));
        return -1;
    }

    ptrace(PTRACE_CONT, pid, NULL, NULL);
    int status = 0;
    if (waitpid(pid, &status, WUNTRACED) < 0) {
        LOGE("waitpid after shellcode: %s", strerror(errno));
        return -1;
    }

    iov.iov_base = &result_regs;
    ptrace(PTRACE_GETREGSET, pid, (void *)(long)NT_PRSTATUS, &iov);
    if (out_dlopen_ret) *out_dlopen_ret = result_regs.regs[0];

    iov.iov_base = &orig_regs;
    ptrace(PTRACE_SETREGSET, pid, (void *)(long)NT_PRSTATUS, &iov);

    if (WIFSTOPPED(status)) {
        int sig = WSTOPSIG(status);
        LOGD("Stopped with signal %d, dlopen ret=0x%llx", sig, result_regs.regs[0]);
        if (sig != SIGTRAP && sig != SIGSTOP) {
            LOGE("Unexpected signal %d", sig);
            return -1;
        }
    }

    return (result_regs.regs[0] != 0) ? 0 : -1;
}

int inject_library(int pid, const char *lib_path) {
    char msg[256];

    snprintf(msg, sizeof(msg), "ANDROCE_INJECT: start pid=%d lib=%s", pid, lib_path);
    out(msg);

    if (access(lib_path, R_OK) != 0) {
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: FAIL library not readable: %s", strerror(errno));
        out(msg);
        return -1;
    }

    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d", pid);
    if (access(proc_path, F_OK) != 0) {
        out("ANDROCE_INJECT: FAIL process not found");
        return -1;
    }

    if (is_lib_loaded(pid, "libspeedhook")) {
        out("ANDROCE_INJECT: OK already loaded");
        return 0;
    }

    unsigned long dlopen_addr = find_dlopen_addr(pid);
    if (!dlopen_addr) {
        out("ANDROCE_INJECT: FAIL dlopen symbol not found");
        return -1;
    }

    snprintf(msg, sizeof(msg), "ANDROCE_INJECT: dlopen=0x%lx", dlopen_addr);
    out(msg);

    if (ptrace(PTRACE_ATTACH, pid, NULL, NULL) < 0) {
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: FAIL ptrace attach: %s", strerror(errno));
        out(msg);
        return -1;
    }

    int status = 0;
    if (waitpid(pid, &status, WUNTRACED) < 0 || !WIFSTOPPED(status)) {
        out("ANDROCE_INJECT: FAIL process did not stop");
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        return -1;
    }

    unsigned long long dlopen_ret = 0;
    int inject_result = arm64_inject(pid, dlopen_addr, lib_path, &dlopen_ret);
    ptrace(PTRACE_DETACH, pid, NULL, NULL);

    if (inject_result < 0) {
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: FAIL shellcode dlopen returned NULL");
        out(msg);
        return -1;
    }

    usleep(300000);

    if (is_lib_loaded(pid, "libspeedhook")) {
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: OK handle=0x%llx", dlopen_ret);
        out(msg);
        return 0;
    }

    out("ANDROCE_INJECT: FAIL library not in maps after dlopen");
    return -1;
}

int main(int argc, char *argv[]) {
    if (argc != 3) {
        fprintf(stderr, "Usage: %s <pid> <library_path>\n", argv[0]);
        return 1;
    }

    int pid = atoi(argv[1]);
    int r = inject_library(pid, argv[2]);
    return (r == 0) ? 0 : 1;
}
