package com.smartstoryboard.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.app.AlarmManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private var pendingLang = "en-IN"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#050b1f")
        window.navigationBarColor = Color.parseColor("#071029")
        tts = TextToSpeech(this) { }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#050b1f"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
        setContentView(web)
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    inner class Bridge {
        @JavascriptInterface
        fun speak(text: String, lang: String) = runOnUiThread {
            tts?.language = Locale.forLanguageTag(lang)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s")
        }

        @JavascriptInterface fun drain(): String = Store.drain(this@MainActivity)
        @JavascriptInterface fun setApps(csv: String) = Store.setApps(this@MainActivity, csv)
        @JavascriptInterface fun setAlarms(json: String) { try { Store.setAlarms(this@MainActivity, json) } catch (e: Exception) {} }
        @JavascriptInterface fun status(): String {
            val c = this@MainActivity
            val am = c.getSystemService(ALARM_SERVICE) as AlarmManager
            return org.json.JSONObject()
                .put("notif", NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName))
                .put("sms", c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
                .put("post", Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                .put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()).toString()
        }
        @JavascriptInterface fun openNotifAccess() = runOnUiThread { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        @JavascriptInterface fun openExact() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        @JavascriptInterface fun requestPerms() = runOnUiThread {
            val l = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(l.toTypedArray(), 2)
        }
        @JavascriptInterface fun importSms(n: Int): String {
            val out = JSONArray()
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return "[]"
            contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
                while (c.moveToNext() && out.length() < n) {
                    val a = c.getString(0) ?: "SMS"; val b = c.getString(1) ?: ""; val d = c.getLong(2)
                    out.put(org.json.JSONObject().put("id", "s:" + (a + b).hashCode()).put("source", "SMS").put("sender", a)
                        .put("text", b).put("at", java.time.Instant.ofEpochMilli(d).toString()))
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun listen(lang: String) = runOnUiThread {
            pendingLang = lang
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            else startListen(lang)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != 1) return
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListen(pendingLang)
        else js("onSpeechError('Microphone permission denied')")
    }

    private fun startListen(lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onSpeechError('Speech recognition is not available on this phone')"); return
        }
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr!!.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                js("onSpeech(" + JSONObject.quote(t) + ")")
            }
            override fun onError(e: Int) { js("onSpeechError('Could not hear you, try again')") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        sr!!.startListening(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finish()
}
