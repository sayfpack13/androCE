#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <dirent.h>
#include <android/log.h>
#include <errno.h>
#include <signal.h>

#define LOG_TAG "SpeedInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ARM64 shellcode for calling dlopen
// This is simplified and would need to be adapted for the target architecture
static unsigned char arm64_dlopen_shellcode[] = {
    // Save registers
    0xFD, 0x7B, 0xBF, 0xA9,     // stp x29, x30, [sp, #-16]!
    0xFD, 0x03, 0x00, 0x91,     // mov x29, sp
    
    // Load library path address (will be patched)
    0x00, 0x00, 0x00, 0x58,     // ldr x0, #0 (path addr)
    0x21, 0x00, 0x80, 0x52,     // mov w1, #1 (RTLD_LAZY)
    
    // Call dlopen (address will be resolved and patched)
    0x00, 0x00, 0x00, 0x58,     // ldr x16, dlopen_addr
    0x00, 0x02, 0x1F, 0xD6,     // br x16
    
    // Restore and return
    0xFD, 0x7B, 0xC1, 0xA8,     // ldp x29, x30, [sp], #16
    0xC0, 0x03, 0x5F, 0xD6      // ret
};

// Find dlopen address in target process
static unsigned long find_dlopen_addr(int pid) {
    char path[256];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *fp = fopen(path, "r");
    if (!fp) return 0;
    
    unsigned long addr = 0;
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        // Look for linker or libc where dlopen is
        if (strstr(line, "linker") || strstr(line, "libc.so")) {
            // Parse start address
            sscanf(line, "%lx", &addr);
            break;
        }
    }
    fclose(fp);
    return addr;
}

// Simple injection using process_vm_writev would require kernel support
// Fallback to simpler method: use app_process with LD_PRELOAD for new processes
// For existing processes, we need ptrace

int inject_library(int pid, const char *lib_path) {
    LOGI("Attempting to inject into pid %d", pid);
    LOGI("Library path: %s", lib_path);
    
    // Check if process exists
    char proc_path[256];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d", pid);
    if (access(proc_path, F_OK) != 0) {
        LOGE("Process %d does not exist", pid);
        return -1;
    }
    
    // Check if already injected
    char maps_path[256];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *maps = fopen(maps_path, "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "libspeedhook.so")) {
                LOGI("Library already injected");
                fclose(maps);
                return 0; // Already injected, success
            }
        }
        fclose(maps);
    }
    
    // Try to attach with ptrace
    int ret = ptrace(PTRACE_ATTACH, pid, NULL, NULL);
    if (ret < 0) {
        LOGE("PTRACE_ATTACH failed: %s (errno=%d)", strerror(errno), errno);
        // This is expected on Android 10+ due to restrictions
        // Fall through to alternative method
    } else {
        // Wait for process to stop
        int status;
        waitpid(pid, &status, WUNTRACED);
        
        // Get registers to find injection point
        // This would require architecture-specific register structures
        
        // For now, just detach
        ptrace(PTRACE_DETACH, pid, NULL, NULL);
        LOGI("Ptrace attach succeeded but injection not fully implemented");
    }
    
    // Alternative: Try using /proc/pid/mem for direct memory writing
    // This requires CAP_SYS_PTRACE or being root
    char mem_path[256];
    snprintf(mem_path, sizeof(mem_path), "/proc/%d/mem", pid);
    int mem_fd = open(mem_path, O_RDWR);
    if (mem_fd >= 0) {
        LOGI("Opened /proc/%d/mem successfully", pid);
        // Would need to:
        // 1. Find executable memory region
        // 2. Write shellcode
        // 3. Redirect execution
        // 4. Restore original code
        close(mem_fd);
    }
    
    // Last resort: Check if we can use debuggerd or other system services
    // Some Android versions allow injection via debugger interfaces
    
    LOGE("Direct injection not available on this Android version");
    LOGE("Library must be preloaded at process startup using wrap property");
    
    return -1;
}

int main(int argc, char *argv[]) {
    if (argc != 3) {
        fprintf(stderr, "Usage: %s <pid> <library_path>\n", argv[0]);
        return 1;
    }
    
    int pid = atoi(argv[1]);
    const char *lib_path = argv[2];
    
    return inject_library(pid, lib_path);
}
