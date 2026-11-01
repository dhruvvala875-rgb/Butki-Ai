package android.template.butki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale

class VoiceAssistantManager(
    private val context: Activity,
    private val onSpeechRecognized: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isTtsInitialized = false

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                context.runOnUiThread {
                    onStatusChanged("READY! TAP 'B' TO SPEAK")
                }
            }
            override fun onError(utteranceId: String?) {
                context.runOnUiThread {
                    onStatusChanged("READY! TAP 'B' TO SPEAK")
                }
            }
        })
    }

    // 🎙️ Clean Voice Recognition Start with Extended Silence Timeouts
    fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Lambe gaps (silence) ko handle karne ke liye time badha diya hai (6 seconds)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye Boss, Butki sun rahi hai...")
            }
            @Suppress("DEPRECATION")
            context.startActivityForResult(intent, REQUEST_CODE_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(context, "Mic Error! Niche box mein type karein.", Toast.LENGTH_LONG).show()
        }
    }

    // 🔊 Professional Text-to-Speech Output
    fun speak(text: String) {
        onStatusChanged("BUTKI IS SPEAKING...")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "BUTKI_VOICE")
        }
        if (isTtsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "BUTKI_VOICE")
        }
    }

    // Handle activity result from MainActivity
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SPEECH && resultCode == Activity.RESULT_OK && data != null) {
            val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.trim() ?: ""
            if (spokenText.isNotEmpty()) {
                onSpeechRecognized(spokenText.lowercase())
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.getDefault()
            }
            // Soft & Caring Assistant Voice Tuning
            tts.setPitch(1.10f)
            tts.setSpeechRate(0.92f)
            isTtsInitialized = true
        }
    }

    fun stop() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {}
    }

    companion object {
        const val REQUEST_CODE_SPEECH = 200
    }
}
