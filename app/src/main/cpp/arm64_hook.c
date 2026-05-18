#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <dlfcn.h>

#define PAGE_SIZE 4096
#define PAGE_ALIGN(x) ((uintptr_t)(x) & ~(PAGE_SIZE - 1))
#define PATCH_SIZE 16

static int set_rwx(void *addr, size_t len) {
    uintptr_t start = PAGE_ALIGN((uintptr_t)addr);
    uintptr_t end = PAGE_ALIGN((uintptr_t)addr + len + PAGE_SIZE - 1);
    return mprotect((void *)start, end - start, PROT_READ | PROT_WRITE | PROT_EXEC);
}

static void flush_cache(void *addr, size_t len) {
    __builtin___clear_cache((char *)addr, (char *)addr + len);
}

int arm64_hook(void *target, void *replacement, void **orig_trampoline) {
    if (!target || !replacement || !orig_trampoline) return -1;

    void *tramp = mmap(NULL, PAGE_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) {
        return -1;
    }

    uint32_t *t = (uint32_t *)tramp;
    memcpy(t, target, PATCH_SIZE);
    t[4] = 0x58000051U; /* ldr x17, #8 */
    t[5] = 0xD61F0220U; /* br x17 */
    *(uint64_t *)(t + 6) = (uint64_t)((uintptr_t)target + PATCH_SIZE);
    flush_cache(tramp, PATCH_SIZE + 16);

    if (set_rwx(target, PATCH_SIZE) != 0) {
        munmap(tramp, PAGE_SIZE);
        return -1;
    }

    uint32_t *p = (uint32_t *)target;
    p[0] = 0x58000051U; /* ldr x17, #8 */
    p[1] = 0xD61F0220U; /* br x17 */
    *(uint64_t *)(p + 2) = (uint64_t)(uintptr_t)replacement;
    flush_cache(target, PATCH_SIZE);

    *orig_trampoline = tramp;
    return 0;
}
