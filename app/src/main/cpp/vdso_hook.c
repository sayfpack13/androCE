#define _GNU_SOURCE
#include <elf.h>
#include <stdint.h>
#include <string.h>
#include <sys/auxv.h>
#include <android/log.h>

#define VDSO_TAG "SpeedHook"
#define VDSO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VDSO_TAG, __VA_ARGS__)

#ifndef AT_SYSINFO_EHDR
#define AT_SYSINFO_EHDR 33
#endif

extern int arm64_hook(void *target, void *replacement, void **orig_trampoline);

static void *vdso_resolve(const char *sym_name) {
    uintptr_t base = getauxval(AT_SYSINFO_EHDR);
    if (!base) return NULL;

    const Elf64_Ehdr *eh = (const Elf64_Ehdr *)base;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return NULL;

    const Elf64_Shdr *sh = (const Elf64_Shdr *)(base + eh->e_shoff);
    const Elf64_Sym *dynsym = NULL;
    const char *dynstr = NULL;
    size_t sym_count = 0;

    for (int i = 0; i < eh->e_shnum; i++) {
        if (sh[i].sh_type != SHT_DYNSYM) continue;
        dynsym = (const Elf64_Sym *)(base + sh[i].sh_offset);
        sym_count = sh[i].sh_size / sizeof(Elf64_Sym);
        if (sh[i].sh_link < (unsigned)eh->e_shnum) {
            dynstr = (const char *)(base + sh[sh[i].sh_link].sh_offset);
        }
        break;
    }

    if (!dynsym || !dynstr) return NULL;

    for (size_t i = 0; i < sym_count; i++) {
        if (ELF64_ST_TYPE(dynsym[i].st_info) != STT_FUNC) continue;
        if (dynsym[i].st_name == 0) continue;
        if (strcmp(dynstr + dynsym[i].st_name, sym_name) != 0) continue;
        return (void *)(base + dynsym[i].st_value);
    }
    return NULL;
}

int vdso_hook_symbol(const char *symbol, void *replacement, void **original) {
    void *target = vdso_resolve(symbol);
    if (!target) return 0;
    if (arm64_hook(target, replacement, original) != 0) return 0;
    VDSO_LOGI("VDSO hooked %s at %p", symbol, target);
    return 1;
}
