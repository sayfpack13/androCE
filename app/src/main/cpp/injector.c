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
#include <stdatomic.h>

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
#include <stdarg.h>

#define LOG_TAG "SpeedInjector"
#define INJECTOR_BUILD __DATE__ " " __TIME__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define NT_PRSTATUS 1
#define RTLD_NOW    2

#ifndef __WALL
#define __WALL 0x40000000
#endif

#ifndef PTRACE_SEIZE
#define PTRACE_SEIZE 0x4206
#endif
#ifndef PTRACE_INTERRUPT
#define PTRACE_INTERRUPT 0x4207
#endif
#ifndef PTRACE_O_TRACEEXEC
#define PTRACE_O_TRACEEXEC 0x00000001
#endif

#define INJECT_ATTACH_TIMEOUT_MS 15000
#define INJECT_ATTACH_POLL_MS    10

/* ARM64 user_pt_regs */
struct pt_regs_arm64 {
    unsigned long long regs[31];
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static void inject_emit(int use_error, const char *prefix, const char *body) {
    char line[512];
    snprintf(line, sizeof(line), "%s%s", prefix, body);
    fputs(line, stdout);
    fputc('\n', stdout);
    fflush(stdout);
    if (use_error) LOGE("%s", line);
    else LOGI("%s", line);
}

static void out(const char *msg) {
    inject_emit(0, "", msg);
}

static void inject_dbg(const char *fmt, ...) {
    char body[464];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);
    inject_emit(0, "ANDROCE_DBG: ", body);
}

static void inject_err(const char *fmt, ...) {
    char body[464];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);
    inject_emit(1, "ANDROCE_DBG: ", body);
}

static void inject_fail(const char *fmt, ...) {
    char body[400];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);
    inject_emit(1, "ANDROCE_DBG: ", body);
    char line[512];
    snprintf(line, sizeof(line), "ANDROCE_INJECT: FAIL %s", body);
    inject_emit(1, "", line);
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
    DLOPEN_SHELLCODE = 2, /* PC at stub; args in the stub */
    REMOTE_MMAP_6ARG = 3,
    REMOTE_MEMFD_2ARG = 4,
    DLOPEN_ANDROID_EXT_3ARG = 5,
    REMOTE_MPROTECT_3ARG = 6,
};

/* bionic android_dlextinfo (API 29+) */
#define ANDROID_DLEXT_USE_LIBRARY_FD 0x10
#define ANDROID_DLEXT_FORCE_LOAD     0x40
#define ANDROID_DLEXT_RESERVED_ADDRESS 0x01
struct android_dlextinfo_memfd {
    uint64_t flags;
    void *reserved_addr;
    size_t reserved_size;
    int relro_fd;
    int library_fd;
    off64_t library_fd_offset;
    struct android_namespace_t *library_namespace;
};

#define REMOTE_CALL_TIMEOUT_MS 8000
#define REMOTE_CALL_POLL_MS    50
#define INJECT_PATH_OFF        0x40
#define MEMFD_SCRATCH_NAME_OFF 0x00
#define MEMFD_SCRATCH_FD_OFF   0x200

static size_t get_page_size(void) {
    long ps = sysconf(_SC_PAGESIZE);
    size_t result = ps > 0 ? (size_t)ps : 4096;
    LOGI("Page size detected: %zu bytes", result);
    return result;
}

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

/* Largest anonymous RW mapping — safe scratch (not live heap objects at base). */
static unsigned long find_anon_rw_region(int pid, size_t size_needed, unsigned long *out_size) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long best = 0;
    unsigned long best_size = 0;

    while (fgets(line, sizeof(line), fp)) {
        unsigned long start = 0, end = 0;
        char perms[8];
        char path[256] = {0};
        int n = sscanf(line, "%lx-%lx %4s %*s %*s %*s %255s", &start, &end, perms, path);
        unsigned long size = end - start;
        if (size < size_needed) continue;
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        int is_anon = (n < 4) || path[0] == '\0' || path[0] == '[';
        if (!is_anon) continue;
        if (size > best_size) {
            best = start;
            best_size = size;
        }
    }
    fclose(fp);
    if (out_size) *out_size = best_size;
    return best;
}

static unsigned long scratch_addr_at_tail(unsigned long base, unsigned long region_size, size_t len) {
    if (region_size < len + 0x100) return 0;
    unsigned long off = region_size - len;
    off &= ~7ULL;
    return base + off;
}

static unsigned long ptr_untag(unsigned long p) {
    return p & 0x00FFFFFFFFFFFFFFULL;
}

static unsigned long ptr_tag_from(unsigned long tagged_ref) {
    return tagged_ref & 0xFF00000000000000ULL;
}

static unsigned long ptr_tagged(unsigned long addr, unsigned long tag_ref) {
    unsigned long u = ptr_untag(addr);
    if (!u) return 0;
    return u | ptr_tag_from(tag_ref);
}

static unsigned long tag_remote_ptr(unsigned long addr, unsigned long tag_ref) {
    if (!addr) return 0;
    if (addr & 0xFF00000000000000ULL) return addr;
    return ptr_tagged(addr, tag_ref);
}

/* Write to process memory via /proc/pid/mem (alternative to process_vm_writev) */
static int proc_mem_write(int pid, unsigned long addr, const void *data, size_t len) {
    char mem_path[64];
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);
    
    int fd = open(mem_path, O_WRONLY);
    if (fd < 0) {
        LOGE("Failed to open %s: %s", mem_path, strerror(errno));
        return -1;
    }
    
    if (lseek(fd, addr, SEEK_SET) < 0) {
        LOGE("Failed to seek in %s: %s", mem_path, strerror(errno));
        close(fd);
        return -1;
    }
    
    ssize_t written = write(fd, data, len);
    close(fd);
    
    if (written != (ssize_t)len) {
        LOGE("Failed to write to %s: wrote %zd of %zu bytes", mem_path, written, len);
        return -1;
    }
    
    return 0;
}

