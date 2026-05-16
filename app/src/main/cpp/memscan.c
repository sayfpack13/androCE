/*
 * Native memory scanner for Android 15+ compatibility.
 * Uses process_vm_readv instead of /proc/[pid]/mem which is blocked on newer Android kernels.
 * 
 * Build: $ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android34-clang
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/uio.h>
#include <errno.h>
#include <signal.h>

static void crash_handler(int sig) {
    printf("# crash:signal=%d\n", sig);
    fflush(stdout);
    _exit(128 + sig);
}

#define CHUNK_SIZE (64 * 1024)  /* 64 KB */
#define MAX_RESULTS 500000

static int hexchar(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int hex_to_bytes(const char *hex, unsigned char *out, int max_len) {
    int len = strlen(hex);
    int j = 0;
    for (int i = 0; i < len && j < max_len; i += 2) {
        int hi = hexchar(hex[i]);
        int lo = (i + 1 < len) ? hexchar(hex[i + 1]) : 0;
        if (hi < 0 || lo < 0) return -1;
        out[j++] = (hi << 4) | lo;
    }
    return j;
}

static long read_mem(int pid, unsigned long addr, unsigned char *buf, size_t len) {
    struct iovec local = { buf, len };
    struct iovec remote = { (void *)addr, len };
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (n < 0) {
        /* Print to stdout so libsu captures it */
        printf("# read_err:pid=%d addr=%lu len=%zu errno=%d\n", pid, addr, len, errno);
        fflush(stdout);
        return -1;
    }
    return n;
}

/*
 * scan mode: argv[1]="scan"
 *   argv[2]=pid
 *   argv[3]=pattern_hex
 *   argv[4]=pattern_len
 *   argv[5]=wildcard_hex (or "none")
 *   argv[6...]=region pairs "start:size"
 */
int scan_mode(int argc, char **argv) {
    printf("# scan_enter\n"); fflush(stdout);
    int pid = atoi(argv[2]);
    const char *pat_hex = argv[3];
    int pat_len = atoi(argv[4]);
    const char *wc_hex = argv[5];
    int use_wc = (strcmp(wc_hex, "none") != 0);
    unsigned char wildcard = 0;
    if (use_wc) {
        unsigned char tmp[1];
        hex_to_bytes(wc_hex, tmp, 1);
        wildcard = tmp[0];
    }

    printf("# scan_pat pid=%d pat_len=%d use_wc=%d\n", pid, pat_len, use_wc); fflush(stdout);
    unsigned char *pat = malloc(pat_len);
    int actual_len = hex_to_bytes(pat_hex, pat, pat_len);
    if (actual_len != pat_len) {
        printf("# error:bad_pattern\n"); fflush(stdout);
        return 1;
    }

    unsigned char *chunk = malloc(CHUNK_SIZE);
    if (!chunk) {
        printf("# error:malloc\n"); fflush(stdout);
        return 1;
    }

    int found = 0;
    int skipped = 0;
    int capped = 0;
    int region_count = 0;

    printf("# scan_loop argc=%d\n", argc); fflush(stdout);
    for (int i = 6; i < argc && !capped; i++) {
        unsigned long start, size;
        if (sscanf(argv[i], "%lu:%lu", &start, &size) != 2) continue;
        region_count++;

        unsigned long off = 0;
        while (off < size && !capped) {
            size_t rsize = (size - off < CHUNK_SIZE) ? (size - off) : CHUNK_SIZE;
            long n = read_mem(pid, start + off, chunk, rsize);
            if (n < 0) {
                skipped++;
                off += rsize;
                continue;
            }
            if (n == 0) {
                off += rsize;
                continue;
            }

            size_t dlen = (size_t)n;
            if (!use_wc) {
                for (size_t j = 0; j <= dlen - pat_len && found < MAX_RESULTS; j++) {
                    if (memcmp(chunk + j, pat, pat_len) == 0) {
                        printf("%lu\n", start + off + j);
                        found++;
                        if (found >= MAX_RESULTS) { capped = 1; break; }
                    }
                }
            } else {
                for (size_t j = 0; j <= dlen - pat_len && found < MAX_RESULTS; j++) {
                    int match = 1;
                    for (int k = 0; k < pat_len; k++) {
                        if (pat[k] != wildcard && chunk[j + k] != pat[k]) {
                            match = 0;
                            break;
                        }
                    }
                    if (match) {
                        printf("%lu\n", start + off + j);
                        found++;
                        if (found >= MAX_RESULTS) { capped = 1; break; }
                    }
                }
            }
            off += (rsize > (size_t)pat_len) ? (rsize - pat_len + 1) : rsize;
        }
    }

    printf("# done:regions=%d found=%d skipped=%d capped=%d\n", region_count, found, skipped, capped);
    fflush(stdout);
    printf("# skipped:%d\n", skipped);
    printf("# capped:%d\n", capped);
    fflush(stdout);

    free(pat);
    free(chunk);
    return 0;
}

/*
 * read mode: argv[1]="read"
 *   argv[2]=pid
 *   argv[3]=addr
 *   argv[4]=len
 */
int read_mode(int argc, char **argv) {
    int pid = atoi(argv[2]);
    unsigned long addr = strtoul(argv[3], NULL, 0);
    int len = atoi(argv[4]);

    unsigned char *buf = malloc(len);
    if (!buf) {
        fprintf(stderr, "# error:malloc\n");
        return 1;
    }

    long n = read_mem(pid, addr, buf, len);
    if (n == len) {
        for (int i = 0; i < len; i++) printf("%02x", buf[i]);
        printf("\n");
    }

    free(buf);
    return 0;
}

/*
 * batch read mode: argv[1]="readbatch"
 *   argv[2]=pid
 *   argv[3...]="addr:len"
 */
int readbatch_mode(int argc, char **argv) {
    int pid = atoi(argv[2]);
    for (int i = 3; i < argc; i++) {
        unsigned long addr;
        int len;
        if (sscanf(argv[i], "%lu:%d", &addr, &len) != 2) continue;

        unsigned char *buf = malloc(len);
        if (!buf) continue;

        long n = read_mem(pid, addr, buf, len);
        if (n == len) {
            printf("%d:", i - 3);
            for (int j = 0; j < len; j++) printf("%02x", buf[j]);
            printf("\n");
        } else {
            printf("%d:\n", i - 3);
        }
        free(buf);
    }
    return 0;
}

/*
 * snapshot mode: argv[1]="snapshot"
 *   argv[2]=pid
 *   argv[3]=slot_size
 *   argv[4]=step
 *   argv[5...]=region pairs "start:size"
 */
int snapshot_mode(int argc, char **argv) {
    int pid = atoi(argv[2]);
    int slot_size = atoi(argv[3]);
    int step = atoi(argv[4]);

    unsigned char *chunk = malloc(CHUNK_SIZE);
    if (!chunk) {
        fprintf(stderr, "# error:malloc\n");
        return 1;
    }

    int found = 0;
    int skipped = 0;
    int capped = 0;

    for (int i = 5; i < argc && !capped; i++) {
        unsigned long start, size;
        if (sscanf(argv[i], "%lu:%lu", &start, &size) != 2) continue;

        unsigned long off = 0;
        while (off < size && !capped) {
            size_t rsize = (size - off < CHUNK_SIZE) ? (size - off) : CHUNK_SIZE;
            long n = read_mem(pid, start + off, chunk, rsize);
            if (n < 0) {
                skipped++;
                off += rsize;
                continue;
            }
            if (n == 0) {
                off += rsize;
                continue;
            }

            size_t dlen = (size_t)n;
            size_t last = (dlen > (size_t)slot_size) ? (dlen - slot_size) : 0;
            for (size_t j = 0; j <= last && found < MAX_RESULTS; j += step) {
                printf("%lu:", start + off + j);
                for (int k = 0; k < slot_size; k++) printf("%02x", chunk[j + k]);
                printf("\n");
                found++;
                if (found >= MAX_RESULTS) { capped = 1; break; }
            }
            off += rsize;
        }
    }

    printf("# skipped:%d\n", skipped);
    printf("# capped:%d\n", capped);

    free(chunk);
    return 0;
}

/*
 * write mode: argv[1]="write"
 *   argv[2]=pid
 *   argv[3...]="addr:hexdata"
 */
int write_mode(int argc, char **argv) {
    int pid = atoi(argv[2]);
    int ok = 1;

    for (int i = 3; i < argc; i++) {
        unsigned long addr;
        char hex[1024];
        if (sscanf(argv[i], "%lu:%1023s", &addr, hex) != 2) continue;

        int len = strlen(hex) / 2;
        unsigned char *buf = malloc(len);
        if (!buf) { ok = 0; continue; }

        hex_to_bytes(hex, buf, len);

        struct iovec local = { buf, len };
        struct iovec remote = { (void *)addr, len };
        ssize_t n = process_vm_writev(pid, &local, 1, &remote, 1, 0);
        if (n != len) ok = 0;

        free(buf);
    }

    printf("# ok:%d\n", ok);
    return 0;
}

int main(int argc, char **argv) {
    signal(SIGSEGV, crash_handler);
    signal(SIGABRT, crash_handler);
    signal(SIGILL, crash_handler);
    signal(SIGBUS, crash_handler);
    signal(SIGFPE, crash_handler);
    printf("# start:1\n");
    fflush(stdout);
    if (argc < 3) {
        fprintf(stderr, "Usage: memscan <mode> <pid> [args...]\n");
        return 1;
    }

    if (strcmp(argv[1], "scan") == 0) return scan_mode(argc, argv);
    if (strcmp(argv[1], "read") == 0) return read_mode(argc, argv);
    if (strcmp(argv[1], "readbatch") == 0) return readbatch_mode(argc, argv);
    if (strcmp(argv[1], "snapshot") == 0) return snapshot_mode(argc, argv);
    if (strcmp(argv[1], "write") == 0) return write_mode(argc, argv);

    fprintf(stderr, "Unknown mode: %s\n", argv[1]);
    return 1;
}
