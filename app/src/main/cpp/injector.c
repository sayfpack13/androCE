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
#include <dirent.h>

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
#include <stdint.h>

#define LOG_TAG "SpeedInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define NT_PRSTATUS 1
#define RTLD_NOW    2

#ifndef __WALL
#define __WALL 0x40000000
#endif

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
                break;
            }
        }
        close(fd);
        if (result) break;
    }
    fclose(fp);
    return result;
}

enum dlopen_kind {
    DLOPEN_2ARG = 0,
    DLOPEN_LOADER_3ARG = 1,
    DLOPEN_SHELLCODE = 2, /* PC at cave; args already in the stub */
};

#define REMOTE_CALL_TIMEOUT_MS 12000
#define REMOTE_CALL_POLL_MS    50

struct dlopen_target {
    unsigned long addr;
    enum dlopen_kind kind;
    unsigned long caller_addr;
};

static unsigned long find_map_base(int pid, const char *lib_substr, const char *perm) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long base = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, lib_substr)) continue;
        if (perm && !strstr(line, perm)) continue;
        unsigned long start = 0, end = 0;
        if (sscanf(line, "%lx-%lx", &start, &end) == 2) {
            base = start;
            break;
        }
    }
    fclose(fp);
    return base;
}

static struct dlopen_target resolve_dlopen(int pid) {
    struct dlopen_target target = {0};

    static const struct {
        const char *lib;
        const char *sym;
        enum dlopen_kind kind;
    } candidates[] = {
        { "libdl.so", "dlopen", DLOPEN_2ARG },
        { "libc.so", "dlopen", DLOPEN_2ARG },
        { "linker64", "__loader_dlopen", DLOPEN_LOADER_3ARG },
        { "linker", "__loader_dlopen", DLOPEN_LOADER_3ARG },
        { NULL, NULL, DLOPEN_2ARG }
    };

    for (int i = 0; candidates[i].lib; i++) {
        unsigned long addr = find_sym_in_maps(pid, candidates[i].lib, candidates[i].sym);
        if (!addr) continue;

        target.addr = addr;
        target.kind = candidates[i].kind;
        if (target.kind == DLOPEN_LOADER_3ARG) {
            unsigned long linker_base = find_map_base(pid, "linker64", "r-xp");
            if (!linker_base) linker_base = find_map_base(pid, "linker", "r-xp");
            target.caller_addr = linker_base ? linker_base + 0x1000 : addr;
        }
        return target;
    }
    return target;
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

static int is_plausible_dlopen_handle(unsigned long long h) {
    if (h == 0 || h == (unsigned long long)-1) return 0;
    if (h & 7ULL) return 0;
    if (h < 0x10000ULL) return 0;
    if (h > 0x0000FFFFFFFFFFFFULL) return 0;
    return 1;
}

static int is_lib_loaded(int pid, const char *lib_name) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    int found = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, lib_name)) continue;
        if (!strstr(line, ".so")) continue;
        if (strstr(line, "r-xp") || strstr(line, "r--p") || strstr(line, "rw-p")) {
            found = 1;
            break;
        }
    }
    fclose(fp);
    return found;
}

static int is_brk_insn(uint32_t insn) {
    return (insn & 0xFFE0001FU) == 0xD4200000U;
}

/* Scan on-disk ELF (from maps path) — fast and works when /proc/pid/mem is unreadable. */
static unsigned long scan_file_for_brk(const char *path, unsigned long map_start,
                                       unsigned long file_off, unsigned long map_size) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;

    unsigned long limit = map_size;
    if (limit > 256 * 1024) limit = 256 * 1024;

    unsigned char buf[4096];
    for (unsigned long off = 0; off + 4 <= limit; off += sizeof(buf) - 4) {
        size_t chunk = limit - off;
        if (chunk > sizeof(buf)) chunk = sizeof(buf);
        if (pread(fd, buf, chunk, (off_t)(file_off + off)) < 4) break;

        for (size_t j = 0; j + 4 <= chunk; j += 4) {
            uint32_t insn = *(uint32_t *)(buf + j);
            if (is_brk_insn(insn)) {
                close(fd);
                return map_start + off + j;
            }
        }
    }
    close(fd);
    return 0;
}

