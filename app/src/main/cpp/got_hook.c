#define _GNU_SOURCE
#include <elf.h>
#include <link.h>
#include <dlfcn.h>
#include <string.h>
#include <strings.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <stdint.h>
#include <stdatomic.h>
#include <pthread.h>
#include <android/log.h>

#define GOT_TAG "SpeedHook"
#define GOT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, GOT_TAG, __VA_ARGS__)
#define GOT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, GOT_TAG, __VA_ARGS__)

#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1024
#endif

struct got_hook_ctx {
    const char *symbol;
    void *replacement;
    void **original;
    int count;
    int skipped;
};

static char g_pkg[256];
static int g_pkg_len = -1;
static int g_plt_allowlist_only = 0;
static pthread_mutex_t g_hook_mutex = PTHREAD_MUTEX_INITIALIZER;

void speedhook_set_plt_allowlist(int enable) {
    g_plt_allowlist_only = enable ? 1 : 0;
}

void speedhook_load_package_name(void) {
    if (g_pkg_len >= 0) return;
    g_pkg_len = 0;

    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return;

    ssize_t n = read(fd, g_pkg, sizeof(g_pkg) - 1);
    close(fd);
    if (n <= 0) return;

    g_pkg[n] = '\0';
    for (ssize_t i = 0; i < n; i++) {
        if (g_pkg[i] == ':') {
            g_pkg[i] = '\0';
            break;
        }
    }
    g_pkg_len = (int)strlen(g_pkg);
    if (g_pkg_len > 0) {
        GOT_LOGI("Hook scope package: %s", g_pkg);
    }
}

int speedhook_is_risky_module(const char *name) {
    if (!name) return 1;

    static const char *deny[] = {
        "libspeedhook.so",
        "libc.so",
        "libc++",
        "linker64",
        "linker",
        "libdl.so",
        "[vdso]",
        "libart",
        "libEGL",
        "libGLES",
        "libandroid",
        "libbinder",
        "libutils",
        "libhwui",
        "libjavacore",
        "libopenjdk",
        "libicu",
        "liblog.so",
        "libm.so",
        "libz.so",
        "libvulkan",
        "libmedia",
        "libcodec",
        "libsfplugin",
        "libstagefright",
        "libOpenSLES",
        "libOpenMAXAL",
        "libinput",
        "libgui",
        "libui",
        "libsqlite",
        "libhidl",
        "libnativeloader",
        "libbase.so",
        "libcutils",
        "libprocessgroup",
        "libselinux",
        "libvndksupport",
        "libperfetto",
        "libaaudio",
        "libaudioclient",
        "libcamera",
        "libskia",
        "libwebview",
        "libchrome",
        "libfirebase",
        "libgms",
        "libgoogle",
        "libads",
        "libcrashlytics",
        "libfmod",
        "libopus",
        "libvorbis",
        "libopenal",
        "libsoundtouch",
        "libAudioEngine",
        "libAkSoundEngine",
        "libswresample",
        "libavformat",
        "libavutil",
        "libavcodec",
        "libffmpeg",
        "libexoplayer",
        "libExoPlayer",
        "exoplayer",
        "libmedia3",
        "libcronet",
        "libloader",
        "libdatalib",
        "com.google.android.gms",
        NULL
    };

    for (int i = 0; deny[i]; i++) {
        if (strstr(name, deny[i])) return 1;
    }

    /* Also deny any module in /system/, /apex/, /vendor/, /product/ */
    if (strstr(name, "/system/") || strstr(name, "/apex/") ||
        strstr(name, "/vendor/") || strstr(name, "/product/")) {
        return 1;
    }

    /* Also deny any framework/boot oat files */
    if (strstr(name, "/system/framework/") || strstr(name, "boot-framework.oat") ||
        strstr(name, "boot-core-libart.oat") || strstr(name, "boot.oat")) {
        return 1;
    }

    return 0;
}

static int address_in_module(uintptr_t addr, size_t len, unsigned long base,
                             const Elf64_Phdr *phdr, int phnum) {
    uintptr_t end_addr = addr + len;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type != PT_LOAD) continue;
        uintptr_t start = (uintptr_t)base + phdr[i].p_vaddr;
        uintptr_t end = start + phdr[i].p_memsz;
        if (addr >= start && end_addr <= end) return 1;
    }
    return 0;
}

