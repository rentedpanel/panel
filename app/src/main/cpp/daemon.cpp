// ============================================================================
// ProInjector - Standalone Root Memory Engine Daemon (mem_daemon)
// ============================================================================
// Runs as a standalone binary with real UID 0 (root) via `su -c`.
// Directly accesses `/proc/[pid]/mem` and `/proc/[pid]/maps` without SELinux
// userspace restrictions. Communicates with Android app over standard pipes.
// ============================================================================

#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <csignal>
#include <pthread.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>
#include "offsets.h"

#define TAG "ProDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_mem_fd = -1;
static uint64_t g_lib_base = 0;
static pid_t g_target_pid = 0;
static bool g_state[64] = {false};
static pthread_t g_worker;
static volatile bool g_running = false;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

// ============================================================================
// Process Memory Maps Parser (/proc/[pid]/maps)
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

static void restore_all_memory() {
    pthread_mutex_lock(&g_lock);
    if (g_lib_base != 0 && g_mem_fd >= 0) {
        for (int i = 0; i < Offsets::FEATURE_COUNT; i++) {
            Offsets::Feature &f = Offsets::FEATURES[i];
            if (f.orig_captured && g_state[i]) {
                uint64_t addr = g_lib_base + f.offset;
                write_mem(addr, f.orig, f.size);
                g_state[i] = false;
                LOGI("[+] Restored original memory for feature #%d", i);
            }
        }
    }
    pthread_mutex_unlock(&g_lock);
}

// ============================================================================
// Background Persistence Worker (300ms Interval)
// ============================================================================
static void *worker_loop(void *) {
    LOGI("[+] Background memory persistence worker active");
    while (g_running) {
        if (g_lib_base != 0 && g_mem_fd >= 0) {
            pthread_mutex_lock(&g_lock);
            for (int i = 0; i < Offsets::FEATURE_COUNT; i++) {
                if (g_state[i]) {
                    const Offsets::Feature &f = Offsets::FEATURES[i];
                    uint64_t addr = g_lib_base + f.offset;
                    write_mem(addr, f.bytes, f.size);
                }
            }
            pthread_mutex_unlock(&g_lock);
        }
        usleep(300000); // 300ms
    }
    LOGI("[+] Background memory persistence worker terminated");
    return nullptr;
}

// ============================================================================
// Clean Signal Shutdown
// ============================================================================
static void signal_handler(int sig) {
    LOGI("[*] Signal %d received. Restoring game memory and exiting.", sig);
    g_running = false;
    restore_all_memory();
    if (g_mem_fd >= 0) {
        close(g_mem_fd);
        g_mem_fd = -1;
    }
    exit(0);
}

// ============================================================================
// Main Daemon Entry Point
// ============================================================================
int main(int argc, char *argv[]) {
    // Register signal handlers for clean memory restore
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP, signal_handler);

    uid_t uid = getuid();
    gid_t gid = getgid();
    pid_t self_pid = getpid();

    LOGI("[+] ProDaemon initializing. PID=%d, UID=%d, GID=%d", self_pid, uid, gid);

    // Initial handshake message to parent stdout
    printf("DAEMON_READY pid=%d uid=%d\n", self_pid, uid);
    fflush(stdout);

    char line[256];
    while (fgets(line, sizeof(line), stdin)) {
        // Strip newline characters
        line[strcspn(line, "\r\n")] = 0;

        if (strncmp(line, "INIT", 4) == 0) {
            int pid = 0;
            if (sscanf(line, "INIT %d", &pid) == 1 && pid > 0) {
                g_target_pid = (pid_t)pid;
                if (g_mem_fd >= 0) {
                    close(g_mem_fd);
                    g_mem_fd = -1;
                }

                char mempath[64];
                snprintf(mempath, sizeof(mempath), "/proc/%d/mem", g_target_pid);
                g_mem_fd = open(mempath, O_RDWR);

                if (g_mem_fd < 0) {
                    LOGE("[-] Failed to open %s with O_RDWR (UID=%d)", mempath, getuid());
                    printf("ERR_OPEN_MEM %d\n", g_target_pid);
                    fflush(stdout);
                    continue;
                }

                g_lib_base = find_lib_base(g_target_pid, TARGET_LIB);
                if (g_lib_base != 0) {
                    backup_original_bytes();
                }

                if (!g_running) {
                    g_running = true;
                    pthread_create(&g_worker, nullptr, worker_loop, nullptr);
                }

                printf("OK_INIT pid=%d base=0x%llx\n", g_target_pid, (unsigned long long)g_lib_base);
                fflush(stdout);
            } else {
                printf("ERR_INVALID_PID\n");
                fflush(stdout);
            }
        } else if (strncmp(line, "SET", 3) == 0) {
            int id = -1;
            int on = 0;
            if (sscanf(line, "SET %d %d", &id, &on) == 2) {
                if (id >= 0 && id < Offsets::FEATURE_COUNT && g_mem_fd >= 0) {
                    pthread_mutex_lock(&g_lock);
                    g_state[id] = (on != 0);

                    if (g_lib_base == 0) {
                        g_lib_base = find_lib_base(g_target_pid, TARGET_LIB);
                        if (g_lib_base != 0) {
                            backup_original_bytes();
                        }
                    }

                    bool write_ok = false;
                    if (g_lib_base != 0) {
                        Offsets::Feature &f = Offsets::FEATURES[id];
                        uint64_t addr = g_lib_base + f.offset;
                        if (g_state[id]) {
                            write_ok = write_mem(addr, f.bytes, f.size);
                        } else if (f.orig_captured) {
                            write_ok = write_mem(addr, f.orig, f.size);
                        }
                    }
                    pthread_mutex_unlock(&g_lock);

                    printf("OK_SET id=%d on=%d status=%s\n", id, on, write_ok ? "APPLIED" : "PENDING");
                    fflush(stdout);
                } else {
                    printf("ERR_SET_PARAMS\n");
                    fflush(stdout);
                }
            }
        } else if (strncmp(line, "RESCAN", 6) == 0) {
            if (g_target_pid > 0) {
                g_lib_base = find_lib_base(g_target_pid, TARGET_LIB);
                if (g_lib_base != 0) {
                    backup_original_bytes();
                }
                printf("OK_RESCAN base=0x%llx\n", (unsigned long long)g_lib_base);
            } else {
                printf("ERR_NO_PID\n");
            }
            fflush(stdout);
        } else if (strncmp(line, "STATUS", 6) == 0) {
            int active = 0;
            for (int i = 0; i < Offsets::FEATURE_COUNT; i++) {
                if (g_state[i]) active++;
            }
            printf("STATUS pid=%d base=0x%llx fd=%d active=%d\n",
                   g_target_pid, (unsigned long long)g_lib_base, g_mem_fd, active);
            fflush(stdout);
        } else if (strncmp(line, "STOP", 4) == 0 || strncmp(line, "EXIT", 4) == 0) {
            printf("OK_STOPPING\n");
            fflush(stdout);
            break;
        } else if (strncmp(line, "PING", 4) == 0) {
            printf("PONG uid=%d\n", getuid());
            fflush(stdout);
        }
    }

    // Clean exit on stdin close or STOP command
    LOGI("[*] ProDaemon shutting down. Cleaning memory patches.");
    g_running = false;
    restore_all_memory();
    if (g_mem_fd >= 0) {
        close(g_mem_fd);
        g_mem_fd = -1;
    }
    LOGI("[+] ProDaemon shutdown complete.");
    return 0;
}
