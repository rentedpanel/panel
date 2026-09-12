package com.pro.injector

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigationrail.NavigationRailView
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var wm: WindowManager

    // Official Google Material 3 Views
    private var windowView: View? = null
    private var pillView: View? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var pillParams: WindowManager.LayoutParams

    private var isMaximized = false
    private var isMinimized = false

    private var targetPid: Int = 0
    private var targetPackage: String = "com.target.game"
    private var isEngineReady: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var normalWidth = 0
    private var normalHeight = 0

    // Feature state tracking
    private val switchStates = BooleanArray(8) { false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupDimensions()
        setupForegroundNotification()
    }

    private fun setupDimensions() {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels

        normalWidth = (screenWidth * 0.90f).toInt().coerceAtMost((560 * density).toInt()).coerceAtLeast((360 * density).toInt())
        normalHeight = (420 * density).toInt()
    }

    private fun setupForegroundNotification() {
        val channelId = "pro_injector_channel"
        val channelName = "ProInjector Overlay Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ProInjector active mod-menu overlay service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚡ SMMTor Engine Active")
            .setContentText("Google Material 3 floating suite is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            targetPid = intent.getIntExtra("pid", 0)
            targetPackage = intent.getStringExtra("package") ?: "com.target.game"
        }

        initNativeEngine()

        if (windowView == null) {
            setupWindowView()
            setupPillView()
        } else {
            updateStatusUI()
        }

        return START_STICKY
    }

    private fun initNativeEngine() {
        if (targetPid > 0) {
            // First attempt: Standalone Root Daemon (UID 0 execution via su)
            val daemonOk = RootDaemonBridge.init(applicationContext, targetPid)
            if (daemonOk && RootDaemonBridge.isRootDaemonActive()) {
                isEngineReady = true
                Toast.makeText(this, "✓ Root Daemon active (UID 0) • PID: $targetPid", Toast.LENGTH_SHORT).show()
                return
            }

            // Fallback: In-process JNI NativeBridge
            isEngineReady = NativeBridge.init(targetPid)
            if (isEngineReady) {
                if (NativeBridge.isSupported()) {
                    Toast.makeText(this, "✓ Memory engine attached (PID: $targetPid)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Target library not yet loaded in memory. Keep game running.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Engine initialization failed. Verify root permissions.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupWindowView() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            normalWidth,
            normalHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
            windowAnimations = 0 // Zero animations for instant, snappy response
        }

        val inflater = LayoutInflater.from(this)
        val v = inflater.inflate(R.layout.layout_floating_window, null)

        // Titlebar Dragging
        val titlebar = v.findViewById<View>(R.id.window_titlebar)
        titlebar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        windowParams.x = initialX + (event.rawX - touchStartX).toInt()
                        windowParams.y = initialY + (event.rawY - touchStartY).toInt()
                        wm.updateViewLayout(windowView, windowParams)
                        return true
                    }
                }
                return false
            }
        })

        // Window Control Buttons (MaterialButtons)
        v.findViewById<MaterialButton>(R.id.btn_window_minimize).setOnClickListener {
            minimizeToPill()
        }

        v.findViewById<MaterialButton>(R.id.btn_window_maximize).setOnClickListener {
            toggleMaximizeWindow()
        }

        v.findViewById<MaterialButton>(R.id.btn_window_close).setOnClickListener {
            stopSelf()
        }

        // Tab Navigation Containers
        val tabDashboard = v.findViewById<View>(R.id.tab_container_dashboard)
        val tabCombat = v.findViewById<View>(R.id.tab_container_combat)
        val tabVisuals = v.findViewById<View>(R.id.tab_container_visuals)
        val tabScripts = v.findViewById<View>(R.id.tab_container_scripts)
        val tabProcess = v.findViewById<View>(R.id.tab_container_process)
        val tabSettings = v.findViewById<View>(R.id.tab_container_settings)

        fun selectTab(target: View?) {
            val allTabs = listOf(tabDashboard, tabCombat, tabVisuals, tabScripts, tabProcess, tabSettings)
            allTabs.forEach { tab ->
                tab?.visibility = if (tab == target) View.VISIBLE else View.GONE
            }
        }

        // Google NavigationRailView Tab Selection
        val navRail = v.findViewById<NavigationRailView>(R.id.nav_rail)
        navRail.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> selectTab(tabDashboard)
                R.id.nav_combat -> selectTab(tabCombat)
                R.id.nav_visuals -> selectTab(tabVisuals)
                R.id.nav_scripts -> selectTab(tabScripts)
                R.id.nav_process -> {
                    selectTab(tabProcess)
                    performPidRescan()
                }
                R.id.nav_settings -> selectTab(tabSettings)
            }
            true
        }

        // Live JNI Feature Switches (Indexes 0..7)
        val switches = arrayOf(
            v.findViewById<MaterialSwitch>(R.id.sw_god_mode),       // Feature 0: God Mode
            v.findViewById<MaterialSwitch>(R.id.sw_infinite_ammo),   // Feature 1: Infinite Ammo
            v.findViewById<MaterialSwitch>(R.id.sw_no_recoil),       // Feature 2: No Recoil
            v.findViewById<MaterialSwitch>(R.id.sw_speed_boost),     // Feature 3: Speed Boost
            v.findViewById<MaterialSwitch>(R.id.sw_teleport),        // Feature 4: Teleport
            v.findViewById<MaterialSwitch>(R.id.sw_anti_aim),        // Feature 5: Anti-Aim
            v.findViewById<MaterialSwitch>(R.id.sw_radar),           // Feature 6: Radar ESP
            v.findViewById<MaterialSwitch>(R.id.sw_magic_bullet)     // Feature 7: Magic Bullet
        )

        val featureNames = arrayOf(
            "God Mode", "Infinite Ammo", "No Recoil", "Speed Boost",
            "Teleport", "Anti-Aim", "Radar ESP", "Magic Bullet"
        )

        val swMasterEsp = v.findViewById<MaterialSwitch>(R.id.sw_master_esp)
        val swRadar = v.findViewById<MaterialSwitch>(R.id.sw_radar)

        swMasterEsp?.isChecked = switchStates[6]
        swMasterEsp?.setOnCheckedChangeListener { _, isChecked ->
            if (swRadar?.isChecked != isChecked) {
                swRadar?.isChecked = isChecked
            }
        }

        switches.forEachIndexed { index, sw ->
            sw?.isChecked = switchStates[index]
            sw?.setOnCheckedChangeListener { _, isChecked ->
                switchStates[index] = isChecked
                val ok = if (RootDaemonBridge.isRootDaemonActive()) {
                    RootDaemonBridge.setFeature(index, isChecked)
                } else {
                    NativeBridge.setFeature(index, isChecked)
                }
                val status = if (isChecked) "ENABLED" else "DISABLED"

                // Radar ESP (Feature 6): Start/Stop Visual Canvas Animation Overlay
                if (index == 6) {
                    if (swMasterEsp?.isChecked != isChecked) {
                        swMasterEsp?.isChecked = isChecked
                    }
                    val overlayIntent = Intent(this, EspOverlayService::class.java)
                    if (isChecked) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(overlayIntent)
                        } else {
                            startService(overlayIntent)
                        }
                    } else {
                        stopService(overlayIntent)
                    }
                }

                updateStatusUI()

                if (ok) {
                    Toast.makeText(this, "C++ Live: ${featureNames[index]} $status", Toast.LENGTH_SHORT).show()
                } else if (isChecked) {
                    Toast.makeText(this, "Notice: ${featureNames[index]} $status", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Visuals Tab: ESP Customization Switches

        v.findViewById<MaterialSwitch>(R.id.sw_esp_box)?.setOnCheckedChangeListener { _, isChecked ->
            EspConfig.isBoxEnabled = isChecked
        }
        v.findViewById<MaterialSwitch>(R.id.sw_esp_ring)?.setOnCheckedChangeListener { _, isChecked ->
            EspConfig.isRingAnimEnabled = isChecked
        }
        v.findViewById<MaterialSwitch>(R.id.sw_esp_skel)?.setOnCheckedChangeListener { _, isChecked ->
            EspConfig.isSkelEnabled = isChecked
        }
        v.findViewById<MaterialSwitch>(R.id.sw_esp_hp)?.setOnCheckedChangeListener { _, isChecked ->
            EspConfig.isHpEnabled = isChecked
        }
        v.findViewById<MaterialSwitch>(R.id.sw_esp_tracer)?.setOnCheckedChangeListener { _, isChecked ->
            EspConfig.isTracerEnabled = isChecked
        }
        v.findViewById<MaterialSwitch>(R.id.sw_esp_dist)?.setOnCheckedChangeListener { _, isChecked ->
            EspConfig.isDistanceEnabled = isChecked
        }


        // Settings Tab: Transparency (Alpha) Chips
        v.findViewById<Chip>(R.id.chip_opacity_50)?.setOnClickListener {
            windowParams.alpha = 0.50f
            wm.updateViewLayout(windowView, windowParams)
        }
        v.findViewById<Chip>(R.id.chip_opacity_80)?.setOnClickListener {
            windowParams.alpha = 0.80f
            wm.updateViewLayout(windowView, windowParams)
        }
        v.findViewById<Chip>(R.id.chip_opacity_100)?.setOnClickListener {
            windowParams.alpha = 1.00f
            wm.updateViewLayout(windowView, windowParams)
        }

        // Re-Scan PID Button
        v.findViewById<MaterialButton>(R.id.btn_rescan_pid)?.setOnClickListener {
            performPidRescan()
        }

        wm.addView(v, windowParams)
        windowView = v
        updateStatusUI()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupPillView() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 200
            windowAnimations = 0 // Zero animations
        }

        val inflater = LayoutInflater.from(this)
        val pill = inflater.inflate(R.layout.layout_minimized_pill, null)

        // Google ExtendedFloatingActionButton Touch Dragging
        val fab = pill.findViewById<ExtendedFloatingActionButton>(R.id.dock_pill_fab)
        fab.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = pillParams.x
                        initialY = pillParams.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        pillParams.x = initialX + (event.rawX - touchStartX).toInt()
                        pillParams.y = initialY + (event.rawY - touchStartY).toInt()
                        wm.updateViewLayout(pillView, pillParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - touchStartX)
                        val diffY = abs(event.rawY - touchStartY)
                        if (diffX < 15 && diffY < 15) {
                            expandFromPill()
                        }
                        return true
                    }
                }
                return false
            }
        })

        pillView = pill
    }

    private fun minimizeToPill() {
        if (isMinimized) return
        isMinimized = true

        windowView?.let { wm.removeView(it) }
        pillView?.let { wm.addView(it, pillParams) }
    }

    private fun expandFromPill() {
        if (!isMinimized) return
        isMinimized = false

        pillView?.let { wm.removeView(it) }
        windowView?.let { wm.addView(it, windowParams) }
        updateStatusUI()
    }

    private fun toggleMaximizeWindow() {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        if (!isMaximized) {
            isMaximized = true
            windowParams.width = (screenWidth * 0.96f).toInt()
            windowParams.height = (screenHeight * 0.85f).toInt()
        } else {
            isMaximized = false
            windowParams.width = normalWidth
            windowParams.height = normalHeight
        }
        windowView?.let { wm.updateViewLayout(it, windowParams) }
    }

    private fun updateStatusUI() {
        windowView?.let { v ->
            val txtTitle = v.findViewById<TextView>(R.id.txt_status_title)
            val txtSub = v.findViewById<TextView>(R.id.txt_status_sub)
            val chipStatus = v.findViewById<Chip>(R.id.chip_engine_status)

            val txtDashPid = v.findViewById<TextView>(R.id.txt_dash_pid)
            val txtDashMemory = v.findViewById<TextView>(R.id.txt_dash_memory)
            val txtDashHooks = v.findViewById<TextView>(R.id.txt_dash_hooks)
            val txtDiagMempath = v.findViewById<TextView>(R.id.txt_diag_mempath)

            val activeCount = switchStates.count { it }
            txtDashHooks?.text = "$activeCount / 8"

            val isDaemon = RootDaemonBridge.isRootDaemonActive()
            val libReady = if (isDaemon) RootDaemonBridge.isSupported() else (targetPid > 0 && NativeBridge.isSupported())
            val baseAddr = if (isDaemon) RootDaemonBridge.currentBaseAddress else "Auto-Mapped"

            if (targetPid > 0) {
                txtTitle?.text = if (isDaemon) {
                    "Memory Engine: Root Daemon (UID 0 Active)"
                } else if (libReady) {
                    "Memory Engine: In-Process JNI Hooked"
                } else {
                    "Memory Engine: Attached (Waiting lib)"
                }
                txtSub?.text = "Target: $targetPackage • PID: $targetPid"
                chipStatus?.text = if (isDaemon) "ROOT (UID 0)" else if (libReady) "HOOKED (PID: $targetPid)" else "PID: $targetPid"

                txtDashPid?.text = "$targetPid"
                txtDashMemory?.text = if (isDaemon) "ROOT UID 0" else if (libReady) "HOOKED" else "ATTACHED"
                txtDiagMempath?.text = if (isDaemon) {
                    "/proc/$targetPid/mem (Root Daemon UID 0 • Permitted)"
                } else {
                    "/proc/$targetPid/mem (In-Process pread/pwrite)"
                }

                val txtDiagLib = v.findViewById<TextView>(R.id.txt_diag_lib)
                txtDiagLib?.text = "libil2cpp.so (Base: $baseAddr)"
            } else {
                txtTitle?.text = "Memory Engine: Disconnected"
                txtSub?.text = "Target: $targetPackage • PID: Not Detected"
                chipStatus?.text = "DISCONNECTED"

                txtDashPid?.text = "DETACHED"
                txtDashMemory?.text = "READY"
                txtDiagMempath?.text = "/proc/[pid]/mem (pread/pwrite)"
            }
        }
    }

    private fun performPidRescan() {
        Thread {
            val newPid = try {
                val cmd = "pidof $targetPackage || pgrep -f $targetPackage || ps -A | grep '$targetPackage' | awk '{print \$2}'"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                p.waitFor()
                val text = p.inputStream.bufferedReader().readText().trim()
                if (text.isNotEmpty()) text.split("\\s+".toRegex())[0].toIntOrNull() ?: 0 else 0
            } catch (e: Exception) {
                0
            }

            targetPid = newPid
            initNativeEngine()

            mainHandler.post {
                updateStatusUI()
                if (targetPid > 0) {
                    Toast.makeText(applicationContext, "Target PID attached: $targetPid", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, "Process ($targetPackage) not active!", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        // Stop ESP visual overlay service if running
        try {
            stopService(Intent(this, EspOverlayService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Restore memory and stop persistence loop
        for (i in 0 until 8) {
            if (RootDaemonBridge.isRootDaemonActive()) {
                RootDaemonBridge.setFeature(i, false)
            } else {
                NativeBridge.setFeature(i, false)
            }
        }
        RootDaemonBridge.stop()
        NativeBridge.stop()

        if (isMinimized) {
            pillView?.let { wm.removeView(it) }
        } else {
            windowView?.let { wm.removeView(it) }
        }

        windowView = null
        pillView = null
        super.onDestroy()
    }
}
