package com.smartstoryboard.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private val OTP = Regex("\\botp\\b|one[- ]time (pass|code)|verification code|\\b\\d{4,8}\\b is your", RegexOption.IGNORE_CASE)
    private fun prefs(c: Context) = c.getSharedPreferences("ssb", Context.MODE_PRIVATE)

    fun allowed(c: Context): Set<String> =
        prefs(c).getString("apps", "com.whatsapp,com.whatsapp.w4b,org.telegram.messenger")!!
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setApps(c: Context, csv: String) = prefs(c).edit().putString("apps", csv).apply()

    @Synchronized
    fun add(c: Context, id: String, source: String, sender: String, text: String, at: Long) {
        if (OTP.containsMatchIn(text)) return
        val q = JSONArray(prefs(c).getString("queue", "[]"))
        for (i in 0 until q.length()) if (q.getJSONObject(i).getString("id") == id) return
        q.put(JSONObject().put("id", id).put("source", source).put("sender", sender).put("text", text)
            .put("at", java.time.Instant.ofEpochMilli(at).toString()))
        val keep = JSONArray()
        for (i in maxOf(0, q.length() - 200) until q.length()) keep.put(q.get(i))
        prefs(c).edit().putString("queue", keep.toString()).apply()
    }

    @Synchronized
    fun drain(c: Context): String {
        val s = prefs(c).getString("queue", "[]")!!
        if (s != "[]") prefs(c).edit().putString("queue", "[]").apply()
        return s
    }

    private fun pi(c: Context, id: String, title: String = ""): PendingIntent =
        PendingIntent.getBroadcast(c, id.hashCode(),
            Intent(c, AlarmReceiver::class.java).putExtra("t", title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(c: Context, id: String, title: String, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, id, title))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, id, title))
    }

    @Synchronized
    fun setAlarms(c: Context, json: String) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val old = JSONArray(prefs(c).getString("alarms", "[]"))
        for (i in 0 until old.length()) am.cancel(pi(c, old.getJSONObject(i).getString("id")))
        val now = JSONArray(json)
        for (i in 0 until now.length()) {
            val a = now.getJSONObject(i)
            schedule(c, a.getString("id"), a.getString("title"), a.getLong("at"))
        }
        prefs(c).edit().putString("alarms", json).apply()
    }

    fun reschedule(c: Context) {
        val a = JSONArray(prefs(c).getString("alarms", "[]"))
        val t = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("at") > t) schedule(c, o.getString("id"), o.getString("title"), o.getLong("at"))
        }
    }
}