static int pc_in_app_rx_map(int pid, unsigned long pc) {
    if (!pc) return 0;
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;
    char line[512];
    int found = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, "r-xp")) continue;
        if (!strstr(line, "/data/app/") && !strstr(line, "/data/data/")) continue;
        unsigned long start = 0, end = 0;
        if (sscanf(line, "%lx-%lx", &start, &end) == 2 && pc >= start && pc < end) {
            found = 1;
            break;
        }
    }
    fclose(fp);
    return found;
}

/** Caller PC for __loader_dlopen — must be inside app/game executable code. */
static unsigned long resolve_loader_caller(int pid) {
    static const char *game_libs[] = {
        "libil2cpp.so", "libunity.so", "libmain.so", "libgame.so", "libUE4.so", NULL
    };
    for (int i = 0; game_libs[i]; i++) {
        unsigned long sym = find_sym_in_maps(pid, game_libs[i], "JNI_OnLoad");
        if (sym) return sym;
    }

    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *fp = fopen(maps_path, "r");
    if (!fp) return 0;

    char line[512];
    unsigned long caller = 0;
    unsigned long best_start = 0;
    unsigned long best_size = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, "r-xp")) continue;
        if (!strstr(line, "/data/app/") && !strstr(line, "/data/data/")) continue;

        unsigned long start = 0, end = 0, pgoff = 0;
        char path[256] = {0};
        if (sscanf(line, "%lx-%lx %*s %lx %*s %*s %255s", &start, &end, &pgoff, path) < 3)
            continue;

        unsigned long size = end - start;
        if (path[0] != '\0' && (strstr(path, "/lib/arm64/") || strstr(path, "/lib/arm/"))) {
            if (size > best_size) {
                best_size = size;
                best_start = start;
                caller = start + (pgoff ? pgoff : 0x1000) + 0x4000;
            }
        }
        if (!caller && size > best_size) {
            best_size = size;
            best_start = start;
            caller = start + (pgoff ? pgoff : 0x1000) + 0x8000;
        }
    }
    fclose(fp);
    if (!caller && best_start) {
        caller = best_start + 0x10000;
    }
    return caller;
}

static int resolve_dlopen_candidate(int pid, int index, struct dlopen_target *out) {
    static const struct {
        const char *lib;
        const char *sym;
        enum dlopen_kind kind;
    } candidates[] = {
        { "libdl.so", "dlopen", DLOPEN_2ARG },
        { "libc.so", "dlopen", DLOPEN_2ARG },
        { "libc.so", "__dl_dlopen", DLOPEN_2ARG },
        { "linker64", "__loader_dlopen", DLOPEN_LOADER_3ARG },
        { "linker", "__loader_dlopen", DLOPEN_LOADER_3ARG },
        { NULL, NULL, DLOPEN_2ARG }
    };

    int found = 0;
    for (int i = 0; candidates[i].lib; i++) {
        unsigned long addr = find_sym_in_maps(pid, candidates[i].lib, candidates[i].sym);
        if (!addr) continue;
        if (found++ != index) continue;

        memset(out, 0, sizeof(*out));
        out->addr = addr;
        out->kind = candidates[i].kind;
        if (out->kind == DLOPEN_LOADER_3ARG) {
            out->caller_addr = resolve_loader_caller(pid);
            if (!out->caller_addr) out->caller_addr = addr;
        }
        return 0;
    }
    return -1;
}

static struct dlopen_target resolve_dlopen(int pid) {
    struct dlopen_target target = {0};
    resolve_dlopen_candidate(pid, 0, &target);
    return target;
}