static unsigned long find_brk_gadget(int pid) {
    static const char *libs[] = { "libc.so", "libdl.so", "linker64", "linker", NULL };

    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long found = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, "r-xp")) continue;

        int lib_match = 0;
        for (int i = 0; libs[i]; i++) {
            if (strstr(line, libs[i])) {
                lib_match = 1;
                break;
            }
        }
        if (!lib_match) continue;

        unsigned long start = 0, end = 0, pgoff = 0;
        char path[256] = {0};
        if (sscanf(line, "%lx-%lx %*s %lx %*s %*s %255s", &start, &end, &pgoff, path) < 3)
            continue;
        if (path[0] == '\0' || path[0] == '[') continue;

        found = scan_file_for_brk(path, start, pgoff, end - start);
        if (found) break;
    }
    fclose(fp);
    return found;
}

static int is_nop_insn(uint32_t insn) {
    return insn == 0xd503201fU;
}

static unsigned long scan_file_for_nop_cave(const char *path, unsigned long map_start,
                                            unsigned long file_off, unsigned long map_size,
                                            size_t need_insns) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;

    unsigned long limit = map_size;
    if (limit > 512 * 1024) limit = 512 * 1024;

    unsigned char buf[4096];
    for (unsigned long off = 0; off + 4 <= limit; off += sizeof(buf) - 4) {
        size_t chunk = limit - off;
        if (chunk > sizeof(buf)) chunk = sizeof(buf);
        if (pread(fd, buf, chunk, (off_t)(file_off + off)) < 4) break;

        size_t run = 0;
        for (size_t j = 0; j + 4 <= chunk; j += 4) {
            uint32_t insn = *(uint32_t *)(buf + j);
            if (is_nop_insn(insn)) {
                run++;
                if (run >= need_insns) {
                    unsigned long cave = map_start + off + j - (run - 1) * 4;
                    close(fd);
                    return cave;
                }
            } else {
                run = 0;
            }
        }
    }
    close(fd);
    return 0;
}

static unsigned long find_code_cave(int pid, size_t need_bytes) {
    size_t need_insns = (need_bytes + 3) / 4;
    static const char *libs[] = { "libc.so", "libdl.so", NULL };

    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long found = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, "r-xp")) continue;
        int lib_match = 0;
        for (int i = 0; libs[i]; i++) {
            if (strstr(line, libs[i])) {
                lib_match = 1;
                break;
            }
        }
        if (!lib_match) continue;

        unsigned long start = 0, end = 0, pgoff = 0;
        char path[256] = {0};
        if (sscanf(line, "%lx-%lx %*s %lx %*s %*s %255s", &start, &end, &pgoff, path) < 3)
            continue;
        if (path[0] == '\0' || path[0] == '[') continue;

        found = scan_file_for_nop_cave(path, start, pgoff, end - start, need_insns);
        if (found) break;
    }
    fclose(fp);
    return found;
}

static unsigned char g_cave_saved[0x24];
static unsigned long g_cave_addr;
static int g_cave_saved_valid;

static int write_dlopen_cave(int pid, unsigned long cave, unsigned long dlopen_addr,
                             unsigned long path_addr) {
    char mem_path[64];
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);
    int mem_fd = open(mem_path, O_RDONLY);
    if (mem_fd >= 0) {
        if (pread(mem_fd, g_cave_saved, sizeof(g_cave_saved), (off_t)cave) == (ssize_t)sizeof(g_cave_saved)) {
            g_cave_addr = cave;
            g_cave_saved_valid = 1;
        }
        close(mem_fd);
    }

    unsigned char blob[40];
    memset(blob, 0, sizeof(blob));
    uint32_t *sc = (uint32_t *)blob;
    sc[0] = 0x580000c0U; /* ldr x0, #path_ptr @ 0x1c */
    sc[1] = 0x52800041U; /* mov w1, #RTLD_NOW */
    sc[2] = 0x58000051U; /* ldr x17, #fn @ 0x14 */
    sc[3] = 0xd63f0220U; /* blr x17 */
    sc[4] = 0xd4200000U; /* brk #0 */
    memcpy(blob + 0x14, &dlopen_addr, sizeof(dlopen_addr));
    memcpy(blob + 0x1c, &path_addr, sizeof(path_addr));
    return remote_write(pid, cave, blob, 0x24);
}

