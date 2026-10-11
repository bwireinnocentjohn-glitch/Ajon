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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
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
    private lateinit var tvTotalMoney: TextView
    private lateinit var etAdminPin: EditText
    private lateinit var btnUnlockAdmin: Button
    private lateinit var tvAdminStatus: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    
    private lateinit var llHomeContent: LinearLayout
    private lateinit var llScheduleContent: LinearLayout
    private lateinit var llLogsContent: LinearLayout
    private lateinit var llAdminContent: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isAdminUnlocked = false

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
        tvTotalMoney = findViewById(R.id.tvTotalMoney)
        etAdminPin = findViewById(R.id.etAdminPin)
        btnUnlockAdmin = findViewById(R.id.btnUnlockAdmin)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)
        toggleGroup = findViewById(R.id.toggleGroup)
        
        llHomeContent = findViewById(R.id.llHomeContent)
        llScheduleContent = findViewById(R.id.llScheduleContent)
        llLogsContent = findViewById(R.id.llLogsContent)
        llAdminContent = findViewById(R.id.llAdminContent)

        // RESTORE STATE FROM MEMORY
        val prefs = getSharedPreferences("AjonSettings", Context.MODE_PRIVATE)
        isAdminUnlocked = prefs.getBoolean("isAdmin", false)
        isRunning = prefs.getBoolean("isRunning", false)
        
        updateAdminUI()
        updateStartButtonUI()

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTabHome -> showTab(0)
                    R.id.btnTabSchedule -> showTab(1)
                    R.id.btnTabLogs -> showTab(2)
                    R.id.btnTabAdmin -> showTab(3)
                }
            }
        }
        toggleGroup.check(R.id.btnTabHome)

        btnCopyCurrent.setOnClickListener {
            if (!isAdminUnlocked) {
                Toast.makeText(this, "Please unlock Admin first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pass = tvCurrentPassword.text.toString()
            if (pass != "LOCKED" && pass != "--------") {
                copyToClipboard(pass)
            }
        }

        btnUnlockAdmin.setOnClickListener {
            val pin = etAdminPin.text.toString()
            if (pin == "26bijon") {
                isAdminUnlocked = true
                prefs.edit().putBoolean("isAdmin", true).apply()
                updateAdminUI()
                Toast.makeText(this, "Admin Unlocked!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }

        btnStart.setOnClickListener {
            if (!isRunning) {
                requestAllPermissions()
            }
        }
    }

    private fun updateStartButtonUI() {
        if (isRunning) {
            btnStart.text = "Ajon Activator is Running 24/7"
            btnStart.isEnabled = false
        } else {
            btnStart.text = "START 24/7 ACTIVATOR"
            btnStart.isEnabled = true
        }
    }

    private fun updateAdminUI() {
        if (isAdminUnlocked) {
            tvCurrentPassword.text = "--------"
            tvCurrentPassword.setTextColor(Color.parseColor("#00FF00"))
            tvPastPassword.visibility = View.VISIBLE
            btnCopyCurrent.visibility = View.VISIBLE
            tvAdminStatus.text = "Status: UNLOCKED"
            tvAdminStatus.setTextColor(Color.parseColor("#00FF00"))
            etAdminPin.visibility = View.GONE
            btnUnlockAdmin.visibility = View.GONE
            if (isRunning) updatePasswordLoop()
        } else {
            tvCurrentPassword.text = "LOCKED"
            tvCurrentPassword.setTextColor(Color.parseColor("#FF4444"))
            tvPastPassword.visibility = View.GONE
            btnCopyCurrent.visibility = View.GONE
            tvAdminStatus.text = "Status: Locked"
            tvAdminStatus.setTextColor(Color.parseColor("#FF4444"))
            etAdminPin.visibility = View.VISIBLE
            btnUnlockAdmin.visibility = View.VISIBLE
            handler.removeCallbacks(passwordUpdater)
        }
    }

    private fun showTab(index: Int) {
        llHomeContent.visibility = if (index == 0) View.VISIBLE else View.GONE
        llScheduleContent.visibility = if (index == 1) View.VISIBLE else View.GONE
        llLogsContent.visibility = if (index == 2) View.VISIBLE else View.GONE
        llAdminContent.visibility = if (index == 3) View.VISIBLE else View.GONE
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
        val prefs = getSharedPreferences("AjonSettings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("isRunning", true).apply()
        
        updateStartButtonUI()

        val serviceIntent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        askBatteryOptimization()
        if (isAdminUnlocked) updatePasswordLoop()
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
            if (!isAdminUnlocked) return

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

    private fun format12Hour(cal: Calendar): String {
        val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val hour12 = cal.get(Calendar.HOUR)
        val displayHour = if (hour12 == 0) 12 else hour12
        return String.format(Locale.getDefault(), "%02d:00 %s", displayHour, amPm)
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
            val timeStr = format12Hour(cal)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 24, 0, 24)
            row.gravity = Gravity.CENTER_VERTICAL

            val tvTime = TextView(this)
            tvTime.text = timeStr
            tvTime.setTextColor(Color.WHITE)
            tvTime.width = 200
            tvTime.textSize = 16f
            row.addView(tvTime)

            val tvPass = TextView(this)
            tvPass.text = pass
            tvPass.textSize = 18f
            tvPass.width = 500
            if (i < 0) {
                tvPass.setTextColor(Color.parseColor("#FF4444"))
            } else if (i == 0) {
                tvPass.setTextColor(Color.parseColor("#00FF00"))
            } else {
                tvPass.setTextColor(Color.parseColor("#00CCFF"))
            }
            row.addView(tvPass)

            val btnCopy = Button(this)
            btnCopy.text = "COPY"
            btnCopy.setOnClickListener { copyToClipboard(pass) }
            row.addView(btnCopy)

            llPasswordList.addView(row)
        }
    }

    private fun populateTransactionLog() {
        llTransactionLog.removeAllViews()
        val prefs = getSharedPreferences("AjonHistory", Context.MODE_PRIVATE)
        val historyText = prefs.getString("log", "") ?: ""

        if (historyText.isEmpty()) {
            tvTotalMoney.text = "Total Received: UGX 0"
            val tvEmpty = TextView(this)
            tvEmpty.text = "No transactions yet."
            tvEmpty.setTextColor(Color.GRAY)
            tvEmpty.textSize = 14f
            tvEmpty.setPadding(0, 16, 0, 16)
            llTransactionLog.addView(tvEmpty)
            return
        }

        val entries = historyText.lines().filter { it.isNotBlank() }
        var total = 0
        
        for (entry in entries) {
            val parts = entry.split("|")
            if (parts.size == 5) {
                val displayName = parts[0]
                val amount = parts[1].toIntOrNull() ?: 0
                val password = parts[2]
                val time = parts[3]
                val status = parts[4]
                total += amount

                val row = LinearLayout(this)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(0, 16, 0, 16)

                val tvTop = TextView(this)
                tvTop.text = "$displayName - UGX $amount"
                tvTop.setTextColor(Color.WHITE)
                tvTop.textSize = 16f
                row.addView(tvTop)

                val tvBottom = TextView(this)
                tvBottom.text = "Pass: $password | $time\nStatus: $status"
                if (status.contains("PENDING")) {
                    tvBottom.setTextColor(Color.parseColor("#FFAA00"))
                } else {
                    tvBottom.setTextColor(Color.parseColor("#00CCFF"))
                }
                tvBottom.textSize = 14f
                row.addView(tvBottom)

                llTransactionLog.addView(row)
            }
        }
        tvTotalMoney.text = "Total Received: UGX $total"
    }

    private fun updatePasswordLoop() {
        handler.removeCallbacks(passwordUpdater)
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