static int is_plausible_dlopen_handle(unsigned long long h) {
    if (h == 0 || h == (unsigned long long)-1) return 0;
    /* Strip MTE / HWASan tag from upper byte before validation */
    unsigned long long untagged = h & 0x00FFFFFFFFFFFFFFULL;
    if (untagged == 0) return 0;
    if (untagged & 7ULL) return 0;
    if (untagged < 0x10000ULL) return 0;
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
        /* Skip deleted libraries - they're stale from previous injections */
        if (strstr(line, "(deleted)")) continue;
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
    sc[0] = 0x580000e0U; /* ldr x0, #path_ptr @ 0x1c (offset 28 bytes = 7*4) */
    sc[1] = 0x52800041U; /* mov w1, #RTLD_NOW */
    sc[2] = 0x58000031U; /* ldr x17, [pc+0x0c] -> dlopen @ 0x14 (imm19=3, Rt=17) */
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

static void ptrace_release_other_thread(int tid, int status);

/* ptrace must target the stopped thread (tid), not only the thread-group pid. */
static int arm64_remote_call(int tid, const struct dlopen_target *target,
                             unsigned long path_addr, unsigned long stop_pc,
                             unsigned long long *out_x0, const char *method_tag) {
    static atomic_size_t cached_page_size = 0;
    size_t page_size = atomic_load(&cached_page_size);
    if (page_size == 0) {
        size_t new_size = get_page_size();
        size_t expected = 0;
        if (atomic_compare_exchange_strong(&cached_page_size, &expected, new_size)) {
            page_size = new_size;
        } else {
            page_size = expected;
        }
    }

    struct pt_regs_arm64 orig_regs, new_regs, result_regs;
    struct iovec iov = { &orig_regs, sizeof(orig_regs) };
    if (ptrace(PTRACE_GETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) < 0) {
        inject_err("GETREGSET %s: %s", method_tag, strerror(errno));
        return -1;
    }

    unsigned long tag_ref = orig_regs.sp;
    inject_dbg("call %s fn=0x%lx kind=%d path=0x%lx stop=0x%lx sp=0x%lx tag=0x%lx",
               method_tag, target->addr, (int)target->kind, path_addr, stop_pc,
               (unsigned long)orig_regs.sp, ptr_tag_from(tag_ref));

    new_regs = orig_regs;
    new_regs.pc = target->addr;
    if (target->kind == DLOPEN_SHELLCODE) {
        /* Stub sets x0/x1/x17 from literals in the RX page. */
    } else if (target->kind == REMOTE_MMAP_6ARG) {
        new_regs.regs[0] = 0;
        new_regs.regs[1] = page_size;
        new_regs.regs[2] = 3; /* PROT_READ|WRITE (add EXEC later with mprotect) */
        new_regs.regs[3] = 0x22; /* MAP_PRIVATE|MAP_ANONYMOUS */
        new_regs.regs[4] = (unsigned long)-1;
        new_regs.regs[5] = 0;
    } else if (target->kind == REMOTE_MPROTECT_3ARG) {
        new_regs.regs[0] = tag_remote_ptr(path_addr, tag_ref);
        new_regs.regs[1] = page_size;
        new_regs.regs[2] = 7; /* PROT_READ|WRITE|EXEC */
    } else if (target->kind == REMOTE_MEMFD_2ARG) {
        new_regs.regs[0] = tag_remote_ptr(path_addr, tag_ref);
        new_regs.regs[1] = 1; /* MFD_CLOEXEC */
    } else if (target->kind == DLOPEN_ANDROID_EXT_3ARG) {
        new_regs.regs[0] = tag_remote_ptr(path_addr, tag_ref);
        new_regs.regs[1] = RTLD_NOW;
        new_regs.regs[2] = tag_remote_ptr(target->caller_addr, tag_ref);
    } else {
        new_regs.regs[0] = tag_remote_ptr(path_addr, tag_ref);
        new_regs.regs[1] = RTLD_NOW;
        new_regs.regs[2] = (target->kind == DLOPEN_LOADER_3ARG)
            ? tag_remote_ptr(target->caller_addr, tag_ref) : 0;
    }
    new_regs.regs[30] = tag_remote_ptr(stop_pc, tag_ref);
    new_regs.pstate &= ~0x20ULL;
    if (new_regs.sp < 0x1000) new_regs.sp = orig_regs.sp;
    new_regs.sp = (new_regs.sp & ~0xFULL) - 0x80;

    iov.iov_base = &new_regs;
    if (ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) < 0) {
        inject_err("SETREGSET %s: %s", method_tag, strerror(errno));
        return -1;
    }

    ptrace(PTRACE_CONT, tid, NULL, NULL);
    int status = 0;
    int waited = 0;
    pid_t w = 0;
    int reg_tid = tid;
retry_wait:
    w = 0;
    while (waited < REMOTE_CALL_TIMEOUT_MS) {
        w = waitpid(-1, &status, __WALL | WNOHANG | WUNTRACED);
        if (w > 0) {
            if (!WIFSTOPPED(status)) {
                w = 0;
            } else if (w != tid) {
                ptrace_release_other_thread((int)w, status);
                w = 0;
            } else {
                break;
            }
        } else if (w < 0 && errno != ECHILD) {
            inject_err("waitpid %s: %s", method_tag, strerror(errno));
            iov.iov_base = &orig_regs;
            ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov);
            return -1;
        }
        usleep(REMOTE_CALL_POLL_MS * 1000);
        waited += REMOTE_CALL_POLL_MS;
    }
    if (w != tid) {
        inject_err("timeout %s after %dms", method_tag, REMOTE_CALL_TIMEOUT_MS);
        ptrace(PTRACE_INTERRUPT, tid, NULL, NULL);
        (void)waitpid(-1, &status, __WALL | WUNTRACED);
        iov.iov_base = &orig_regs;
        ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov);
        return -2;
    }

    reg_tid = tid;
    iov.iov_base = &result_regs;
    ptrace(PTRACE_GETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov);

    if (WIFSTOPPED(status)) {
        int sig = WSTOPSIG(status);
        unsigned long pc_u = ptr_untag(result_regs.pc);
        unsigned long stop_u = ptr_untag(stop_pc);
        unsigned long sc_brk_u = (target->kind == DLOPEN_SHELLCODE)
            ? ptr_untag(target->addr) + 0x10U : 0;
        int stopped_at_ret = (pc_u == stop_u);
        int stopped_at_sc_brk = (sc_brk_u && pc_u == sc_brk_u);
        int is_expected_stop = stopped_at_ret || stopped_at_sc_brk;

        if ((sig == SIGSEGV || sig == SIGBUS) ||
            (sig == SIGILL && !is_expected_stop)) {
            inject_err("crash %s sig=%d pc=0x%llx x0=0x%llx lr=0x%llx stop=0x%llx", method_tag, sig,
                       (unsigned long long)result_regs.pc,
                       (unsigned long long)result_regs.regs[0],
                       (unsigned long long)result_regs.regs[30],
                       (unsigned long long)stop_pc);
            iov.iov_base = &orig_regs;
            ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov);
            return -1;
        }
        if (!is_expected_stop && sig != SIGTRAP && sig != SIGSTOP) {
            inject_dbg("signal %d suppressed during %s pc=0x%llx", sig, method_tag,
                       (unsigned long long)result_regs.pc);
            ptrace(PTRACE_CONT, tid, NULL, (void *)0);
            w = 0;
            goto retry_wait;
        }
        if (is_expected_stop) {
            inject_dbg("stop %s at pc=0x%llx sig=%d x0=0x%llx", method_tag,
                       (unsigned long long)result_regs.pc, sig,
                       (unsigned long long)result_regs.regs[0]);
        }
    }

    if (out_x0) *out_x0 = result_regs.regs[0];
    inject_dbg("return %s x0=0x%llx pc=0x%llx", method_tag,
               (unsigned long long)result_regs.regs[0],
               (unsigned long long)result_regs.pc);

    iov.iov_base = &orig_regs;
    ptrace(PTRACE_SETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov);

    if (target->kind == REMOTE_MMAP_6ARG) {
        if (result_regs.regs[0] < 0x10000ULL) {
            inject_err("mmap %s bad x0=0x%llx", method_tag,
                       (unsigned long long)result_regs.regs[0]);
            return -1;
        }
        return 0;
    }

    if (target->kind == REMOTE_MPROTECT_3ARG) {
        if ((long long)result_regs.regs[0] < 0) {
            inject_err("mprotect %s errno x0=0x%llx", method_tag,
                       (unsigned long long)result_regs.regs[0]);
            return -1;
        }
        return 0;
    }

    if (target->kind == REMOTE_MEMFD_2ARG) {
        if ((long long)result_regs.regs[0] < 0) {
            inject_err("memfd_create %s errno x0=0x%llx", method_tag,
                       (unsigned long long)result_regs.regs[0]);
            return -1;
        }
        return 0;
    }

    if (target->kind == DLOPEN_SHELLCODE) {
        return 0;
    }

    if (result_regs.regs[0] == 0) {
        inject_fail("dlopen %s returned NULL", method_tag);
        return -1;
    }

    if ((long long)result_regs.regs[0] < 0) {
        inject_fail("dlopen %s errno x0=0x%llx", method_tag,
                    (unsigned long long)result_regs.regs[0]);
        return -1;
    }

    if (!is_plausible_dlopen_handle(result_regs.regs[0])) {
        inject_fail("dlopen %s invalid handle x0=0x%llx", method_tag,
                    (unsigned long long)result_regs.regs[0]);
        return -1;
    }

    return 0;
}

