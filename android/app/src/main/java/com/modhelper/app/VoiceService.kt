package com.modhelper.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Слушает микрофон и переводит речь в текст (Google SpeechRecognizer). */
class VoiceService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
    }

    private var rec: SpeechRecognizer? = null
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            3, Hub.notification("Слушаю голос и перевожу в текст"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Hub.toast("На телефоне нет службы распознавания речи (нужно приложение Google)")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!active) {
            active = true
            Hub.voiceOn = true
            Hub.changed()
            listen()
        }
        return START_NOT_STICKY
    }

    private fun listen() {
        if (!active) return
        try {
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            rec = r
            r.setRecognitionListener(listener)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            r.startListening(i)
        } catch (e: Exception) {
            Hub.main.postDelayed({ listen() }, 3000)
        }
    }

    private fun again(delay: Long) {
        Hub.main.postDelayed({ listen() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Hub.toast("Нет разрешения на микрофон")
                stopSelf()
                return
            }
            val quick = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            again(if (quick) 300L else 2000L)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = if (list != null && list.isNotEmpty()) list[0] else ""
            if (text.isNotBlank()) {
                Hub.onSpeech(text.trim())
            }
            again(200L)
        }
    }

    override fun onDestroy() {
        active = false
        Hub.voiceOn = false
        try {
            rec?.destroy()
        } catch (e: Exception) {
        }
        Hub.changed()
        super.onDestroy()
    }
}
