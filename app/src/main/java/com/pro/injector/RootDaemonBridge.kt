package com.pro.injector

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RootDaemonBridge
 *
 * Bridges the Android UI process with the native standalone root daemon (`mem_daemon`).
 * Launches the daemon process under real UID 0 using `su -c` to bypass Android SELinux
 * userspace sandbox restrictions on `/proc/[pid]/mem`.
 */
object RootDaemonBridge {

    private const val TAG = "RootDaemonBridge"

    private var daemonProcess: Process? = null
    private var daemonWriter: BufferedWriter? = null
    private var daemonReader: BufferedReader? = null

    private val isRunning = AtomicBoolean(false)
    private val isAttached = AtomicBoolean(false)

    @Volatile
    var currentBaseAddress: String = "0x0"
        private set

    @Volatile
    var daemonPid: Int = 0
        private set

    @Volatile
    var daemonUid: Int = -1
        private set

    /**
     * Locates or prepares the native executable binary path.
     */
    private fun getDaemonExecutable(context: Context): File {
        val filesDir = context.filesDir
        val daemonFile = File(filesDir, "mem_daemon")

        // Check if executable exists in nativeLibraryDir
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeDir, "mem_daemon")
        if (candidate.exists() && candidate.canExecute()) {
            return candidate
        }

        // Check in assets or app lib directory
        if (!daemonFile.exists() || daemonFile.length() == 0L) {
            try {
                // Copy if bundled in assets
                context.assets.open("mem_daemon").use { input ->
                    daemonFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // If not in assets, copy from nativeLibraryDir if present
                if (candidate.exists()) {
                    candidate.inputStream().use { input ->
                        daemonFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }

        // Ensure executable flag
        daemonFile.setExecutable(true, false)
        return daemonFile
    }

    /**
     * Launches the root daemon via `su -c` and performs initial handshake.
     */
    @Synchronized
    fun startDaemon(context: Context): Boolean {
        if (isRunning.get() && daemonProcess != null) {
            return true
        }

        try {
            val daemonExe = getDaemonExecutable(context)
            val daemonPath = daemonExe.absolutePath

            // Deploy to /data/local/tmp to bypass any app-private directory `noexec` mount restrictions
            val tmpDaemon = "/data/local/tmp/pro_mem_daemon"
            val suCmd = "cp $daemonPath $tmpDaemon 2>/dev/null || cat $daemonPath > $tmpDaemon; chmod 755 $tmpDaemon; exec $tmpDaemon"
            Log.i(TAG, "[+] Launching root memory daemon via: $suCmd")

            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", suCmd))
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))

            // Wait for DAEMON_READY banner
            val handshake = reader.readLine()
            Log.i(TAG, "[+] Daemon handshake response: $handshake")

            if (handshake != null && handshake.startsWith("DAEMON_READY")) {
                // Parse PID and UID from DAEMON_READY pid=1234 uid=0
                val parts = handshake.split(" ")
                for (p in parts) {
                    if (p.startsWith("pid=")) daemonPid = p.substring(4).toIntOrNull() ?: 0
                    if (p.startsWith("uid=")) daemonUid = p.substring(4).toIntOrNull() ?: -1
                }

                daemonProcess = proc
                daemonWriter = writer
                daemonReader = reader
                isRunning.set(true)
                Log.i(TAG, "[✓] Root daemon successfully operational (PID=$daemonPid, UID=$daemonUid)")
                return true
            } else {
                Log.w(TAG, "[-] Unexpected daemon handshake: $handshake")
                proc.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[-] Failed to start root daemon via su: ${e.message}", e)
        }

        return false
    }

    /**
     * Initializes target PID memory attachment in the root daemon.
     */
    @Synchronized
    fun init(context: Context, pid: Int): Boolean {
        if (pid <= 0) return false

        // Ensure daemon is active
        if (!isRunning.get() || daemonProcess == null) {
            if (!startDaemon(context)) {
                // Fall back to direct in-process JNI
                Log.w(TAG, "[!] Daemon unavailable, falling back to in-process NativeBridge JNI")
                return NativeBridge.init(pid)
            }
        }

        return try {
            val writer = daemonWriter ?: return NativeBridge.init(pid)
            val reader = daemonReader ?: return NativeBridge.init(pid)

            writer.write("INIT $pid\n")
            writer.flush()

            val resp = reader.readLine()
            Log.i(TAG, "[+] Daemon INIT response: $resp")

            if (resp != null && resp.startsWith("OK_INIT")) {
                val parts = resp.split(" ")
                for (p in parts) {
                    if (p.startsWith("base=")) currentBaseAddress = p.substring(5)
                }
                isAttached.set(true)
                true
            } else {
                isAttached.set(false)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[-] Exception during daemon INIT: ${e.message}", e)
            NativeBridge.init(pid)
        }
    }

    /**
     * Toggles an offset feature in the target process memory.
     */
    @Synchronized
    fun setFeature(id: Int, on: Boolean): Boolean {
        if (isRunning.get() && daemonWriter != null && daemonReader != null) {
            return try {
                val cmd = "SET $id ${if (on) 1 else 0}\n"
                daemonWriter!!.write(cmd)
                daemonWriter!!.flush()

                val resp = daemonReader!!.readLine()
                Log.i(TAG, "[+] Daemon SET response: $resp")
                resp != null && resp.startsWith("OK_SET")
            } catch (e: Exception) {
                Log.e(TAG, "[-] Daemon SET failed: ${e.message}", e)
                NativeBridge.setFeature(id, on)
            }
        }

        // In-process fallback
        return NativeBridge.setFeature(id, on)
    }

    /**
     * Re-scans memory map for target library base address.
     */
    @Synchronized
    fun rescan(): String {
        if (isRunning.get() && daemonWriter != null && daemonReader != null) {
            return try {
                daemonWriter!!.write("RESCAN\n")
                daemonWriter!!.flush()
                val resp = daemonReader!!.readLine()
                if (resp != null && resp.startsWith("OK_RESCAN")) {
                    currentBaseAddress = resp.substringAfter("base=")
                }
                currentBaseAddress
            } catch (e: Exception) {
                "0x0"
            }
        }
        return "0x0"
    }

    /**
     * Checks whether the target library is loaded and accessible.
     */
    fun isSupported(): Boolean {
        if (isRunning.get() && isAttached.get()) {
            return currentBaseAddress.isNotEmpty() && currentBaseAddress != "0x0"
        }
        return NativeBridge.isSupported()
    }

    /**
     * Returns whether the root daemon is currently running with real UID 0.
     */
    fun isRootDaemonActive(): Boolean {
        return isRunning.get() && daemonUid == 0
    }

    /**
     * Stops the daemon, restores memory, and releases process resources.
     */
    @Synchronized
    fun stop() {
        if (isRunning.get()) {
            try {
                daemonWriter?.write("STOP\n")
                daemonWriter?.flush()
            } catch (_: Exception) {}

            try {
                daemonReader?.close()
                daemonWriter?.close()
            } catch (_: Exception) {}

            try {
                daemonProcess?.destroy()
            } catch (_: Exception) {}

            daemonProcess = null
            daemonWriter = null
            daemonReader = null
            isRunning.set(false)
            isAttached.set(false)
        }

        // Also stop in-process JNI
        NativeBridge.stop()
    }
}