static int arm64_prepare_path(int pid, const char *lib_path, unsigned long *path_addr_out);
static int arm64_prepare_path_ex(int pid, int tid, unsigned long stop_pc,
                                 const char *lib_path, unsigned long *path_addr_out);

static int arm64_try_direct_dlopen(int tid, const struct dlopen_target *target,
                                   unsigned long path_addr, unsigned long stop_pc,
                                   unsigned long long *out_ret, const char *tag);

/** __loader_dlopen requires a caller address inside the target process (use stopped PC). */
static void patch_loader_caller(int pid, int tid, struct dlopen_target *target) {
    char msg[128];
    if (!target || !target->addr) return;
    if (target->kind != DLOPEN_LOADER_3ARG) return;

    struct pt_regs_arm64 regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) != 0) {
        target->caller_addr = resolve_loader_caller(pid);
        return;
    }
    target->caller_addr = resolve_loader_caller(pid);
    if (regs.pc && pc_in_app_rx_map(pid, regs.pc)) {
        target->caller_addr = regs.pc;
    } else if (regs.regs[30] && pc_in_app_rx_map(pid, regs.regs[30])) {
        target->caller_addr = regs.regs[30];
    }
    if (!target->caller_addr) {
        target->caller_addr = resolve_loader_caller(pid);
    }
    inject_dbg("loader caller=0x%lx", target->caller_addr);
}

