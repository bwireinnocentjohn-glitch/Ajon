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
        private const val SEND_INTERVAL_MS = 2000L // 2 seconds between SMS
        private const val SIM_SWITCH_LIMIT = 60
    }

    private val handler = Handler(Looper.getMainLooper())
    private var burstCount = 0

    private val queueProcessor = object : Runnable {
        override fun run() {
            val processed = processNextInQueue()
            
            if (processed) {
                burstCount++
                if (burstCount >= BURST_SIZE) {
                    // Batch of 10 done. Rest for 30 seconds.
                    Log.d("AjonActivator", "Batch of $BURST_SIZE done. Resting for 30 seconds...")
                    burstCount = 0
                    handler.postDelayed(this, BURST_REST_MS)
                } else {
                    // Continue quickly within the batch
                    handler.postDelayed(this, SEND_INTERVAL_MS)
                }
            } else {
                // Queue is empty, check again in 5 seconds
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

    private fun processNextInQueue(): Boolean {
        val prefs = getSharedPreferences("AjonQueue", Context.MODE_PRIVATE)
        val queueText = prefs.getString("queue", "") ?: ""
        
        if (queueText.isBlank()) return false

        val lines = queueText.lines().filter { it.isNotBlank() }.toMutableList()
        if (lines.isEmpty()) return false

        // Get the first person in the queue
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
                sendSmsWithSmartSim(phone, password, payerName, amount)
            } else {
                saveToHistory("$payerName ($phone)", amount, password, "PENDING - MANUAL SEND")
            }
            return true
        }
        return false
    }

    private fun sendSmsWithSmartSim(phone: String, password: String, payerName: String, amount: Int) {
        val prefs = getSharedPreferences("AjonSettings", Context.MODE_PRIVATE)
        var smsSentOnCurrentSim = prefs.getInt("smsSentOnCurrentSim", 0)
        var currentSimIndex = prefs.getInt("currentSimIndex", 0)
        
        // Get available SIMs
        val activeSubs = try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            subManager.activeSubscriptionInfoList
        } catch (e: Exception) {
            null
        }

        var targetSubId = -1
        if (activeSubs != null && activeSubs.isNotEmpty()) {
            if (currentSimIndex >= activeSubs.size) {
                currentSimIndex = 0 // Wrap around if we exceed the list
            }
            targetSubId = activeSubs[currentSimIndex].subscriptionId
        }

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && targetSubId != -1) {
                SmsManager.getSmsManagerForSubscriptionId(targetSubId)
            } else {
                SmsManager.getDefault()
            }
            
            val reply = "Your Ajon password is $password enter it faster and don't share."
            smsManager.sendTextMessage(phone, null, reply, null, null)
            
            smsSentOnCurrentSim++
            Log.d("AjonActivator", "Sent via SIM $targetSubId. Count on this SIM: $smsSentOnCurrentSim")
            
            // Auto Switch SIM logic
            if (smsSentOnCurrentSim >= SIM_SWITCH_LIMIT && activeSubs != null && activeSubs.size > 1) {
                currentSimIndex++
                if (currentSimIndex >= activeSubs.size) currentSimIndex = 0
                smsSentOnCurrentSim = 0
                Log.d("AjonActivator", "SWITCHING SIM! Now using index $currentSimIndex")
            }
            
            // Save counters
            prefs.edit()
                .putInt("smsSentOnCurrentSim", smsSentOnCurrentSim)
                .putInt("currentSimIndex", currentSimIndex)
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
            .setContentText("AJON Active 24/7 - Queue & SIM Switcher Active")
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
