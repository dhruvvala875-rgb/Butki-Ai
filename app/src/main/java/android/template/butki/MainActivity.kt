package android.template.butki

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var generativeModel: GenerativeModel? = null 
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var txtResponse: TextView
    private lateinit var txtStatus: TextView
    private lateinit var neonVisualizer: NeonOrbView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false
    private var isPopupOpen = false // 🔴 NAYA LOCK: Double Mic Clash rokne ke liye
    private var isWaitingForWakeWord = true
    private var isTrainingVoice = false
    private val currentRmsSamples = mutableListOf<Float>()

    private var savedSchedule: String = "Abhi tak koi schedule set nahi kiya hai aapne."
    private var savedTasks: MutableList<String> = mutableListOf()

    private var isAskingNotificationPermission = false
    private var isAskingToReply = false
    private var isWaitingForReplyText = false
    private var pendingNotificationTitle = ""
    private var pendingNotificationText = ""
    private var canReplyToCurrentNotification = false

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "butki.NEW_NOTIFICATION") {
                val title = intent.getStringExtra("title") ?: ""
                val text = intent.getStringExtra("text") ?: ""
                val canReply = intent.getBooleanExtra("canReply", false)
                
                if (title.isNotEmpty() && text.isNotEmpty() && !isSpeaking && !isPopupOpen) {
                    pendingNotificationTitle = title
                    pendingNotificationText = text
                    canReplyToCurrentNotification = canReply
                    
                    isAskingNotificationPermission = true
                    isAskingToReply = false
                    isWaitingForReplyText = false
                    isWaitingForWakeWord = false
                    
                    val promptText = "Boss, $title ki taraf se ek message aaya hai. Kya main padh ke sunau?"
                    txtResponse.text = "● Notification Detected..."
                    speakOut(promptText)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#05050A")

        sharedPreferences = getSharedPreferences("ButkiPrefs", Context.MODE_PRIVATE)
        setupFuturisticUI()
        tts = TextToSpeech(this, this)

        try {
            registerReceiver(notificationReceiver, IntentFilter("butki.NEW_NOTIFICATION"), RECEIVER_EXPORTED)
        } catch (e: Exception) {
            try {
                registerReceiver(notificationReceiver, IntentFilter("butki.NEW_NOTIFICATION"))
            } catch (ex: Exception) { }
        }

        val savedKey = sharedPreferences.getString("GEMINI_API_KEY", "")
        if (savedKey.isNullOrEmpty()) {
            showApiKeyDialog()
        } else {
            setupGeminiAI(savedKey)
            checkNotificationPermission()
            checkPermissionsAndStart()
        }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.hint = "Paste Gemini API Key here"
        input.setPadding(40, 40, 40, 40)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Setup Butki AI")
            .setMessage("Boss, please enter your Gemini API Key to activate my brain!")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save & Start") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    sharedPreferences.edit().putString("GEMINI_API_KEY", key).apply()
                    setupGeminiAI(key)
                    checkNotificationPermission()
                    checkPermissionsAndStart()
                } else {
                    showApiKeyDialog() 
                }
            }
            .show()
    }

    private fun showVoiceEnrollDialog() {
        killBackgroundMic() // Mic free karo
        val currentPhrase = sharedPreferences.getString("BOSS_WAKE_PHRASE", "Not Set (Default: Hey Butki)")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🎙️ Boss Voice Lock Setup")
            .setMessage("Current Lock: \"$currentPhrase\"\n\n'Record My Voice' dabayein aur apna wake-word (jaise 'Hey Butki') boliye.")
            .setPositiveButton("Record My Voice") { _, _ ->
                isTrainingVoice = true
                isWaitingForWakeWord = false
                txtStatus.text = "● RECORDING VOICE SAMPLE..."
                txtResponse.text = "Abhi boliye apna secret wake-word..."
                startPopupMic()
            }
            .setNeutralButton("Remove Lock") { _, _ ->
                sharedPreferences.edit().remove("BOSS_WAKE_PHRASE").remove("BOSS_VOICE_RMS").apply()
                Toast.makeText(this, "Voice Lock Removed!", Toast.LENGTH_SHORT).show()
                restartListeningWithDelay(400)
            }
            .setNegativeButton("Cancel") { _, _ ->
                restartListeningWithDelay(400)
            }
            .show()
    }

    private fun setupGeminiAI(apiKey: String) {
        generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey 
        )
    }

    override fun onResume() {
        super.onResume()
        val cn = ComponentName(this, ButkiNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())
        
        if (enabled && ::txtResponse.isInitialized && txtResponse.text.toString().contains("allow kijiye")) {
            txtResponse.text = "● System Ready! Beech wale 'B' par Tap karke boliye!"
        }

        val savedKey = sharedPreferences.getString("GEMINI_API_KEY", "")
        // Agar popup khula nahi hai, tabhi background mic chalu karo
        if (!isPopupOpen && !savedKey.isNullOrEmpty() && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            restartListeningWithDelay(600)
        }
    }

    private fun checkNotificationPermission() {
        val cn = ComponentName(this, ButkiNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())
        if (!enabled) {
            txtResponse.text = "Boss, Notifications padhne ke liye 'Notification Access' allow kijiye."
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun setupFuturisticUI() {
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05050C"))
            setPadding(45, 80, 45, 60)
        }

        val headerLayout = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        }

        val titleText = TextView(this).apply {
            text = "B U T K I   A.I."
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F0FF"))
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#00F0FF"))
            gravity = Gravity.CENTER
            
            setOnLongClickListener {
                sharedPreferences.edit().remove("GEMINI_API_KEY").apply()
                showApiKeyDialog()
                true
            }
        }

        txtStatus = TextView(this).apply {
            text = "● TAP 'B' TO SPEAK DIRECTLY"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF007F"))
            setShadowLayer(15f, 0f, 0f, Color.parseColor("#FF007F"))
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        }

        headerLayout.addView(titleText)
        headerLayout.addView(txtStatus)
        root.addView(headerLayout)

        neonVisualizer = NeonOrbView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(750, 750).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            // 🔴 TAP ON 'B': Pehle background mic band karega, fir Popup kholega (Zero Clash!)
            setOnClickListener {
                tts.stop()
                isSpeaking = false
                isWaitingForWakeWord = false
                startPopupMic()
            }
            setOnLongClickListener {
                tts.stop()
                isSpeaking = false
                showVoiceEnrollDialog()
                true
            }
        }
        root.addView(neonVisualizer)

        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#121225"))
            cornerRadius = 45f
            setStroke(3, Color.parseColor("#00F0FF"))
        }

        val consoleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(50, 40, 50, 40)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        val streamLabel = TextView(this).apply {
            text = "NEURAL AUDIO STREAM // (TAP 'B' TO TALK)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F0FF"))
            setPadding(0, 0, 0, 14)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        txtResponse = TextView(this).apply {
            text = "System Ready! Beech wale 'B' par Tap karke boliye!"
            textSize = 16f
            setTextColor(Color.parseColor("#F5F5FF"))
            setLineSpacing(10f, 1.0f)
        }

        scrollView.addView(txtResponse)
        consoleLayout.addView(streamLabel)
        consoleLayout.addView(scrollView)
        root.addView(consoleLayout)

        setContentView(root)
    }

    // 🔴 NAYA FUNCTION: Background Mic ko jad se band karne ke liye taaki Popup Mic free ho jaye
    private fun killBackgroundMic() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {}
    }

    // 🔴 CLASH-FREE POPUP MIC 🔴
    private fun startPopupMic() {
        isPopupOpen = true
        killBackgroundMic() // Pehle background mic ko poori tarah hatao
        txtStatus.text = "● POPUP MIC ACTIVE..."

        // 150ms ka break do taaki Android microphone hardware ko release kar de
        mainHandler.postDelayed({
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, if (isTrainingVoice) "Apna Secret Wake-Word Boliye..." else "Boliye Boss, Butki sun rahi hai...")
                }
                startActivityForResult(intent, 200)
            } catch (e: Exception) {
                isPopupOpen = false
                Toast.makeText(this, "Phone me Google Voice Search active nahi hai!", Toast.LENGTH_LONG).show()
            }
        }, 150)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            isPopupOpen = false // Popup band ho gaya
            if (resultCode == Activity.RESULT_OK && data != null) {
                val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = matches?.firstOrNull()?.lowercase()?.trim() ?: ""
                
                if (spokenText.isNotEmpty()) {
                    if (isTrainingVoice) {
                        isTrainingVoice = false
                        isWaitingForWakeWord = true
                        sharedPreferences.edit().putString("BOSS_WAKE_PHRASE", spokenText).apply()
                        txtResponse.text = "✅ Voice Lock Saved: \"$spokenText\""
                        speakOut("Boss, aapka secret code '$spokenText' maine save kar liya hai!")
                    } else {
                        isWaitingForWakeWord = false
                        processSpokenText(spokenText, matches, 5.0f, true)
                    }
                    return
                }
            }
            // Agar user ne bina bole popup cancel kar diya, toh wapas background mic chalu karo
            txtStatus.text = "● TAP 'B' TO SPEAK"
            restartListeningWithDelay(500)
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setupContinuousSpeechRecognizer()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupContinuousSpeechRecognizer()
        } else {
            txtStatus.text = "● MIC PERMISSION DENIED"
            txtResponse.text = "Microphone permission allow karein."
        }
    }

    private fun setupContinuousSpeechRecognizer() {
        if (isPopupOpen || isSpeaking) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            txtStatus.text = "● TAP 'B' TO SPEAK"
            return
        }

        killBackgroundMic()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isSpeaking && !isPopupOpen) {
                    if (isAskingNotificationPermission || isAskingToReply || isWaitingForReplyText) {
                        txtStatus.text = "● WAITING FOR YOUR ANSWER..."
                    } else if (isWaitingForWakeWord) {
                        txtStatus.text = "● SAY 'HEY BUTKI' OR TAP 'B'..."
                    } else {
                        txtStatus.text = "● LISTENING TO COMMAND..."
                    }
                    neonVisualizer.setMode(0)
                }
            }

            override fun onBeginningOfSpeech() {
                currentRmsSamples.clear()
                if (!isPopupOpen) txtStatus.text = "● HEARING VOICE..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isSpeaking && !isPopupOpen) {
                    neonVisualizer.updateVoiceLevel(rmsdB.coerceAtLeast(0f))
                    if (rmsdB > 2.0f) currentRmsSamples.add(rmsdB)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                neonVisualizer.updateVoiceLevel(1f)
            }

            override fun onError(error: Int) {
                neonVisualizer.updateVoiceLevel(1f)
                if (!isSpeaking && !isPopupOpen) {
                    restartListeningWithDelay(800)
                }
            }

            override fun onResults(results: Bundle?) {
                if (isPopupOpen) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()?.lowercase()?.trim() ?: ""
                val avgRms = if (currentRmsSamples.isNotEmpty()) currentRmsSamples.average().toFloat() else 5.0f

                if (spokenText.isNotEmpty()) {
                    processSpokenText(spokenText, matches, avgRms, false)
                } else {
                    restartListeningWithDelay(500)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (isPopupOpen) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val currentText = matches?.firstOrNull()?.lowercase() ?: ""
                if (currentText.isNotEmpty() && !isSpeaking) {
                    txtResponse.text = "Sun rahi hoon: \"$currentText...\""
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListeningLoop()
    }

    private fun processSpokenText(spokenText: String, matches: ArrayList<String>?, avgRms: Float, isDirectTap: Boolean) {
        var containsDefaultButki = false
        matches?.forEach { match ->
            val text = match.lowercase()
            if (text.contains("butki") || text.contains("bootki") || text.contains("bhootki") || text.contains("bhutki") || text.contains("chutki") || text.contains("putki") || text.contains("boot ki") || text.contains("hutki")) {
                containsDefaultButki = true
            }
        }

        if (isTrainingVoice) {
            isTrainingVoice = false
            isWaitingForWakeWord = true
            sharedPreferences.edit()
                .putString("BOSS_WAKE_PHRASE", spokenText)
                .putFloat("BOSS_VOICE_RMS", avgRms)
                .apply()
            txtResponse.text = "✅ Voice Print Saved: \"$spokenText\""
            speakOut("Boss, aapki aawaz aur secret code '$spokenText' maine lock kar liya hai!")
            return
        }

        if (isAskingNotificationPermission) {
            if (spokenText.contains("haan") || spokenText.contains("yes") || spokenText.contains("padh") || spokenText.contains("sunao")) {
                isAskingNotificationPermission = false
                if (canReplyToCurrentNotification) {
                    isAskingToReply = true
                    speakOut("Message hai: $pendingNotificationText. Kya iska reply karna hai Boss?")
                } else {
                    isWaitingForWakeWord = true
                    speakOut("Message hai: $pendingNotificationText.")
                }
            } else {
                isAskingNotificationPermission = false
                isWaitingForWakeWord = true
                speakOut("Theek hai Boss, main ise ignore kar rahi hoon.")
            }
        } 
        else if (isAskingToReply) {
            if (spokenText.contains("haan") || spokenText.contains("yes") || spokenText.contains("karo") || spokenText.contains("bhej")) {
                isAskingToReply = false
                isWaitingForReplyText = true
                speakOut("Bataiye Boss, kya reply bhejna hai?")
            } else {
                isAskingToReply = false
                isWaitingForWakeWord = true
                speakOut("Theek hai Boss, koi reply nahi bhej rahi.")
            }
        }
        else if (isWaitingForReplyText) {
            isWaitingForReplyText = false
            isWaitingForWakeWord = true
            val success = ButkiNotificationService.sendReply(this@MainActivity, spokenText)
            if (success) speakOut("Message bhej diya Boss!") else speakOut("Sorry Boss, reply nahi ja saka.")
        } 
        else if (isWaitingForWakeWord && !isDirectTap) {
            val savedPhrase = sharedPreferences.getString("BOSS_WAKE_PHRASE", "")
            if (!savedPhrase.isNullOrEmpty()) {
                val phraseMatched = matches?.any { it.lowercase().contains(savedPhrase) || savedPhrase.contains(it.lowercase()) } == true
                if (phraseMatched) {
                    isWaitingForWakeWord = false
                    txtResponse.text = "🔓 Boss Verified! Listening..."
                    speakOut("Aawaz pehchan li Boss! Boliye, kya hukam hai?")
                } else {
                    restartListeningWithDelay(500)
                }
            } else {
                if (containsDefaultButki) {
                    isWaitingForWakeWord = false
                    txtResponse.text = "● Awake. Listening..."
                    speakOut("Haan boliye Boss, main sun rahi hoon!")
                } else {
                    restartListeningWithDelay(500)
                }
            }
        } 
        else {
            txtResponse.text = "Aapne kaha: \"$spokenText\""
            askButki(spokenText)
        }
    }

    private fun startListeningLoop() {
        if (!isSpeaking && !isPopupOpen) {
            try {
                if (speechRecognizer == null) {
                    setupContinuousSpeechRecognizer()
                } else {
                    speechRecognizer?.startListening(recognizerIntent)
                }
            } catch (e: Exception) {
                restartListeningWithDelay(1000)
            }
        }
    }

    private fun restartListeningWithDelay(delayMillis: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isSpeaking && !isPopupOpen) {
                startListeningLoop()
            }
        }, delayMillis)
    }

    private fun askButki(prompt: String) {
        val aiModel = generativeModel
        if (aiModel == null) {
            txtResponse.text = "API Key missing!"
            speakOut("Boss, meri API key nahi mil rahi hai.")
            return
        }

        isSpeaking = true
        killBackgroundMic()
        txtStatus.text = "● BUTKI IS THINKING..."
        neonVisualizer.setMode(1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val personaPrompt = """
                    You are Butki, a deeply loving, sweet, caring, and playfully naughty female AI companion/girlfriend. 
                    CRITICAL RULE 1: Always address the user as "Boss" affectionately. 
                    CRITICAL RULE 2: NEVER use emojis, asterisks, or special characters.
                    CRITICAL RULE 3: Reply in conversational Hindi/Hinglish (1-2 short sentences).
                    User's input: $prompt
                """.trimIndent()

                val response = aiModel.generateContent(personaPrompt)
                val answer = response.text?.replace(Regex("[*#~]"), "") ?: "Kuch samajh nahi aaya Boss, phir se boliye na."

                withContext(Dispatchers.Main) {
                    txtResponse.text = "Butki: \"$answer\""
                    speakOut(answer)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtStatus.text = "● API ERROR"
                    txtResponse.text = "Error: ${e.localizedMessage}"
                    speakOut("Sorry Boss, internet ya API key mein kuch problem aa rahi hai.")
                }
            }
        }
    }

    private fun speakOut(text: String) {
        isSpeaking = true
        txtStatus.text = "● BUTKI IS SPEAKING..."
        neonVisualizer.setMode(2)
        neonVisualizer.updateVoiceLevel(8f)

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "BUTKI_VOICE")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "BUTKI_VOICE")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("hi", "IN")
            tts.setPitch(1.3f) 
            tts.setSpeechRate(0.95f)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        neonVisualizer.setMode(0)
                        neonVisualizer.updateVoiceLevel(1f)
                        isSpeaking = false
                        if (!isAskingNotificationPermission && !isAskingToReply && !isWaitingForReplyText) {
                            isWaitingForWakeWord = true
                        }
                        restartListeningWithDelay(500)
                    }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        restartListeningWithDelay(500)
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        killBackgroundMic()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    class NeonOrbView(context: Context) : View(context) {
        private val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.parseColor("#00F0FF")
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#00F0FF"))
        }
        private val pinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.parseColor("#FF007F")
            setShadowLayer(30f, 0f, 0f, Color.parseColor("#FF007F"))
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#1800F0FF")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 110f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            setShadowLayer(30f, 0f, 0f, Color.parseColor("#FF007F"))
        }

        private var angle = 0f; private var voicePulse = 1f; private var mode = 0; private val rect = RectF()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3500; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener {
                    angle = it.animatedValue as Float
                    if (mode == 2) voicePulse = 4f + 5f * sin(Math.toRadians(angle.toDouble() * 4)).toFloat()
                    invalidate()
                }
                start()
            }
        }

        fun updateVoiceLevel(rms: Float) { voicePulse = rms.coerceIn(0.5f, 12f); invalidate() }
        fun setMode(newMode: Int) { mode = newMode; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val baseR = (width.coerceAtMost(height) / 3.2f) + (voicePulse * 8f)
            canvas.drawCircle(cx, cy, baseR * 0.72f, corePaint)
            rect.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR)
            canvas.drawArc(rect, angle, 75f, false, cyanPaint)
            canvas.drawArc(rect, angle + 120f, 75f, false, cyanPaint)
            canvas.drawArc(rect, angle + 240f, 75f, false, cyanPaint)

            val r2 = baseR * 0.84f
            rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
            canvas.drawArc(rect, -angle * 1.4f, 110f, false, pinkPaint)
            canvas.drawArc(rect, -angle * 1.4f + 180f, 110f, false, pinkPaint)

            for (i in 0 until 24) {
                val rad = Math.toRadians((i * 15 + angle).toDouble())
                val inner = baseR * 1.06f
                val outer = inner + (voicePulse * 6f) * abs(sin(rad * 3)).toFloat() + 12f
                canvas.drawLine(cx + inner * cos(rad).toFloat(), cy + inner * sin(rad).toFloat(),
                    cx + outer * cos(rad).toFloat(), cy + outer * sin(rad).toFloat(), if (i % 2 == 0) cyanPaint else pinkPaint)
            }
            canvas.drawText("B", cx, cy - ((textPaint.descent() + textPaint.ascent()) / 2), textPaint)
        }
    }
}
