package com.ajon.activator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvCurrentPassword: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvPastPassword: TextView
    private lateinit var btnStart: Button
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val permissionCode = 100
    private val requiredPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentPassword = findViewById(R.id.tvCurrentPassword)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvPastPassword = findViewById(R.id.tvPastPassword)
        btnStart = findViewById(R.id.btnStart)

        btnStart.setOnClickListener {
            if (!isRunning) {
                requestAllPermissions()
            }
        }
    }

    private fun requestAllPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionCode)
        } else {
            startActivator()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startActivator()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Permissions Needed")
                    .setMessage("Ajon Activator needs SMS and notification permissions to work. Please allow them.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun startActivator() {
        isRunning = true
        btnStart.text = "Ajon Activator is Running"

        // Start foreground service
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Ask to ignore battery optimization
        askBatteryOptimization()

        // Start updating password display
        updatePasswordLoop()
    }

    private fun askBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Some phones don't support this, just ignore
            }
        }
    }

    private val passwordUpdater = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val year = now.get(Calendar.YEAR)
            val month = now.get(Calendar.MONTH)
            val day = now.get(Calendar.DAY_OF_MONTH)
            val hour = now.get(Calendar.HOUR_OF_DAY)

            // Current password
            val currentPass = makePassword(year, month, day, hour)
            tvCurrentPassword.text = currentPass

            // Past hour password
            val pastHour = if (hour == 0) 23 else hour - 1
            val pastDay = if (hour == 0) {
                if (day == 1) {
                    // Previous month
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                    cal.get(Calendar.DAY_OF_MONTH)
                } else day - 1
            } else day
            val pastMonth = if (hour == 0 && day == 1) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, -1)
                cal.get(Calendar.MONTH)
            } else month
            val pastPass = makePassword(year, pastMonth, pastDay, pastHour)
            tvPastPassword.text = "Previous: $pastPass"

            // Countdown
            val minutesLeft = 59 - now.get(Calendar.MINUTE)
            val secondsLeft = 60 - now.get(Calendar.SECOND)
            tvCountdown.text = String.format(Locale.getDefault(), "%02d:%02d left this hour", minutesLeft, secondsLeft)

            handler.postDelayed(this, 1000)
        }
    }

    private fun updatePasswordLoop() {
        handler.post(passwordUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(passwordUpdater)
    }

    /**
     * AJON PASSWORD FORMULA - EXACTLY AS PROVIDED
     */
    fun makePassword(year: Int, monthIndex0: Int, day: Int, hour: Int): String {
        val monthIndex = (year - 2026) * 12 + monthIndex0
        val dayIdx = monthIndex * 31 + day
        val hourIdx = dayIdx * 24 + hour
        val num = 2000 + hourIdx * 13 + 77 * 13
        val l1 = ('a' + ((year - 2026) % 26))
        val l2 = ('a' + (monthIndex0 % 26))
        val l3 = ('a' + (day % 26))
        val l4 = ('a' + hour)
        val last = (hourIdx * 7 + year % 10) % 9 + 1
        return "aj$num$l1$l2$l3$l4$last".lowercase()
    }
}
