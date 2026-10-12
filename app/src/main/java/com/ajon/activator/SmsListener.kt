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
            if (lowerBody.contains("ugx") || lowerBody.contains("money") || lowerBody.contains("mobilemoney")) {
                processPaymentMessage(context, sender, messageBody)
            }
        }
    }

    private fun processPaymentMessage(context: Context, sender: String, body: String) {
        // STRICT REGEX: Only accepts amounts if they are right next to "UGX"
        val amount = extractStrictAmount(body)

        if (amount == null) {
            Log.d("AjonActivator", "No valid UGX amount found. Ignoring.")
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
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)

        val isTwoDay = amount == 2500
        val password = if (isTwoDay) {
            makePassword2Days(year, month, day, hour)
        } else {
            makePasswordHourly(year, month, day, hour)
        }

        addToQueue(context, payerPhone, password, payerName, amount, isTwoDay)
    }

    private fun extractStrictAmount(body: String): Int? {
        val validAmounts = listOf(2500, 5000, 10000, 15000)
        
        // This regex ONLY matches if "UGX" is directly before or after the number.
        val regex = Regex("""(?i)(?:UGX\s*([\d,]+))|(?:([\d,]+)\s*UGX)""")
        val matches = regex.findAll(body)
        
        for (match in matches) {
            val numStr = match.groups[1]?.value ?: match.groups[2]?.value
            val num = numStr?.replace(",", "")?.toIntOrNull()
            
            if (num != null && num in validAmounts) {
                Log.d("AjonActivator", "Extracted valid amount: $num")
                return num
            } else {
                Log.d("AjonActivator", "Found amount $num but it is not in the valid list. Ignoring.")
            }
        }
        
        return null
    }

    private fun addToQueue(context: Context, phone: String, password: String, payerName: String, amount: Int, isTwoDay: Boolean) {
        val prefs = context.getSharedPreferences("AjonQueue", Context.MODE_PRIVATE)
        val currentQueue = prefs.getString("queue", "") ?: ""
        val newEntry = "$phone|$password|$payerName|$amount|${if (isTwoDay) "1" else "0"}\n"
        prefs.edit().putString("queue", currentQueue + newEntry).apply()
        Log.d("AjonActivator", "Added to queue: $phone (2-Day: $isTwoDay, Amount: $amount)")
    }

    private fun makePasswordHourly(year: Int, monthIndex0: Int, day: Int, hour: Int): String {
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

    private fun makePassword2Days(year: Int, monthIndex0: Int, day: Int, hour: Int): String {
        val monthIndex = (year - 2026) * 12 + monthIndex0
        val dayIdx = monthIndex * 31 + day
        val hourIdx = dayIdx * 24 + hour
        val num = 1500 + hourIdx * 17 + 33 * 7
        val l1 = ('a' + ((year - 2026) % 26))
        val l2 = ('a' + (monthIndex0 % 26))
        val l3 = ('a' + (day % 26))
        val l4 = ('a' + hour)
        val last = (hourIdx * 7 + year % 10) % 9 + 1
        return "aj$num$l1$l2$l3$l4$last".lowercase()
    }
}