/* Load library from memfd — bypasses linker namespace path restrictions (Android 10+). */
static int inject_via_memfd(int pid, int tid, unsigned long stop_pc, const char *lib_path,
                            unsigned long scratch_addr,
                            struct dlopen_target *loader_target) {
    unsigned long memfd_fn = find_sym_in_maps(pid, "libc.so", "memfd_create");
    unsigned long dlopen_ext = find_sym_in_maps(pid, "libdl.so", "android_dlopen_ext");
    if (!memfd_fn) {
        out("ANDROCE_INJECT: memfd skip no memfd_create in target");
        return -1;
    }
    if (!dlopen_ext) {
        out("ANDROCE_INJECT: memfd warn no android_dlopen_ext (will try loader path only)");
    }

    if (!scratch_addr) {
        out("ANDROCE_INJECT: memfd skip no scratch memory");
        return -1;
    }

    FILE *fp = fopen(lib_path, "rb");
    if (!fp) {
        out("ANDROCE_INJECT: memfd FAIL fopen library");
        return -1;
    }
    if (fseek(fp, 0, SEEK_END) != 0) { fclose(fp); return -1; }
    long lib_size = ftell(fp);
    if (lib_size <= 0 || lib_size > 8 * 1024 * 1024) { fclose(fp); return -1; }
    rewind(fp);

    unsigned char *lib_buf = (unsigned char *)malloc((size_t)lib_size);
    if (!lib_buf) { fclose(fp); return -1; }
    if (fread(lib_buf, 1, (size_t)lib_size, fp) != (size_t)lib_size) {
        free(lib_buf);
        fclose(fp);
        return -1;
    }
    fclose(fp);

    struct pt_regs_arm64 regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) != 0) {
        free(lib_buf);
        out("ANDROCE_INJECT: memfd FAIL read thread regs");
        return -1;
    }
    unsigned long sp = ptr_untag(regs.sp) & ~0xFULL;
    if (sp < 0x800) {
        free(lib_buf);
        out("ANDROCE_INJECT: memfd FAIL invalid stack pointer");
        return -1;
    }
    unsigned long name_u = sp - 0x100;
    unsigned long fdpath_u = sp - 0x200;
    inject_dbg("memfd stack name=0x%lx fdpath=0x%lx tag=0x%lx", name_u, fdpath_u,
               ptr_tag_from(regs.sp));

    if (remote_write(pid, name_u, "libspeedhook.so", 16) < 0) {
        free(lib_buf);
        out("ANDROCE_INJECT: memfd FAIL write name");
        return -1;
    }

    patch_loader_caller(pid, tid, loader_target);

    struct dlopen_target memfd_target = { .addr = memfd_fn, .kind = REMOTE_MEMFD_2ARG };
    unsigned long long remote_fd = 0;
    if (arm64_remote_call(tid, &memfd_target, name_u, stop_pc, &remote_fd, "memfd") != 0) {
        free(lib_buf);
        out("ANDROCE_INJECT: memfd FAIL memfd_create call");
        return -1;
    }
    if ((long long)remote_fd < 0) {
        char msg[128];
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: memfd FAIL fd=%lld", (long long)remote_fd);
        out(msg);
        free(lib_buf);
        return -1;
    }

    char procfd[64];
    snprintf(procfd, sizeof(procfd), "/proc/%d/fd/%d", pid, (int)remote_fd);
    int wfd = open(procfd, O_WRONLY);
    if (wfd < 0) {
        LOGE("open %s: %s", procfd, strerror(errno));
        free(lib_buf);
        return -1;
    }
    ssize_t written = 0;
    while (written < lib_size) {
        ssize_t n = write(wfd, lib_buf + written, (size_t)(lib_size - written));
        if (n <= 0) break;
        written += n;
    }
    close(wfd);
    free(lib_buf);
    if (written != lib_size) {
        LOGE("wrote %zd/%ld bytes to memfd", written, lib_size);
        out("ANDROCE_INJECT: memfd FAIL write library bytes to fd");
        return -1;
    }

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", (int)remote_fd);
    if (remote_write(pid, fdpath_u, fd_path, strlen(fd_path) + 1) < 0) {
        out("ANDROCE_INJECT: memfd FAIL write fd path");
        return -1;
    }

    if (loader_target && loader_target->addr) {
        unsigned long long ret = 0;
        out("ANDROCE_INJECT: memfd try /proc/self/fd path");
        if (arm64_try_direct_dlopen(tid, loader_target, fdpath_u, stop_pc, &ret, "memfd_fd") == 0 &&
            is_lib_loaded(pid, "libspeedhook")) {
            return 0;
        }
        char msg[128];
        snprintf(msg, sizeof(msg), "ANDROCE_INJECT: memfd fd-path ret=0x%llx", (unsigned long long)ret);
        out(msg);
    }

    if (!dlopen_ext) {
        out("ANDROCE_INJECT: memfd done no android_dlopen_ext");
        return -1;
    }

    unsigned long mmap_addr = find_sym_in_maps(pid, "libc.so", "mmap");
    if (!mmap_addr) mmap_addr = find_sym_in_maps(pid, "libc.so", "mmap64");
    if (!mmap_addr) {
        out("ANDROCE_INJECT: memfd FAIL no mmap");
        return -1;
    }

    struct dlopen_target mmap_fn = { .addr = mmap_addr, .kind = REMOTE_MMAP_6ARG };
    unsigned long long remote_page = 0;
    if (arm64_remote_call(tid, &mmap_fn, 0, stop_pc, &remote_page, "mmap_ext") != 0) {
        return -1;
    }

    struct android_dlextinfo_memfd ext;
    memset(&ext, 0, sizeof(ext));
    ext.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_FORCE_LOAD;
    ext.library_fd = (int)remote_fd;
    unsigned long ext_addr = (unsigned long)remote_page;
    unsigned long ext_name_addr = ext_addr + 0x100;
    if (remote_write(pid, ext_addr, &ext, sizeof(ext)) < 0) {
        out("ANDROCE_INJECT: memfd FAIL write extinfo");
        return -1;
    }
    if (remote_write(pid, ext_name_addr, "libspeedhook.so", 16) < 0) {
        out("ANDROCE_INJECT: memfd FAIL write ext filename");
        return -1;
    }

    out("ANDROCE_INJECT: memfd try android_dlopen_ext");
    struct dlopen_target ext_target = {
        .addr = dlopen_ext,
        .kind = DLOPEN_ANDROID_EXT_3ARG,
        .caller_addr = ext_addr,
    };
    unsigned long long handle = 0;
    if (arm64_remote_call(tid, &ext_target, ext_name_addr, stop_pc, &handle, "dlopen_ext") != 0) {
        out("ANDROCE_INJECT: memfd FAIL android_dlopen_ext call");
        return -1;
    }

    snprintf(fd_path, sizeof(fd_path), "ANDROCE_INJECT: memfd handle=0x%llx loaded=%d",
             (unsigned long long)handle, is_lib_loaded(pid, "libspeedhook"));
    out(fd_path);
    return is_lib_loaded(pid, "libspeedhook") ? 0 : -1;
}