static int is_app_native_path(const char *name) {
    if (!name || name[0] == '\0') return 0;
    if (strstr(name, "/data/local/tmp/")) return 0;
    if (strstr(name, "/system/") || strstr(name, "/apex/")) return 0;
    if (strstr(name, "/lib/arm64/") || strstr(name, "/lib/arm/")) return 1;
    if (strstr(name, "!lib") && strstr(name, ".apk")) return 1;
    if (strstr(name, "/data/data/") && strstr(name, "/lib/")) return 1;
    return 0;
}

/** Match token as a library name component (avoids libfmod matching "libcpp"). */
static int libname_has_token(const char *base, const char *token) {
    size_t tlen = strlen(token);
    if (tlen == 0) return 0;

    if (strncasecmp(base, token, tlen) == 0) {
        char next = base[tlen];
        if (next == '\0' || next == '.' || next == '-' || next == '_') return 1;
    }

    const char *p = base;
    while ((p = strcasestr(p, token)) != NULL) {
        char before = (p > base) ? p[-1] : '\0';
        char after = p[tlen];
        int boundary_before = (p == base) || (before == '.' || before == '-' || before == '_');
        int boundary_after = (after == '\0' || after == '.' || after == '-' || after == '_');
        if (boundary_before && boundary_after) return 1;
        p += tlen;
    }
    return 0;
}

static int is_game_logic_lib(const char *name) {
    const char *base = strrchr(name, '/');
    base = base ? base + 1 : name;
    if (strstr(base, "!")) base = strstr(base, "!") + 1;

    static const char *allow[] = {
        "libcocos2dcpp", "libcocos2d", "libcocos",
        "libgame", "libGame", "libgd", "libGD",
        "libfoliage", "librob", "libjump", "libmain",
        "libproj", "liblua", "libgodot", "libmono", "libxlua",
        "libUE4", "libUnreal", "libApp",
        NULL
    };

    for (int i = 0; allow[i]; i++) {
        if (libname_has_token(base, allow[i])) return 1;
    }
    return 0;
}

static int should_hook_module(const char *name) {
    if (!name) return 0;

    /* First check: never hook system libraries regardless of mode */
    if (strstr(name, "/system/") || strstr(name, "/apex/") ||
        strstr(name, "/vendor/") || strstr(name, "/product/")) {
        return 0;
    }

    /* Second check: respect the explicit deny list */
    speedhook_load_package_name();
    if (speedhook_is_risky_module(name)) return 0;

    /* Third check: only hook app-native libraries */
    if (!is_app_native_path(name)) return 0;

    /* Fourth check: only hook libraries from the target app package */
    if (g_pkg_len > 0 && !strstr(name, g_pkg)) return 0;

    /* Fifth check: in allowlist mode, only hook game logic libraries */
    if (g_plt_allowlist_only) return is_game_logic_lib(name);

    return 1;
}

static int is_game_logic_caller(void *return_addr) {
    Dl_info info = {0};
    if (!return_addr || !dladdr(return_addr, &info) || !info.dli_fname) return 0;

    const char *f = info.dli_fname;
    if (strstr(f, "libspeedhook")) return 0;
    if (!is_app_native_path(f)) return 0;

    speedhook_load_package_name();
    if (g_pkg_len > 0 && !strstr(f, g_pkg)) return 0;
    return is_game_logic_lib(f);
}

int speedhook_is_game_caller(void *return_addr) {
    Dl_info info = {0};
    if (!return_addr || !dladdr(return_addr, &info) || !info.dli_fname) return 0;

    const char *f = info.dli_fname;
    if (strstr(f, "libspeedhook")) return 0;
    if (strstr(f, "/system/") || strstr(f, "/apex/")) return 0;
    if (!is_app_native_path(f)) return 0;

    speedhook_load_package_name();
    if (g_pkg_len > 0 && !strstr(f, g_pkg)) return 0;
    return !speedhook_is_risky_module(f);
}

