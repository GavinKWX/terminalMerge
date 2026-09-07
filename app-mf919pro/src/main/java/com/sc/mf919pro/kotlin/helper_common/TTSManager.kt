package com.sc.mf919pro.kotlin.helper_common

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object TTSManager : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false

    fun init(context: Context) {
        println("init :: $tts")
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        println("onInit :: $status")

        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.US)

            // ---- ADD THESE ----
            //tts?.setPitch(2.0f)      // Maximum stable pitch
            tts?.setSpeechRate(0.8f) // Slow down

            // Warm up (solves the 4-second delay)
            tts?.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "warmup")

            isReady = true
        }
    }

    fun speak(text: String) {
        if (isReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}