static void restore_cave_if_needed(int pid) {
    if (!g_cave_saved_valid) return;
    remote_write(pid, g_cave_addr, g_cave_saved, sizeof(g_cave_saved));
    g_cave_saved_valid = 0;
}

/* ptrace must target the stopped thread (tid), not only the thread-group pid. */
static int arm64_remote_call(int tid, const struct dlopen_target *target,
                             unsigned long path_addr, unsigned long stop_pc,
                             unsigned long long *out_x0, const char *method_tag) {
    struct pt_regs_arm64 orig_regs, new_regs, result_regs;
    struct iovec iov = { &orig_regs, sizeof(orig_regs) };
    if (ptrace(PTRACE_GETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) < 0) {
        LOGE("PTRACE_GETREGSET(%s): %s", method_tag, strerror(errno));
        return -1;
    }

    new_regs = orig_regs;
    new_regs.pc = target->addr;
    if (target->kind == DLOPEN_SHELLCODE) {
        /* Stub sets x0/x1/x17 from literals in the RX cave. */
    } else {
        new_regs.regs[0] = path_addr;
        new_regs.regs[1] = RTLD_NOW;
        new_regs.regs[2] = (target->kind == DLOPEN_LOADER_3ARG) ? target->caller_addr : 0;
    }
    new_regs.regs[30] = stop_pc;
    new_regs.pstate &= ~0x20ULL;
    if (new_regs.sp < 0x1000) new_regs.sp = orig_regs.sp;
    new_regs.sp = (new_regs.sp & ~0xFULL) - 0x80;

    iov.iov_base = &new_regs;
    if (ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) < 0) {
        LOGE("PTRACE_SETREGSET(%s): %s", method_tag, strerror(errno));
        return -1;
    }

    ptrace(PTRACE_CONT, tid, NULL, NULL);
    int status = 0;
    int waited = 0;
    pid_t w = 0;
    while (waited < REMOTE_CALL_TIMEOUT_MS) {
        w = waitpid(-1, &status, __WALL | WNOHANG | WUNTRACED);
        if (w > 0) break;
        if (w < 0 && errno != ECHILD) {
            LOGE("waitpid(%s): %s", method_tag, strerror(errno));
            return -1;
        }
        usleep(REMOTE_CALL_POLL_MS * 1000);
        waited += REMOTE_CALL_POLL_MS;
    }
    if (w <= 0) {
        LOGE("Remote dlopen timed out (%s) after %dms", method_tag, REMOTE_CALL_TIMEOUT_MS);
        ptrace(PTRACE_INTERRUPT, tid, NULL, NULL);
        waitpid(-1, &status, __WALL | WUNTRACED);
        iov.iov_base = &orig_regs;
        ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov);
        return -2;
    }

    int reg_tid = (w == tid) ? tid : (int)w;
    iov.iov_base = &result_regs;
    ptrace(PTRACE_GETREGSET, reg_tid, (void *)(long)NT_PRSTATUS, &iov);
    if (out_x0) *out_x0 = result_regs.regs[0];

    iov.iov_base = &orig_regs;
    ptrace(PTRACE_SETREGSET, reg_tid, (void *)(long)NT_PRSTATUS, &iov);

    if (WIFSTOPPED(status)) {
        int sig = WSTOPSIG(status);
        if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL) {
            LOGE("Remote dlopen crashed (%s) sig=%d pc=0x%llx x0=0x%llx", method_tag, sig,
                 (unsigned long long)result_regs.pc,
                 (unsigned long long)result_regs.regs[0]);
            return -1;
        }
        if (sig != SIGTRAP && sig != SIGSTOP) {
            LOGE("Unexpected signal %d after remote call (%s)", sig, method_tag);
            return -1;
        }
    }

    if (!is_plausible_dlopen_handle(result_regs.regs[0])) {
        LOGE("Remote dlopen (%s) bad handle=0x%llx", method_tag,
             (unsigned long long)result_regs.regs[0]);
        return -1;
    }

    return 0;
}

