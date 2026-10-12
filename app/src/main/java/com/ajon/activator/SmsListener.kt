package com.ajon.activator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import java.util.Calendar

class SmsListener : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        for (pdu in pdus) {
            val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray, format)
            val messageBody = smsMessage.messageBody ?: continue
            val sender = smsMessage.originatingAddress ?: "Unknown"

            Log.d("AjonActivator", "SMS from $sender: $messageBody")

            val lowerBody = messageBody.lowercase()
            if (lowerBody.contains("money") || lowerBody.contains("mobilemoney") || lowerBody.contains("ugx")) {
                processPaymentMessage(context, sender, messageBody)
            }
        }
    }

    private fun processPaymentMessage(context: Context, sender: String, body: String) {
        val amountRegex = Regex("""UGX\s*([\d,]+)""", RegexOption.IGNORE_CASE)
        val amountMatch = amountRegex.find(body)
        val amount = amountMatch?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

        val validAmounts = listOf(2500, 5000, 10000, 15000)
        if (amount == null || amount !in validAmounts) {
            Log.d("AjonActivator", "Amount $amount not valid, ignoring")
            return
        }

        val fromRegex = Regex("""FROM\s*(\+?\d[\d\s-]{7,})""", RegexOption.IGNORE_CASE)
        val fromMatch = fromRegex.find(body)
        var payerPhone = fromMatch?.groupValues?.get(1)?.replace(Regex("[\\s-]"), "")

        var payerName = "Unknown"
        val nameRegex = Regex("""FROM\s*([A-Za-z\s]{3,30})""", RegexOption.IGNORE_CASE)
        val nameMatch = nameRegex.find(body)
        if (nameMatch != null && payerPhone == null) {
            payerName = nameMatch.groupValues.get(1).trim()
        }

        if (payerPhone == null) {
            payerPhone = sender
        }

        val now = Calendar.getInstance()
        val password = makePassword(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY)
        )

        // ADD TO QUEUE
        addToQueue(context, payerPhone, password, payerName, amount)
    }

    private fun addToQueue(context: Context, phone: String, password: String, payerName: String, amount: Int) {
        val prefs = context.getSharedPreferences("AjonQueue", Context.MODE_PRIVATE)
        val currentQueue = prefs.getString("queue", "") ?: ""
        val newEntry = "$phone|$password|$payerName|$amount\n"
        val updatedQueue = currentQueue + newEntry
        prefs.edit().putString("queue", updatedQueue).apply()
        Log.d("AjonActivator", "Added to queue: $phone")
    }

    private fun makePassword(year: Int, monthIndex0: Int, day: Int, hour: Int): String {
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
