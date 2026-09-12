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

        // Google NavigationRailView
        val navRail = v.findViewById<NavigationRailView>(R.id.nav_rail)
        navRail.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    Toast.makeText(this, "Dashboard: All Live C++ Features", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_combat -> {
                    Toast.makeText(this, "Combat: God Mode, Ammo, No Recoil", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_visuals -> {
                    Toast.makeText(this, "Visuals: Radar ESP, Hitbox", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_process -> {
                    performPidRescan()
                }
                R.id.nav_scripts -> {
                    Toast.makeText(this, "Native C++ /proc/mem direct patch", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_settings -> {
                    Toast.makeText(this, "Google Material 3 Theme", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        // Official Google MaterialSwitch Configuration - Connected Live to NativeBridge (C++)
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

        switches.forEachIndexed { index, sw ->
            sw?.isChecked = switchStates[index]
            sw?.setOnCheckedChangeListener { _, isChecked ->
                switchStates[index] = isChecked
                val ok = NativeBridge.setFeature(index, isChecked)
                val status = if (isChecked) "ENABLED" else "DISABLED"

                // Radar ESP (Feature 6): Start/Stop Visual Canvas Animation Overlay
                if (index == 6) {
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

                if (ok) {
                    Toast.makeText(this, "C++ Live: ${featureNames[index]} $status", Toast.LENGTH_SHORT).show()
                } else if (isChecked) {
                    Toast.makeText(this, "Notice: ${featureNames[index]} $status (Overlay Active)", Toast.LENGTH_SHORT).show()
                }
            }
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

            if (targetPid > 0) {
                val libReady = NativeBridge.isSupported()
                txtTitle?.text = if (libReady) "Memory Engine: Active & Hooked" else "Memory Engine: Attached (Waiting lib)"
                txtSub?.text = "Target: $targetPackage • PID: $targetPid • /proc/$targetPid/mem"
                chipStatus?.text = if (libReady) "HOOKED (PID: $targetPid)" else "PID: $targetPid"
            } else {
                txtTitle?.text = "Memory Engine: Disconnected"
                txtSub?.text = "Target: $targetPackage • PID: Not Detected"
                chipStatus?.text = "DISCONNECTED"
            }
        }
    }

    private fun performPidRescan() {
        Thread {
            val newPid = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof $targetPackage"))
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
            NativeBridge.setFeature(i, false)
        }
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
