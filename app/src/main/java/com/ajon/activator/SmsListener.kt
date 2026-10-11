package com.ajon.activator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
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
            val sender = smsMessage.originatingAddress ?: continue

            Log.d("AjonActivator", "SMS from $sender: $messageBody")

            // Check if this looks like a mobile money message
            val lowerBody = messageBody.lowercase()
            if (lowerBody.contains("money") || lowerBody.contains("mobilemoney") || lowerBody.contains("ugx")) {
                processPaymentMessage(context, sender, messageBody)
            }
        }
    }

    private fun processPaymentMessage(context: Context, sender: String, body: String) {
        // Find amount - look for numbers after UGX
        val amountRegex = Regex("""UGX\s*([\d,]+)""", RegexOption.IGNORE_CASE)
        val amountMatch = amountRegex.find(body)
        val amount = amountMatch?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

        // Check if amount is one of the valid amounts
        val validAmounts = listOf(2500, 5000, 10000, 15000)
        if (amount == null || amount !in validAmounts) {
            Log.d("AjonActivator", "Amount $amount not valid, ignoring")
            return
        }

        // Find phone number after "FROM"
        val fromRegex = Regex("""FROM\s*(\+?\d[\d\s-]{7,})""", RegexOption.IGNORE_CASE)
        val fromMatch = fromRegex.find(body)
        val payerPhone = fromMatch?.groupValues?.get(1)?.replace(Regex("[\\s-]"), "")

        if (payerPhone == null) {
            Log.d("AjonActivator", "No payer phone found")
            return
        }

        // Generate current password
        val now = Calendar.getInstance()
        val password = makePassword(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY)
        )

        // Send SMS back
        try {
            val smsManager = SmsManager.getDefault()
            val reply = "Your Ajon password is $password enter it faster and don't share."
            smsManager.sendTextMessage(payerPhone, null, reply, null, null)
            Log.d("AjonActivator", "Sent password $password to $payerPhone")
        } catch (e: Exception) {
            Log.e("AjonActivator", "Failed to send SMS: ${e.message}")
        }
    }

    // Same formula as MainActivity
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
