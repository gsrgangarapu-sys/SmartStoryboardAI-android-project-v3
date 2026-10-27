package com.smartstoryboard.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (!Store.allowed(this).contains(pkg)) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = sbn.notification.extras
        val title = e.getCharSequence("android.title")?.toString() ?: return
        val text = (e.getCharSequence("android.bigText") ?: e.getCharSequence("android.text"))?.toString() ?: return
        val src = when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("telegram") -> "Telegram"
            else -> pkg
        }
        Store.add(this, "n:$pkg:${(title + text).hashCode()}:${sbn.postTime}", src, title, text, sbn.postTime)
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        val addr = parts.firstOrNull()?.originatingAddress ?: "SMS"
        val body = parts.joinToString("") { it.messageBody ?: "" }
        Store.add(c, "s:" + (addr + body).hashCode(), "SMS", addr, body, System.currentTimeMillis())
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("rem", "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, "rem").setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Smart Storyboard AI").setContentText(i.getStringExtra("t") ?: "Reminder")
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify((System.nanoTime() % Int.MAX_VALUE).toInt(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Store.reschedule(c) }
}