static unsigned long cached_stop_pid;
static unsigned long cached_stop_pc;
static int cached_stop_valid;

static unsigned long get_stop_pc(int pid) {
    if (!cached_stop_valid || cached_stop_pid != (unsigned long)pid) {
        cached_stop_pc = find_brk_gadget(pid);
        cached_stop_pid = (unsigned long)pid;
        cached_stop_valid = 1;
    }
    return cached_stop_pc;
}

static int arm64_prepare_path(int pid, const char *lib_path, unsigned long *path_addr_out) {
    unsigned long region_size = 0;
    unsigned long region = find_rw_region(pid, &region_size);
    if (!region || region_size < 512) {
        LOGE("No suitable RW region");
        return -1;
    }

    size_t path_len = strlen(lib_path) + 1;
    if (remote_write(pid, region, lib_path, path_len) < 0) {
        LOGE("Failed to write library path");
        return -1;
    }
    *path_addr_out = region;
    return 0;
}

static int arm64_try_cave_dlopen(int pid, int tid, unsigned long dlopen_addr,
                                 unsigned long path_addr, unsigned long stop_pc,
                                 unsigned long long *out_ret) {
    unsigned long cave = find_code_cave(pid, 0x24);
    if (!cave || !dlopen_addr) return -1;
    if (write_dlopen_cave(pid, cave, dlopen_addr, path_addr) < 0) return -1;

    struct dlopen_target cave_target = {
        .addr = cave,
        .kind = DLOPEN_SHELLCODE,
    };
    return arm64_remote_call(tid, &cave_target, path_addr, stop_pc, out_ret, "cave");
}

static int arm64_try_direct_dlopen(int tid, const struct dlopen_target *target,
                                   unsigned long path_addr, unsigned long stop_pc,
                                   unsigned long long *out_ret, const char *tag) {
    return arm64_remote_call(tid, target, path_addr, stop_pc, out_ret, tag);
}

/* Let non-inject threads run so linker mutexes are not held while frozen. */
static void resume_other_threads(int pid, int inject_tid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *d = opendir(path);
    if (!d) return;

    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (ent->d_name[0] < '1') continue;
        int t = (int)strtol(ent->d_name, NULL, 10);
        if (t <= 0 || t == inject_tid) continue;
        ptrace(PTRACE_CONT, t, NULL, 0);
    }
    closedir(d);
}

static void detach_all_threads(int pid, int inject_tid) {
    restore_cave_if_needed(pid);

    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *d = opendir(path);
    if (d) {
        struct dirent *ent;
        while ((ent = readdir(d)) != NULL) {
            if (ent->d_name[0] < '1') continue;
            int t = (int)strtol(ent->d_name, NULL, 10);
            if (t > 0) {
                ptrace(PTRACE_DETACH, t, NULL, (void *)(long)SIGCONT);
            }
        }
        closedir(d);
    } else {
        ptrace(PTRACE_DETACH, inject_tid, NULL, (void *)(long)SIGCONT);
    }
}