static int inject_via_mmap_page(int pid, int tid, unsigned long stop_pc, const char *lib_path) {
    LOGI("=== MMAP PAGE INJECTION ===");
    static atomic_size_t cached_page_size = 0;
    size_t page_size = atomic_load(&cached_page_size);
    if (page_size == 0) {
        size_t new_size = get_page_size();
        size_t expected = 0;
        if (atomic_compare_exchange_strong(&cached_page_size, &expected, new_size)) {
            page_size = new_size;
        } else {
            page_size = expected;
        }
    }
    LOGI("Using page size: %zu bytes", page_size);

    unsigned long mmap_addr = find_sym_in_maps(pid, "libc.so", "mmap");
    if (!mmap_addr) mmap_addr = find_sym_in_maps(pid, "libc.so", "mmap64");
    if (!mmap_addr) {
        LOGE("mmap symbol not found in target process");
        return -1;
    }
    LOGI("Found mmap at 0x%lx in target", mmap_addr);

    struct dlopen_target mmap_fn = { .addr = mmap_addr, .kind = REMOTE_MMAP_6ARG };
    unsigned long long remote_page = 0;
    if (arm64_remote_call(tid, &mmap_fn, 0, stop_pc, &remote_page, "mmap") != 0) {
        LOGE("Remote mmap call failed");
        return -1;
    }

    if (remote_page == 0 || remote_page < 0x10000ULL) {
        LOGE("Remote mmap returned invalid address: x0=0x%llx - Android 15 may block remote mmap", remote_page);
        LOGE("Skipping mmap injection, trying direct dlopen fallback");
        return -2; /* Special return code to skip mmap but continue with other methods */
    }

    unsigned long path_in_target = (unsigned long)remote_page + INJECT_PATH_OFF;
    size_t path_len = strlen(lib_path) + 1;
    if (INJECT_PATH_OFF + path_len >= page_size) {
        LOGE("library path too long for inject page");
        return -1;
    }

    unsigned char *page = calloc(1, page_size);
    if (!page) {
        LOGE("failed to allocate inject page buffer");
        return -1;
    }
    memcpy(page + INJECT_PATH_OFF, lib_path, path_len);
    int write_result = remote_write(pid, (unsigned long)remote_page, page, page_size);
    free(page);
    if (write_result < 0) {
        LOGE("failed to write inject page");
        return -1;
    }

    // Add PROT_EXEC using remote mprotect (Android 15 W^X policy)
    unsigned long mprotect_addr = find_sym_in_maps(pid, "libc.so", "mprotect");
    if (!mprotect_addr) {
        LOGE("mprotect symbol not found in target process");
        return -1;
    }
    LOGI("Found mprotect at 0x%lx in target", mprotect_addr);
    LOGI("Adding PROT_EXEC to 0x%lx (size %zu)", (unsigned long)remote_page, page_size);
    struct dlopen_target mprotect_fn = { .addr = mprotect_addr, .kind = REMOTE_MPROTECT_3ARG };
    unsigned long long mprotect_ret = 0;
    if (arm64_remote_call(tid, &mprotect_fn, (unsigned long)remote_page, stop_pc, &mprotect_ret, "mprotect") != 0) {
        LOGE("Remote mprotect call failed");
        return -1;
    }
    if (mprotect_ret != 0) {
        LOGE("Remote mprotect failed with error: x0=0x%llx", mprotect_ret);
        return -1;
    }
    LOGI("Remote mprotect succeeded");

    LOGI("Trying dlopen via mmap page (candidates 0-7)");
    unsigned long last_addr = 0;
    for (int ci = 0; ci < 8; ci++) {
        struct dlopen_target cand;
        if (resolve_dlopen_candidate(pid, ci, &cand) != 0) break;
        if (cand.addr == last_addr) continue;
        last_addr = cand.addr;
        patch_loader_caller(pid, tid, &cand);

        LOGI("Trying candidate %d: kind=%d addr=0x%lx", ci, cand.kind, cand.addr);
        unsigned long long ret = 0;
        if (arm64_remote_call(tid, &cand, path_in_target, stop_pc, &ret, "mmap_dlopen") != 0) {
            LOGI("Candidate %d remote call failed", ci);
            continue;
        }
        LOGI("Candidate %d returned x0=0x%llx", ci, ret);
        if (is_lib_loaded(pid, "libspeedhook")) {
            LOGI("Library loaded via candidate %d", ci);
            return 0;
        }
    }

    LOGI("All mmap dlopen candidates failed");
    return -1;
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
    size_t path_len = strlen(lib_path) + 1;
    unsigned long region_size = 0;
    unsigned long region = find_anon_rw_region(pid, path_len + 0x100, &region_size);
    if (!region) {
        LOGE("No suitable anonymous RW region found");
        return -1;
    }

    unsigned long addr = scratch_addr_at_tail(region, region_size, path_len);
    if (!addr) {
        LOGE("Anonymous RW region too small for path (%zu bytes)", path_len);
        return -1;
    }
    LOGI("Writing library path (%zu bytes) to anon scratch 0x%lx", path_len, addr);
    if (remote_write(pid, addr, lib_path, path_len) < 0) {
        LOGE("Failed to write library path to target memory");
        return -1;
    }
    *path_addr_out = addr;
    return 0;
}

static int arm64_prepare_path_ex(int pid, int tid, unsigned long stop_pc,
                                 const char *lib_path, unsigned long *path_addr_out) {
    (void)stop_pc;
    size_t path_len = strlen(lib_path) + 1;
    if (path_len > 240) {
        return arm64_prepare_path(pid, lib_path, path_addr_out);
    }

    struct pt_regs_arm64 regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, tid, (void *)(long)NT_PRSTATUS, &iov) == 0) {
        unsigned long sp = ptr_untag(regs.sp) & ~0xFULL;
        if (sp > 0x400) {
            unsigned long path_u = sp - 0x300;
            if (remote_write(pid, path_u, lib_path, path_len) == 0) {
                inject_dbg("path on stack 0x%lx (untagged, len=%zu)", path_u, path_len);
                *path_addr_out = path_u;
                return 0;
            }
        }
    }
    return arm64_prepare_path(pid, lib_path, path_addr_out);
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

static void detach_all_threads(int pid, int inject_tid);

/** Continue a traced thread without delivering the stop signal (leave registers untouched). */
static void ptrace_release_other_thread(int tid, int status) {
    (void)status;
    ptrace(PTRACE_CONT, tid, NULL, (void *)0);
}

static int wait_for_ptrace_stop_on_tid(int timeout_ms, int want_tid, int *out_tid, int *out_status) {
    int waited = 0;
    while (waited < timeout_ms) {
        int status = 0;
        pid_t w = waitpid(-1, &status, __WALL | WNOHANG | WUNTRACED);
        if (w > 0) {
            if (WIFSTOPPED(status)) {
                if (want_tid > 0 && w != want_tid) {
                    ptrace_release_other_thread((int)w, status);
                    continue;
                }
                if (out_tid) *out_tid = (int)w;
                if (out_status) *out_status = status;
                return 0;
            }
        } else if (w < 0 && errno != ECHILD) {
            LOGE("wait_for_ptrace_stop_on_tid: %s", strerror(errno));
            return -1;
        }
        usleep(INJECT_ATTACH_POLL_MS * 1000);
        waited += INJECT_ATTACH_POLL_MS;
    }
    return -2;
}

