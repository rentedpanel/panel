#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <pthread.h>
#include <android/log.h>
#include "offsets.h"

#define TAG "ProInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_mem_fd = -1;
static uint64_t g_lib_base = 0;
static pid_t g_pid = 0;
static bool g_state[64] = {false};
static pthread_t g_worker;
static volatile bool g_running = false;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

// ============================================================================
// Process Memory Maps Parser (/proc/[pid]/maps)
// Scans mapped memory regions to locate executable library segments
// ============================================================================
static uint64_t find_lib_base(pid_t pid, const char *lib) {
    char path[64], line[512];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) {
        LOGE("[-] Failed to open memory map: %s", path);
        return 0;
    }

    uint64_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, lib) && (strstr(line, "r-xp") || strstr(line, "r--p") || strstr(line, "r-s"))) {
            unsigned long long addr = 0;
            if (sscanf(line, "%llx", &addr) == 1) {
                base = (uint64_t)addr;
                LOGI("[+] Detected %s base address: 0x%llx", lib, (unsigned long long)base);
                break;
            }
        }
    }
    fclose(f);
    return base;
}

// ============================================================================
// Direct Process Memory I/O (/proc/[pid]/mem)
// Performs thread-safe positional reads and writes
// ============================================================================
static bool read_mem(uint64_t addr, void *buf, size_t len) {
    if (g_mem_fd < 0) return false;
    return pread(g_mem_fd, buf, len, (off_t)addr) == (ssize_t)len;
}

static bool write_mem(uint64_t addr, const void *buf, size_t len) {
    if (g_mem_fd < 0) return false;
    return pwrite(g_mem_fd, buf, len, (off_t)addr) == (ssize_t)len;
}

// ============================================================================
// Original Memory Snapshot
// Reads and preserves unpatched memory bytes to restore when toggled OFF
// ============================================================================
static void backup_original_bytes() {
    if (g_lib_base == 0 || g_mem_fd < 0) return;

    for (int i = 0; i < Offsets::FEATURE_COUNT; i++) {
        Offsets::Feature &f = Offsets::FEATURES[i];
        if (!f.orig_captured) {
            uint64_t addr = g_lib_base + f.offset;
            if (read_mem(addr, f.orig, f.size)) {
                f.orig_captured = true;
                LOGI("[+] Captured original memory for feature #%d at 0x%llx", i, (unsigned long long)addr);
            } else {
                LOGE("[-] Failed to capture original memory for feature #%d at 0x%llx", i, (unsigned long long)addr);
            }
        }
    }
}

// ============================================================================
// Background Injection Loop
// Continuously re-applies active patches to counteract in-game value resets
// ============================================================================
static void *worker_loop(void *) {
    LOGI("[+] Background memory persistence worker started");
    while (g_running) {
        if (g_lib_base != 0 && g_mem_fd >= 0) {
            pthread_mutex_lock(&g_lock);
            for (int i = 0; i < Offsets::FEATURE_COUNT; i++) {
                if (g_state[i]) {
                    const Offsets::Feature &f = Offsets::FEATURES[i];
                    uint64_t addr = g_lib_base + f.offset;
                    if (!write_mem(addr, f.bytes, f.size)) {
                        LOGE("[!] Write failed at 0x%llx (feature #%d)", (unsigned long long)addr, i);
                    }
                }
            }
            pthread_mutex_unlock(&g_lock);
        }
        usleep(300000); // 300ms persistence interval
    }
    LOGI("[+] Background memory persistence worker stopped");
    return nullptr;
}

// ============================================================================
// JNI Exported Functions
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pro_injector_NativeBridge_init(JNIEnv *, jclass, jint jpid) {
    g_pid = (pid_t)jpid;
    if (g_pid <= 0) {
        LOGE("[-] Invalid target PID provided: %d", jpid);
        return JNI_FALSE;
    }

    if (g_mem_fd >= 0) {
        close(g_mem_fd);
        g_mem_fd = -1;
    }

    char mempath[64];
    snprintf(mempath, sizeof(mempath), "/proc/%d/mem", g_pid);
    g_mem_fd = open(mempath, O_RDWR);
    if (g_mem_fd < 0) {
        LOGE("[-] Open failed on %s (Root / SELinux permissive required)", mempath);
        return JNI_FALSE;
    }

    g_lib_base = find_lib_base(g_pid, TARGET_LIB);
    LOGI("[+] Memory descriptor opened for PID=%d. Target lib base: 0x%llx", g_pid, (unsigned long long)g_lib_base);

    if (g_lib_base != 0) {
        backup_original_bytes();
    }

    if (!g_running) {
        g_running = true;
        pthread_create(&g_worker, nullptr, worker_loop, nullptr);
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pro_injector_NativeBridge_setFeature(JNIEnv *, jclass, jint id, jboolean on) {
    if (id < 0 || id >= Offsets::FEATURE_COUNT || g_mem_fd < 0) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_lock);
    g_state[id] = on;

    if (g_lib_base == 0) {
        // Retry acquiring target library base address if late-loaded
        g_lib_base = find_lib_base(g_pid, TARGET_LIB);
        if (g_lib_base != 0) {
            backup_original_bytes();
        }
    }

    if (g_lib_base != 0) {
        Offsets::Feature &f = Offsets::FEATURES[id];
        uint64_t addr = g_lib_base + f.offset;

        if (on) {
            write_mem(addr, f.bytes, f.size);
            LOGI("[+] Feature #%d ENABLED -> Patched at 0x%llx", id, (unsigned long long)addr);
        } else {
            if (f.orig_captured) {
                write_mem(addr, f.orig, f.size);
                LOGI("[+] Feature #%d DISABLED -> Restored original bytes at 0x%llx", id, (unsigned long long)addr);
            } else {
                LOGE("[-] Feature #%d DISABLED but original memory was uncaptured", id);
            }
        }
    }

    pthread_mutex_unlock(&g_lock);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pro_injector_NativeBridge_isSupported(JNIEnv *, jclass) {
    if (g_lib_base == 0 && g_pid > 0) {
        g_lib_base = find_lib_base(g_pid, TARGET_LIB);
        if (g_lib_base != 0) {
            backup_original_bytes();
        }
    }
    return (g_lib_base != 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pro_injector_NativeBridge_stop(JNIEnv *, jclass) {
    g_running = false;
    if (g_mem_fd >= 0) {
        close(g_mem_fd);
        g_mem_fd = -1;
    }
    LOGI("[+] Native bridge halted and memory descriptor released");
}
