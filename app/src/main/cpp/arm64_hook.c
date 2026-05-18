#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <dlfcn.h>
#include <stdatomic.h>

#define PATCH_SIZE 16

static size_t get_page_size(void) {
    long ps = sysconf(_SC_PAGESIZE);
    return ps > 0 ? (size_t)ps : 4096;
}

#define PAGE_ALIGN(x, ps) ((uintptr_t)(x) & ~((uintptr_t)(ps) - 1))

static int set_rwx(void *addr, size_t len) {
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
    uintptr_t start = PAGE_ALIGN((uintptr_t)addr, page_size);
    uintptr_t end = PAGE_ALIGN((uintptr_t)addr + len + page_size - 1, page_size);
    return mprotect((void *)start, end - start, PROT_READ | PROT_WRITE | PROT_EXEC);
}

static void flush_cache(void *addr, size_t len) {
    __builtin___clear_cache((char *)addr, (char *)addr + len);
}

int arm64_hook(void *target, void *replacement, void **orig_trampoline) {
    if (!target || !replacement || !orig_trampoline) return -1;

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

    /* Android 15 W^X policy: mmap RW first, then mprotect to add EXEC */
    void *tramp = mmap(NULL, page_size, PROT_READ | PROT_WRITE,
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

    /* Add PROT_EXEC after writing trampoline code (W^X compliance) */
    uintptr_t tramp_start = PAGE_ALIGN((uintptr_t)tramp, page_size);
    if (mprotect((void *)tramp_start, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        munmap(tramp, page_size);
        return -1;
    }

    if (set_rwx(target, PATCH_SIZE) != 0) {
        munmap(tramp, page_size);
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
