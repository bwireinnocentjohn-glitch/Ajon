package com.ajon.activator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "ajon_activator_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BURST_SIZE = 10
        private const val BURST_REST_MS = 30000L // 30 seconds rest
        private const val SEND_INTERVAL_MS = 5000L // 5 seconds between SMS
        private const val SIM_SWITCH_LIMIT = 60
        private const val DAILY_LIMIT = 150
    }

    private val handler = Handler(Looper.getMainLooper())
    private var burstCount = 0

    private val queueProcessor = object : Runnable {
        override fun run() {
            val processed = processNextInQueue()
            
            if (processed) {
                burstCount++
                if (burstCount >= BURST_SIZE) {
                    Log.d("AjonActivator", "Batch of $BURST_SIZE done. Resting for 30 seconds...")
                    burstCount = 0
                    handler.postDelayed(this, BURST_REST_MS)
                } else {
                    handler.postDelayed(this, SEND_INTERVAL_MS)
                }
            } else {
                burstCount = 0
                handler.postDelayed(this, 5000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        handler.removeCallbacks(queueProcessor)
        handler.post(queueProcessor)
        
        return START_STICKY
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun checkAndResetDailyLimit(prefs: android.content.SharedPreferences): Boolean {
        val today = getTodayDate()
        val lastDate = prefs.getString("lastSmsDate", "")
        
        if (today != lastDate) {
            prefs.edit()
                .putString("lastSmsDate", today)
                .putInt("dailySmsCount", 0)
                .apply()
            Log.d("AjonActivator", "New day! Daily SMS count reset to 0.")
        }
        
        val count = prefs.getInt("dailySmsCount", 0)
        return count < DAILY_LIMIT
    }

    private fun processNextInQueue(): Boolean {
        val settingsPrefs = getSharedPreferences("AjonSettings", Context.MODE_PRIVATE)
        
        // 1. Check daily limit (150 messages per day)
        if (!checkAndResetDailyLimit(settingsPrefs)) {
            Log.d("AjonActivator", "Daily limit of $DAILY_LIMIT reached. Waiting until tomorrow.")
            return false
        }

        val prefs = getSharedPreferences("AjonQueue", Context.MODE_PRIVATE)
        val queueText = prefs.getString("queue", "") ?: ""
        
        if (queueText.isBlank()) return false

        val lines = queueText.lines().filter { it.isNotBlank() }.toMutableList()
        if (lines.isEmpty()) return false

        val firstEntry = lines.removeAt(0)
        prefs.edit().putString("queue", lines.joinToString("\n")).apply()

        val parts = firstEntry.split("|")
        if (parts.size == 4) {
            val phone = parts[0]
            val password = parts[1]
            val payerName = parts[2]
            val amount = parts[3].toIntOrNull() ?: 0

            val isRealPhoneNumber = phone.matches(Regex("""^(\+?256|0)\d{9}$""")) || phone.matches(Regex("""^\+?\d{10,15}$"""))

            if (isRealPhoneNumber) {
                sendSmsWithSmartSim(phone, password, payerName, amount, settingsPrefs)
            } else {
                saveToHistory("$payerName ($phone)", amount, password, "PENDING - MANUAL SEND")
            }
            return true
        }
        return false
    }

    private fun sendSmsWithSmartSim(phone: String, password: String, payerName: String, amount: Int, settingsPrefs: android.content.SharedPreferences) {
        var smsSentOnCurrentSim = settingsPrefs.getInt("smsSentOnCurrentSim", 0)
        var currentSimIndex = settingsPrefs.getInt("currentSimIndex", 0)
        var dailyCount = settingsPrefs.getInt("dailySmsCount", 0)
        
        val activeSubs = try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            subManager.activeSubscriptionInfoList
        } catch (e: Exception) {
            null
        }

        var targetSubId = -1
        if (activeSubs != null && activeSubs.isNotEmpty()) {
            if (currentSimIndex >= activeSubs.size) {
                currentSimIndex = 0
            }
            targetSubId = activeSubs[currentSimIndex].subscriptionId
        }

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && targetSubId != -1) {
                SmsManager.getSmsManagerForSubscriptionId(targetSubId)
            } else {
                SmsManager.getDefault()
            }
            
            // PERSONALIZED MESSAGE: Includes the user's phone number so it's unique per contact!
            val reply = "User $phone this is your Ajon password: $password enter it fast & don't share."
            smsManager.sendTextMessage(phone, null, reply, null, null)
            
            smsSentOnCurrentSim++
            dailyCount++
            Log.d("AjonActivator", "Sent via SIM $targetSubId. Daily count: $dailyCount / $DAILY_LIMIT")
            
            if (smsSentOnCurrentSim >= SIM_SWITCH_LIMIT && activeSubs != null && activeSubs.size > 1) {
                currentSimIndex++
                if (currentSimIndex >= activeSubs.size) currentSimIndex = 0
                smsSentOnCurrentSim = 0
                Log.d("AjonActivator", "SWITCHING SIM! Now using index $currentSimIndex")
            }
            
            settingsPrefs.edit()
                .putInt("smsSentOnCurrentSim", smsSentOnCurrentSim)
                .putInt("currentSimIndex", currentSimIndex)
                .putInt("dailySmsCount", dailyCount)
                .apply()
                
            saveToHistory(phone, amount, password, "SENT")
        } catch (e: Exception) {
            Log.e("AjonActivator", "Failed to send SMS: ${e.message}")
            saveToHistory(phone, amount, password, "FAILED")
        }
    }

    private fun saveToHistory(displayName: String, amount: Int, password: String, status: String) {
        val prefs = getSharedPreferences("AjonHistory", Context.MODE_PRIVATE)
        val currentHistory = prefs.getString("log", "") ?: ""
        
        val timeStamp = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
        val newEntry = "$displayName|$amount|$password|$timeStamp|$status\n"
        
        val lines = (newEntry + currentHistory).lines().take(100).joinToString("\n")
        prefs.edit().putString("log", lines).apply()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ajon Activator")
            .setContentText("AJON Active 24/7 - Safe Queue Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ajon Activator Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Ajon Activator running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(queueProcessor)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
