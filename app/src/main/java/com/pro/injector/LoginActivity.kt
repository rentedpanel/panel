package com.pro.injector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etTargetPkg: EditText
    private lateinit var txtStatus: TextView
    private lateinit var btnLogin: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        etTargetPkg = findViewById(R.id.et_target_pkg)
        txtStatus = findViewById(R.id.txt_login_status)
        btnLogin = findViewById(R.id.btn_login)

        btnLogin.setOnClickListener {
            val u = etUsername.text.toString().trim()
            val p = etPassword.text.toString().trim()
            val pkg = etTargetPkg.text.toString().trim()

            if (checkLogin(u, p)) {
                txtStatus.text = "✓ Authenticated. Checking permissions..."

                ensurePermissions {
                    val pid = getTargetPid(pkg)
                    if (pid <= 0) {
                        Toast.makeText(
                            this,
                            "Notice: Process ($pkg) not detected. Launch game to attach.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    val intent = Intent(this, FloatingService::class.java).apply {
                        putExtra("pid", pid)
                        putExtra("package", pkg)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    finish()
                }
            } else {
                txtStatus.text = "✗ Access Denied. Default credentials: admin / root123"
            }
        }

        checkNotificationPermission()
    }

    private fun checkLogin(u: String, p: String): Boolean {
        return (u == "admin" && p == "root123")
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
    }

    private fun ensurePermissions(onDone: () -> Unit) {
        // Overlay permission check
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1)
            Toast.makeText(this, "Grant Overlay permission, then click Authenticate", Toast.LENGTH_LONG).show()
            return
        }

        // Root check (su -c id)
        Thread {
            val isRooted = try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                output.contains("uid=0")
            } catch (e: Exception) {
                false
            }

            runOnUiThread {
                if (!isRooted) {
                    txtStatus.text = "✗ Superuser privilege missing! Grant Root access in Magisk/KernelSU."
                    Toast.makeText(this, "Root access required for memory injection!", Toast.LENGTH_LONG).show()
                } else {
                    onDone()
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay enabled. Tap Authenticate to proceed.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission is mandatory for floating mod menu.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getTargetPid(pkg: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof $pkg"))
            proc.waitFor()
            val text = proc.inputStream.bufferedReader().readText().trim()
            if (text.isNotEmpty()) {
                text.split("\\s+".toRegex())[0].toIntOrNull() ?: 0
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
}