static int ptrace_attach_thread(int tid) {
    ptrace(PTRACE_DETACH, tid, NULL, NULL);
    usleep(5000);
    if (ptrace(PTRACE_SEIZE, tid, NULL, (void *)(long)PTRACE_O_TRACEEXEC) == 0) {
        if (ptrace(PTRACE_INTERRUPT, tid, NULL, NULL) == 0) {
            return 0;
        }
        ptrace(PTRACE_DETACH, tid, NULL, NULL);
    }
    return ptrace(PTRACE_ATTACH, tid, NULL, NULL);
}

/** Attach every thread in the group; required on many Android builds (incl. Transsion). */
static int attach_all_threads(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *d = opendir(path);
    if (!d) {
        return ptrace_attach_thread(pid);
    }

    int attached = 0;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (ent->d_name[0] < '1') continue;
        int t = (int)strtol(ent->d_name, NULL, 10);
        if (t <= 0) continue;
        if (ptrace_attach_thread(t) == 0) {
            attached++;
        }
    }
    closedir(d);
    return attached > 0 ? 0 : -1;
}

static int attach_and_wait_stop(int pid, int *inject_tid, int *out_status) {
    detach_all_threads(pid, pid);
    usleep(50000);

    /* Always inject on the main thread (pid). Hijacking RenderThread corrupts the app. */
    if (ptrace_attach_thread(pid) == 0) {
        int status = 0;
        int tid = 0;
        if (wait_for_ptrace_stop_on_tid(INJECT_ATTACH_TIMEOUT_MS, pid, &tid, &status) == 0) {
            *inject_tid = pid;
            *out_status = status;
            return 0;
        }
        detach_all_threads(pid, pid);
    } else if (errno != 0) {
        return -1;
    }

    /* Fallback: freeze all threads, but still run remote calls only on main thread. */
    if (attach_all_threads(pid) < 0) {
        return -1;
    }
    ptrace(PTRACE_INTERRUPT, pid, NULL, NULL);
    int status = 0;
    int tid = 0;
    if (wait_for_ptrace_stop_on_tid(INJECT_ATTACH_TIMEOUT_MS, pid, &tid, &status) == 0) {
        *inject_tid = pid;
        *out_status = status;
        return 0;
    }
    detach_all_threads(pid, pid);
    return -2;
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
                ptrace(PTRACE_DETACH, t, NULL, (void *)0);
            }
        }
        closedir(d);
    } else {
        ptrace(PTRACE_DETACH, inject_tid, NULL, (void *)0);
    }
}