static int try_dlopen_and_verify(int pid, int tid, const struct dlopen_target *target,
                                 unsigned long path_addr, unsigned long stop_pc,
                                 unsigned long long *out_ret, const char *tag) {
    int r = arm64_remote_call(tid, target, path_addr, stop_pc, out_ret, tag);
    if (r == -2) return -2;
    if (r != 0) return -1;
    if (!is_lib_loaded(pid, "libspeedhook")) {
        LOGE("(%s) handle=0x%llx but libspeedhook.so not in maps", tag,
             (unsigned long long)*out_ret);
        return -1;
    }
    return 0;
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

    struct dlopen_target dlopen_target = resolve_dlopen(pid);
    if (!dlopen_target.addr) {
        out("ANDROCE_INJECT: FAIL dlopen symbol not found");
        return -1;
    }

    snprintf(msg, sizeof(msg), "ANDROCE_INJECT: dlopen=0x%lx kind=%d",
             dlopen_target.addr, dlopen_target.kind);
    out(msg);

    if (ptrace(PTRACE_ATTACH, pid, NULL, NULL) < 0) {
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: FAIL ptrace attach: %s", strerror(errno));
        out(msg);
        return -1;
    }

    int status = 0;
    pid_t inject_tid = waitpid(pid, &status, __WALL | WUNTRACED);
    if (inject_tid < 0 || !WIFSTOPPED(status)) {
        out("ANDROCE_INJECT: FAIL process did not stop");
        ptrace(PTRACE_DETACH, pid, NULL, (void *)(long)SIGCONT);
        return -1;
    }

    char msg_tid[128];
    snprintf(msg_tid, sizeof(msg_tid), "ANDROCE_INJECT: stopped tid=%d", inject_tid);
    out(msg_tid);

    unsigned long path_addr = 0;
    if (arm64_prepare_path(pid, lib_path, &path_addr) < 0) {
        ptrace(PTRACE_DETACH, inject_tid, NULL, (void *)(long)SIGCONT);
        out("ANDROCE_INJECT: FAIL could not write library path");
        return -1;
    }

    unsigned long stop_pc = get_stop_pc(pid);
    if (!stop_pc) {
        detach_all_threads(pid, inject_tid);
        out("ANDROCE_INJECT: FAIL no BRK gadget in libc/linker");
        return -1;
    }

    resume_other_threads(pid, inject_tid);

    unsigned long long dlopen_ret = 0;
    int inject_result = -1;
    int timed_out = 0;

    struct dlopen_target libdl_target = {0};
    libdl_target.addr = find_sym_in_maps(pid, "libdl.so", "dlopen");
    if (!libdl_target.addr) libdl_target.addr = find_sym_in_maps(pid, "libc.so", "dlopen");
    libdl_target.kind = DLOPEN_2ARG;

    if (libdl_target.addr) {
        inject_result = try_dlopen_and_verify(pid, inject_tid, &libdl_target, path_addr,
                                              stop_pc, &dlopen_ret, "libdl");
        if (inject_result == -2) timed_out = 1;
    }

    if (inject_result < 0 && !timed_out && libdl_target.addr) {
        int cave_r = arm64_try_cave_dlopen(pid, inject_tid, libdl_target.addr, path_addr,
                                           stop_pc, &dlopen_ret);
        if (cave_r == -2) {
            timed_out = 1;
        } else if (cave_r == 0 && is_lib_loaded(pid, "libspeedhook")) {
            inject_result = 0;
        }
    }

    detach_all_threads(pid, inject_tid);

    if (timed_out) {
        out("ANDROCE_INJECT: FAIL timeout (stay in androCE, game paused until inject finishes)");
        return -1;
    }

    if (inject_result < 0 || !is_plausible_dlopen_handle(dlopen_ret)) {
        snprintf(msg, sizeof(msg),
                 "ANDROCE_INJECT: FAIL handle=0x%llx (SELinux: try setenforce 0 in Settings)",
                 dlopen_ret);
        out(msg);
        return -1;
    }

    usleep(200000);

    if (!is_lib_loaded(pid, "libspeedhook")) {
        out("ANDROCE_INJECT: FAIL library not in maps after dlopen");
        return -1;
    }

    snprintf(msg, sizeof(msg), "ANDROCE_INJECT: OK handle=0x%llx", dlopen_ret);
    out(msg);
    return 0;
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
