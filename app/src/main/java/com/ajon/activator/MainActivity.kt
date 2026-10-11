package com.ajon.activator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var btnCopyCurrent: Button
    private lateinit var llPasswordList: LinearLayout
    private lateinit var llTransactionLog: LinearLayout
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
        btnCopyCurrent = findViewById(R.id.btnCopyCurrent)
        llPasswordList = findViewById(R.id.llPasswordList)
        llTransactionLog = findViewById(R.id.llTransactionLog)

        btnCopyCurrent.setOnClickListener {
            val pass = tvCurrentPassword.text.toString()
            if (pass != "--------") {
                copyToClipboard(pass)
            }
        }

        btnStart.setOnClickListener {
            if (!isRunning) {
                requestAllPermissions()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Ajon Password", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied $text", Toast.LENGTH_SHORT).show()
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

        val serviceIntent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        askBatteryOptimization()
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
                // Ignore
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

            val currentPass = makePassword(year, month, day, hour)
            tvCurrentPassword.text = currentPass

            val pastCal = Calendar.getInstance()
            pastCal.add(Calendar.HOUR_OF_DAY, -1)
            val pastPass = makePassword(
                pastCal.get(Calendar.YEAR),
                pastCal.get(Calendar.MONTH),
                pastCal.get(Calendar.DAY_OF_MONTH),
                pastCal.get(Calendar.HOUR_OF_DAY)
            )
            tvPastPassword.text = "Previous: $pastPass"

            val minutesLeft = 59 - now.get(Calendar.MINUTE)
            val secondsLeft = 60 - now.get(Calendar.SECOND)
            tvCountdown.text = String.format(Locale.getDefault(), "%02d:%02d left this hour", minutesLeft, secondsLeft)

            populateSchedule(now)
            populateTransactionLog()

            handler.postDelayed(this, 1000)
        }
    }

    private fun populateSchedule(now: Calendar) {
        llPasswordList.removeAllViews()

        for (i in -2..5) {
            val cal = Calendar.getInstance()
            cal.time = now.time
            cal.add(Calendar.HOUR_OF_DAY, i)

            val pass = makePassword(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY)
            )
            val timeStr = String.format(Locale.getDefault(), "%02d:00", cal.get(Calendar.HOUR_OF_DAY))

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 24, 0, 24)
            row.gravity = Gravity.CENTER_VERTICAL

            val tvTime = TextView(this)
            tvTime.text = timeStr
            tvTime.setTextColor(Color.WHITE)
            tvTime.width = 120
            tvTime.textSize = 16f
            row.addView(tvTime)

            val tvPass = TextView(this)
            tvPass.text = pass
            tvPass.textSize = 18f
            tvPass.width = 400
            if (i < 0) {
                tvPass.setTextColor(Color.parseColor("#FF4444"))
            } else if (i == 0) {
                tvPass.setTextColor(Color.parseColor("#00FF00"))
            } else {
                tvPass.setTextColor(Color.parseColor("#00CCFF"))
            }
            row.addView(tvPass)

            val btnCopy = Button(this)
            btnCopy.text = "Copy"
            btnCopy.setOnClickListener {
                copyToClipboard(pass)
            }
            row.addView(btnCopy)

            llPasswordList.addView(row)
        }
    }

    private fun populateTransactionLog() {
        llTransactionLog.removeAllViews()
        val prefs = getSharedPreferences("AjonHistory", Context.MODE_PRIVATE)
        val historyText = prefs.getString("log", "") ?: ""

        if (historyText.isEmpty()) {
            val tvEmpty = TextView(this)
            tvEmpty.text = "No transactions yet."
            tvEmpty.setTextColor(Color.GRAY)
            tvEmpty.textSize = 14f
            tvEmpty.setPadding(0, 16, 0, 16)
            llTransactionLog.addView(tvEmpty)
            return
        }

        val entries = historyText.lines().filter { it.isNotBlank() }
        for (entry in entries) {
            val parts = entry.split("|")
            if (parts.size == 4) {
                val phone = parts[0]
                val amount = parts[1]
                val password = parts[2]
                val time = parts[3]

                val row = LinearLayout(this)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(0, 16, 0, 16)

                val tvTop = TextView(this)
                tvTop.text = "$phone - UGX $amount"
                tvTop.setTextColor(Color.WHITE)
                tvTop.textSize = 16f
                row.addView(tvTop)

                val tvBottom = TextView(this)
                tvBottom.text = "Sent: $password ($time)"
                tvBottom.setTextColor(Color.parseColor("#00CCFF"))
                tvBottom.textSize = 14f
                row.addView(tvBottom)

                llTransactionLog.addView(row)
            }
        }
    }

    private fun updatePasswordLoop() {
        handler.post(passwordUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(passwordUpdater)
    }

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