int inject_library(int pid, const char *lib_path) {
    out("ANDROCE_INJECT: start");
    inject_dbg("injector=2 build=%s pid=%d lib=%s", INJECTOR_BUILD, pid, lib_path);

    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d", pid);
    if (access(proc_path, F_OK) != 0) {
        out("ANDROCE_INJECT: FAIL target process not found");
        return -1;
    }
    inject_dbg("target alive");

    if (is_lib_loaded(pid, "libspeedhook")) {
        out("ANDROCE_INJECT: OK already loaded");
        return 0;
    }

    struct dlopen_target dlopen_target = resolve_dlopen(pid);
    if (!dlopen_target.addr) {
        out("ANDROCE_INJECT: FAIL dlopen symbol not found");
        return -1;
    }

    char msg[256];
    snprintf(msg, sizeof(msg), "ANDROCE_INJECT: dlopen=0x%lx kind=%d",
             dlopen_target.addr, dlopen_target.kind);
    out(msg);

    inject_dbg("attaching pid=%d", pid);
    int status = 0;
    int inject_tid = 0;
    int attach_rc = attach_and_wait_stop(pid, &inject_tid, &status);
    if (attach_rc != 0) {
        if (attach_rc == -1) {
            snprintf(msg, sizeof(msg), "ANDROCE_INJECT: FAIL ptrace attach: %s", strerror(errno));
            out(msg);
        } else {
            inject_err("attach timeout %dms tid=%d status=0x%x",
                       INJECT_ATTACH_TIMEOUT_MS, inject_tid, status);
            out("ANDROCE_INJECT: FAIL process did not stop (timeout)");
        }
        detach_all_threads(pid, pid);
        return -1;
    }

    char msg_tid[128];
    snprintf(msg_tid, sizeof(msg_tid), "ANDROCE_INJECT: stopped tid=%d sig=%d",
             inject_tid, WSTOPSIG(status));
    out(msg_tid);

    unsigned long stop_pc = get_stop_pc(pid);
    inject_dbg("stop_pc=0x%lx", stop_pc);
    if (!stop_pc) {
        detach_all_threads(pid, inject_tid);
        out("ANDROCE_INJECT: FAIL no BRK gadget in libc/linker");
        return -1;
    }

    unsigned long path_addr = 0;
    struct dlopen_target loader_target = {0};
    resolve_dlopen_candidate(pid, 0, &loader_target);
    patch_loader_caller(pid, inject_tid, &dlopen_target);
    patch_loader_caller(pid, inject_tid, &loader_target);

    if (arm64_prepare_path_ex(pid, inject_tid, stop_pc, lib_path, &path_addr) != 0) {
        out("ANDROCE_INJECT: could not write lib path into target RW memory");
    } else {
        inject_dbg("path_addr=0x%lx", path_addr);
    }

    /* Direct dlopen first (staged path in app lib dir) — memfd is slower and fragile on MTE. */
    if (!is_lib_loaded(pid, "libspeedhook") && path_addr) {
        unsigned long last_addr = 0;
        for (int ci = 0; ci < 8; ci++) {
            struct dlopen_target cand;
            if (resolve_dlopen_candidate(pid, ci, &cand) != 0) break;
            if (cand.addr == last_addr) continue;
            last_addr = cand.addr;
            patch_loader_caller(pid, inject_tid, &cand);

            snprintf(msg, sizeof(msg), "ANDROCE_INJECT: try direct ci=%d kind=%d addr=0x%lx",
                     ci, cand.kind, cand.addr);
            out(msg);
            if (cand.kind == DLOPEN_LOADER_3ARG) {
                inject_dbg("loader caller=0x%lx", cand.caller_addr);
            }
            char method_tag[32];
            snprintf(method_tag, sizeof(method_tag), "d%dk%d", ci, (int)cand.kind);
            unsigned long long ret = 0;
            int call_ok = arm64_try_direct_dlopen(inject_tid, &cand, path_addr, stop_pc, &ret,
                                                  method_tag);
            int loaded = is_lib_loaded(pid, "libspeedhook");
            snprintf(msg, sizeof(msg),
                     "ANDROCE_INJECT: direct ret=0x%llx call_ok=%d loaded=%d",
                     (unsigned long long)ret, call_ok, loaded);
            out(msg);
            if (loaded) {
                detach_all_threads(pid, inject_tid);
                out("ANDROCE_INJECT: OK lib loaded via direct dlopen");
                return 0;
            }
        }
    }

    if (!is_lib_loaded(pid, "libspeedhook") && path_addr) {
        out("ANDROCE_INJECT: try memfd dlopen");
        if (inject_via_memfd(pid, inject_tid, stop_pc, lib_path, path_addr, &loader_target) == 0) {
            detach_all_threads(pid, inject_tid);
            out("ANDROCE_INJECT: OK lib loaded via memfd");
            return 0;
        }
    }

    out("ANDROCE_INJECT: try mmap page");
    int inject_result = inject_via_mmap_page(pid, inject_tid, stop_pc, lib_path);

    if (inject_result == -2) {
        inject_dbg("mmap skipped (invalid page on API 35+)");
    } else if (inject_result != 0) {
        inject_err("mmap page path failed rc=%d", inject_result);
    }

    out("ANDROCE_INJECT: try fallback direct dlopen");
    if (!path_addr) {
        inject_err("no path_addr for fallback dlopen");
    } else {
        for (int ci = 0; ci < 8; ci++) {
            struct dlopen_target cand;
            if (resolve_dlopen_candidate(pid, ci, &cand) != 0) break;
            if (cand.kind == DLOPEN_ANDROID_EXT_3ARG) continue;
            patch_loader_caller(pid, inject_tid, &cand);

            unsigned long long ret = 0;
            snprintf(msg, sizeof(msg), "ANDROCE_INJECT: try fallback ci=%d kind=%d addr=0x%lx",
                     ci, cand.kind, cand.addr);
            out(msg);
            char method_tag[32];
            snprintf(method_tag, sizeof(method_tag), "fb%dk%d", ci, (int)cand.kind);
            if (arm64_remote_call(inject_tid, &cand, path_addr, stop_pc, &ret, method_tag) != 0) {
                continue;
            }
            if (is_lib_loaded(pid, "libspeedhook")) {
                out("ANDROCE_INJECT: OK direct dlopen succeeded");
                goto inject_detach_done;
            }
        }
    }

    out("ANDROCE_INJECT: try proc_mem dlopen");
    size_t path_len = strlen(lib_path) + 1;
    unsigned long rw_region_size = 0;
    unsigned long rw_base = find_anon_rw_region(pid, path_len + 0x100, &rw_region_size);
    unsigned long rw_addr = scratch_addr_at_tail(rw_base, rw_region_size, path_len);
    if (rw_addr) {
        inject_dbg("proc_mem scratch=0x%lx", rw_addr);
        if (proc_mem_write(pid, rw_addr, lib_path, path_len) == 0) {
            for (int ci = 0; ci < 8; ci++) {
                struct dlopen_target cand;
                if (resolve_dlopen_candidate(pid, ci, &cand) != 0) break;
                if (cand.kind == DLOPEN_ANDROID_EXT_3ARG) continue;
                patch_loader_caller(pid, inject_tid, &cand);

                unsigned long long ret = 0;
                snprintf(msg, sizeof(msg), "ANDROCE_INJECT: try proc_mem ci=%d kind=%d addr=0x%lx",
                         ci, cand.kind, cand.addr);
                out(msg);
                char method_tag[32];
                snprintf(method_tag, sizeof(method_tag), "pm%dk%d", ci, (int)cand.kind);
                if (arm64_remote_call(inject_tid, &cand, rw_addr, stop_pc, &ret, method_tag) != 0) {
                    continue;
                }
                if (is_lib_loaded(pid, "libspeedhook")) {
                    out("ANDROCE_INJECT: OK proc mem injection succeeded");
                    goto inject_detach_done;
                }
            }
        }
    } else {
        inject_err("no anon scratch for proc_mem");
    }

inject_detach_done:
    detach_all_threads(pid, inject_tid);

    if (is_lib_loaded(pid, "libspeedhook")) {
        out("ANDROCE_INJECT: OK lib loaded (post-inject maps check)");
        return 0;
    }
    inject_dbg("final maps check: lib not loaded");

    if (inject_result == -1) {
        if (access(proc_path, F_OK) != 0) {
            out("ANDROCE_INJECT: FAIL target crashed during injection");
        } else {
            snprintf(msg, sizeof(msg),
                     "ANDROCE_INJECT: FAIL could not load library (SELinux or linker blocked dlopen)");
            out(msg);
        }
        return -1;
    }

    /* All methods attempted but library not loaded */
    snprintf(msg, sizeof(msg),
             "ANDROCE_INJECT: FAIL all injection methods failed (Android 15 restrictions)");
    out(msg);
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