static int protect_got_slot(void **slot) {
    uintptr_t page = (uintptr_t)slot & ~(uintptr_t)0xfff;
    return mprotect((void *)page, 0x1000, PROT_READ | PROT_WRITE);
}

static int is_system_time_resolver(void *fn) {
    Dl_info info = {0};
    if (!fn || !dladdr(fn, &info) || !info.dli_fname) return 0;

    const char *f = info.dli_fname;
    if (strstr(f, "libc.so")) return 1;
    if (strstr(f, "[vdso]")) return 1;
    if (strstr(f, "linker64") || strstr(f, "linker")) return 1;
    return 0;
}

static void atomic_got_patch(void **got, void *replacement) {
    __atomic_store_n((uintptr_t *)got, (uintptr_t)replacement, __ATOMIC_RELEASE);
}

static Elf64_Rela *resolve_jmprel(unsigned long base, unsigned long jmprel,
                                  size_t pltrelsz, const Elf64_Phdr *phdr, int phnum) {
    Elf64_Rela *candidates[2];
    int n = 0;

    candidates[n++] = (Elf64_Rela *)(base + jmprel);
    if ((uintptr_t)jmprel > (uintptr_t)base) {
        candidates[n++] = (Elf64_Rela *)jmprel;
    }

    for (int i = 0; i < n; i++) {
        uintptr_t start = (uintptr_t)candidates[i];
        if (address_in_module(start, pltrelsz, base, phdr, phnum)) {
            return candidates[i];
        }
    }
    return NULL;
}

static int hook_plt_in_dynamic(unsigned long base, const Elf64_Phdr *phdr, int phnum,
                               Elf64_Dyn *dyn, struct got_hook_ctx *ctx) {
    if (!dyn || !phdr || phnum <= 0) return 0;

    unsigned long jmprel = 0;
    size_t pltrelsz = 0;
    Elf64_Sym *dynsym = NULL;
    const char *dynstr = NULL;

    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:
                jmprel = d->d_un.d_ptr;
                break;
            case DT_PLTRELSZ:
                pltrelsz = d->d_un.d_val;
                break;
            case DT_SYMTAB:
                dynsym = (Elf64_Sym *)(base + d->d_un.d_ptr);
                break;
            case DT_STRTAB:
                dynstr = (const char *)(base + d->d_un.d_ptr);
                break;
            default:
                break;
        }
    }

    if (!jmprel || !pltrelsz || !dynsym || !dynstr) return 0;

    if (!address_in_module((uintptr_t)dynsym, sizeof(Elf64_Sym), base, phdr, phnum) ||
        !address_in_module((uintptr_t)dynstr, 1, base, phdr, phnum)) {
        return 0;
    }

    Elf64_Rela *rela = resolve_jmprel(base, jmprel, pltrelsz, phdr, phnum);
    if (!rela) return 0;

    size_t n = pltrelsz / sizeof(Elf64_Rela);
    int hooked = 0;

    for (size_t i = 0; i < n; i++) {
        if (ELF64_R_TYPE(rela[i].r_info) != R_AARCH64_JUMP_SLOT) continue;

        int sym_idx = (int)ELF64_R_SYM(rela[i].r_info);
        if (sym_idx <= 0) continue;

        const char *name = dynstr + dynsym[sym_idx].st_name;
        if (!name || strcmp(name, ctx->symbol) != 0) continue;

        void **got = (void **)((uintptr_t)base + rela[i].r_offset);
        if (!address_in_module((uintptr_t)got, sizeof(void *), base, phdr, phnum)) {
            ctx->skipped++;
            continue;
        }

        Dl_info got_owner = {0};
        if (dladdr(got, &got_owner) && got_owner.dli_fbase != NULL) {
            if ((uintptr_t)got_owner.dli_fbase != (uintptr_t)base) {
                ctx->skipped++;
                continue;
            }
        }

        void *current = (void *)__atomic_load_n((uintptr_t *)got, __ATOMIC_ACQUIRE);
        if (current == ctx->replacement) {
            hooked++;
            continue;
        }
        if (!is_system_time_resolver(current)) continue;

        /* Additional safety: verify the module itself is not a system library */
        if (dladdr(got, &got_owner) && got_owner.dli_fname) {
            GOT_LOGI("GOT slot %p in module %s", got, got_owner.dli_fname);
            if (strstr(got_owner.dli_fname, "/system/") ||
                strstr(got_owner.dli_fname, "/apex/") ||
                strstr(got_owner.dli_fname, "/vendor/") ||
                strstr(got_owner.dli_fname, "/product/")) {
                GOT_LOGW("Skipping system library: %s", got_owner.dli_fname);
                ctx->skipped++;
                continue;
            }
        } else {
            GOT_LOGW("dladdr failed for GOT slot %p, skipping", got);
            ctx->skipped++;
            continue;
        }

        Dl_info rep_info = {0};
        if (!dladdr(ctx->replacement, &rep_info) || !rep_info.dli_fname) continue;

        if (protect_got_slot(got) != 0) {
            ctx->skipped++;
            continue;
        }

        if (ctx->original && *ctx->original == NULL) {
            *ctx->original = current;
        }
        atomic_got_patch(got, ctx->replacement);
        void *verify = (void *)__atomic_load_n((uintptr_t *)got, __ATOMIC_ACQUIRE);
        if (verify != ctx->replacement) {
            atomic_got_patch(got, current);
            ctx->skipped++;
            continue;
        }
        hooked++;
    }

    return hooked;
}

