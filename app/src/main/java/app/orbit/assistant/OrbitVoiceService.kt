package app.orbit.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import java.util.Locale

class OrbitVoiceService : Service(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var running = false
    private var speaking = false
    private var activeUntil = 0L
    private lateinit var processor: CommandProcessor

    private val wake = Regex("орбит\\w*|orbit")

    override fun onCreate() {
        super.onCreate()
        val channelId = "orbit"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Orbit", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Orbit слушает")
            .setContentText("Скажи: «Орбит, ...»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        ServiceCompat.startForeground(
            this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        tts = TextToSpeech(this, this)
        processor = CommandProcessor(this, ::say) { stopSelf() }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale("ru", "RU")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { resume() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { resume() }
            })
        }
    }

    private fun resume() = main.post { speaking = false; listen() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        listen()
        return START_STICKY
    }

    private fun say(text: String) {
        main.post {
            if (!ttsReady) return@post
            speaking = true
            recognizer?.cancel()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbit")
        }
    }

    private fun listen(delayMs: Long = 300) {
        main.removeCallbacks(startRunnable)
        main.postDelayed(startRunnable, delayMs)
    }

    private val startRunnable = Runnable {
        if (!running || speaking) return@Runnable
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text != null) handle(text)
            if (!speaking) listen()
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return }
            listen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handle(text: String) {
        val lower = text.lowercase()
        val m = wake.find(lower)
        val now = System.currentTimeMillis()
        when {
            m != null -> {
                val cmd = lower.substring(m.range.last + 1).trim(' ', ',', '.')
                if (cmd.isEmpty()) { activeUntil = now + 8000; say("Слушаю") }
                else runCommand(cmd)
            }
            now < activeUntil -> { activeUntil = 0; runCommand(lower) }
        }
    }


    private fun runCommand(cmd: String) {
        if (!Prefs.alwaysAi(this) && processor.handle(cmd)) return
        askAi(cmd)
    }

    private fun askAi(text: String) {
        val key = Prefs.key(this)
        val local = LocalLlm.available(this)
        if (key.isEmpty() && !local) {
            say("Не понял команду. Добавь ключ Gemini или локальную модель в приложении"); return
        }
        val screen = OrbitAccessibilityService.instance?.screenSummary().orEmpty()
        val model = Prefs.model(this)
        val preferLocal = local && Prefs.preferLocal(this)
        Thread {
            var r: GeminiClient.Result? = null
            if (!preferLocal && key.isNotEmpty()) r = GeminiClient.ask(key, model, text, screen)
            if ((r == null || r.error != null) && local) r = LocalLlm.ask(applicationContext, text, screen)
            val res = r ?: GeminiClient.Result(emptyList(), "", "нет ИИ")
            main.post { applyAi(res) }
        }.start()
    }

    private fun applyAi(r: GeminiClient.Result) {
        when {
            r.error != null -> say("Ошибка Gemini: ${r.error}")
            r.commands.isNotEmpty() -> r.commands.forEachIndexed { i, c ->
                main.postDelayed({ if (!processor.handle(c)) say("Не понял: $c") }, i * 2000L)
            }
            r.reply.isNotBlank() -> say(r.reply)
            else -> say("Не понял команду")
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        LocalLlm.reset()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