#define MAX_TRACKED_MODS 128
static unsigned long g_hooked_mod_base[MAX_TRACKED_MODS];
static int g_hooked_mod_count = 0;

static int module_plt_done(unsigned long base) {
    for (int i = 0; i < g_hooked_mod_count; i++) {
        if (g_hooked_mod_base[i] == base) return 1;
    }
    return 0;
}

static void mark_module_plt_done(unsigned long base) {
    if (module_plt_done(base)) return;
    if (g_hooked_mod_count < MAX_TRACKED_MODS) {
        g_hooked_mod_base[g_hooked_mod_count++] = base;
    }
}

void got_hook_reset_module_tracking(void) {
    g_hooked_mod_count = 0;
}

static int phdr_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct got_hook_ctx *ctx = (struct got_hook_ctx *)data;

    /* Early check: never hook system libraries */
    if (info->dlpi_name) {
        if (strstr(info->dlpi_name, "/system/") ||
            strstr(info->dlpi_name, "/apex/") ||
            strstr(info->dlpi_name, "/vendor/") ||
            strstr(info->dlpi_name, "/product/")) {
            GOT_LOGI("Skipping system library: %s", info->dlpi_name);
            return 0;
        }
    }

    if (!should_hook_module(info->dlpi_name)) return 0;

    unsigned long base = info->dlpi_addr;
    if (module_plt_done(base)) return 0;
    const Elf64_Phdr *phdr = (const Elf64_Phdr *)info->dlpi_phdr;
    if (!phdr || info->dlpi_phnum <= 0) return 0;

    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (phdr[i].p_type != PT_DYNAMIC) continue;
        Elf64_Dyn *dyn = (Elf64_Dyn *)((uintptr_t)base + phdr[i].p_vaddr);
        if (!address_in_module((uintptr_t)dyn, sizeof(Elf64_Dyn), base, phdr, info->dlpi_phnum)) {
            continue;
        }
        int n = hook_plt_in_dynamic(base, phdr, info->dlpi_phnum, dyn, ctx);
        if (n > 0) {
            ctx->count += n;
            GOT_LOGI("PLT %s: %d in %s", ctx->symbol, n, info->dlpi_name);
        }
    }
    if (strcmp(ctx->symbol, "clock_gettime") == 0) {
        mark_module_plt_done(base);
    }
    return 0;
}

int got_hook_plt_all(const char *symbol, void *replacement, void **original) {
    struct got_hook_ctx ctx = {
        .symbol = symbol,
        .replacement = replacement,
        .original = original,
        .count = 0,
        .skipped = 0,
    };

    pthread_mutex_lock(&g_hook_mutex);
    dl_iterate_phdr(phdr_callback, &ctx);
    pthread_mutex_unlock(&g_hook_mutex);

    if (ctx.skipped > 0) {
        GOT_LOGW("Skipped %d unsafe GOT slots for %s", ctx.skipped, symbol);
    }
    return ctx.count;
}
