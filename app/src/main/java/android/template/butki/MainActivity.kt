package android.template.butki

import android.Manifest
import android.animation.ValueAnimator
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
import android.media.AudioManager
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
    private lateinit var audioManager: AudioManager

    private lateinit var txtResponse: TextView
    private lateinit var txtStatus: TextView
    private lateinit var neonVisualizer: NeonOrbView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false
    private var isWaitingForWakeWord = true

    // 🔴 VOICE LOCK & TRAINING VARIABLES 🔴
    private var isTrainingVoice = false
    private val currentRmsSamples = mutableListOf<Float>()

    // Schedule & Task Memory Storage
    private var savedSchedule: String = "Abhi tak koi schedule ya time table set nahi kiya hai aapne. Mujhe bataiye, main yaad rakh lungi!"
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
                
                if (title.isNotEmpty() && text.isNotEmpty() && !isSpeaking) {
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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    // 🔴 NAYA POPUP: APNI AAWAZ REGISTER KARNE KE LIYE 🔴
    private fun showVoiceEnrollDialog() {
        speechRecognizer?.stopListening()
        val currentPhrase = sharedPreferences.getString("BOSS_WAKE_PHRASE", "Not Set (Default: Hey Butki)")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🎙️ Boss Voice Lock Setup")
            .setMessage("Current Lock: \"$currentPhrase\"\n\nJab aap 'Record My Voice' dabayenge, toh apna wake-word (jaise 'Hey Butki' ya koi secret code) apni normal aawaz mein boliye. Butki aapki aawaz ki frequency aur words lock kar legi!")
            .setPositiveButton("Record My Voice") { _, _ ->
                isTrainingVoice = true
                isWaitingForWakeWord = false
                txtStatus.text = "● RECORDING VOICE SAMPLE..."
                txtResponse.text = "Abhi boliye apna secret wake-word (e.g., 'Hey Butki')..."
                restartListeningWithDelay(300)
            }
            .setNeutralButton("Remove Voice Lock") { _, _ ->
                sharedPreferences.edit()
                    .remove("BOSS_WAKE_PHRASE")
                    .remove("BOSS_VOICE_RMS")
                    .apply()
                Toast.makeText(this, "Voice Lock Removed!", Toast.LENGTH_SHORT).show()
                txtResponse.text = "● Voice Lock hata diya gaya hai."
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
        
        val savedKey = sharedPreferences.getString("GEMINI_API_KEY", "")
        if (enabled && ::txtResponse.isInitialized && txtResponse.text.toString().contains("allow kijiye") && !savedKey.isNullOrEmpty()) {
            txtResponse.text = "● System Ready! 'B' par Long-Press karke apni Voice Lock karein."
        }

        if (!savedKey.isNullOrEmpty() && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (speechRecognizer == null) {
                setupContinuousSpeechRecognizer()
            } else {
                restartListeningWithDelay(400)
            }
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
            text = "● SYSTEM BOOTING..."
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
            // 1. Single Tap: Direct Wake-Up
            setOnClickListener {
                if (!isSpeaking) {
                    isWaitingForWakeWord = false
                    txtResponse.text = "● Awake. Boliye Boss, main sun rahi hoon..."
                    speakOut("Haan boliye Boss!")
                }
            }
            // 🔴 2. Long Press on 'B': Apni Voice Enroll / Lock Karein 🔴
            setOnLongClickListener {
                if (!isSpeaking) {
                    showVoiceEnrollDialog()
                }
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
            text = "NEURAL AUDIO STREAM // (LONG-PRESS 'B' FOR VOICE LOCK)"
            textSize = 10f
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
            text = "System Ready! Boliye \"Hey Butki\" ya 'B' ko daba kar apni aawaz save karein."
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

    private fun setBeepMute(mute: Boolean) {
        try {
            val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0)
        } catch (e: Exception) { }
    }

    private fun setupContinuousSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                mainHandler.postDelayed({ setBeepMute(false) }, 200)

                if (!isSpeaking) {
                    if (isTrainingVoice) {
                        txtStatus.text = "● SPEAK YOUR WAKE WORD NOW..."
                    } else if (isAskingNotificationPermission || isAskingToReply || isWaitingForReplyText) {
                        txtStatus.text = "● WAITING FOR YOUR ANSWER..."
                    } else if (isWaitingForWakeWord) {
                        val savedPhrase = sharedPreferences.getString("BOSS_WAKE_PHRASE", "")
                        if (!savedPhrase.isNullOrEmpty()) {
                            txtStatus.text = "● VOICE LOCKED: WAITING FOR BOSS..."
                        } else {
                            txtStatus.text = "● WAITING FOR 'HEY BUTKI' (OR TAP 'B')..."
                        }
                    } else {
                        txtStatus.text = "● LISTENING TO YOUR COMMAND..."
                    }
                    neonVisualizer.setMode(0)
                }
            }

            override fun onBeginningOfSpeech() {
                currentRmsSamples.clear() // Nayi aawaz ke samples lena shuru karein
                if (isTrainingVoice) {
                    txtStatus.text = "● CAPTURING BOSS VOICE PRINT..."
                } else if (isWaitingForWakeWord) {
                    txtStatus.text = "● ANALYZING VOICE..."
                } else {
                    txtStatus.text = "● RECEIVING COMMAND..."
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isSpeaking) {
                    neonVisualizer.updateVoiceLevel(rmsdB.coerceAtLeast(0f))
                    // Sirf jab insan bol raha ho (rms > 2), tab uski vocal energy record karein
                    if (rmsdB > 2.0f) {
                        currentRmsSamples.add(rmsdB)
                    }
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                neonVisualizer.updateVoiceLevel(1f)
            }

            override fun onError(error: Int) {
                neonVisualizer.updateVoiceLevel(1f)
                if (!isSpeaking) {
                    restartListeningWithDelay(1000)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var spokenText = ""
                var containsDefaultButki = false

                // Bolne wale ki Average Vocal Energy (Pitch/Loudness) calculate karein
                val avgRms = if (currentRmsSamples.isNotEmpty()) currentRmsSamples.average().toFloat() else 5.0f

                matches?.forEach { match ->
                    val text = match.lowercase().trim()
                    if (spokenText.isEmpty()) spokenText = text 
                    if (text.contains("butki") || text.contains("bootki") || text.contains("bhootki") || text.contains("bhutki") || text.contains("chutki") || text.contains("putki") || text.contains("boot ki") || text.contains("hutki") || text.contains("book ki")) {
                        containsDefaultButki = true
                    }
                }

                if (spokenText.isNotEmpty()) {
                    
                    // 🔴 STEP 1: AGAR BOSS APNI VOICE TRAIN/SAVE KAR RAHE HAIN 🔴
                    if (isTrainingVoice) {
                        isTrainingVoice = false
                        isWaitingForWakeWord = true
                        sharedPreferences.edit()
                            .putString("BOSS_WAKE_PHRASE", spokenText)
                            .putFloat("BOSS_VOICE_RMS", avgRms)
                            .apply()

                        txtResponse.text = "✅ Voice Print Saved!\nWake Phrase: \"$spokenText\"\nVocal Energy: ${"%.1f".format(avgRms)} dB"
                        speakOut("Boss, aapki aawaz aur secret code '$spokenText' maine lock kar liya hai. Ab main sirf aapki aawaz par jaagungi!")
                        return
                    }

                    // STEP 2: NOTIFICATION PERMISSIONS
                    if (isAskingNotificationPermission) {
                        if (spokenText.contains("haan") || spokenText.contains("yes") || spokenText.contains("padh") || spokenText.contains("sunao") || spokenText.contains("thik hai")) {
                            isAskingNotificationPermission = false
                            if (canReplyToCurrentNotification) {
                                isAskingToReply = true
                                val msg = "Message hai: $pendingNotificationText. Kya iska reply karna hai Boss?"
                                txtResponse.text = "● Asking for reply permission..."
                                speakOut(msg)
                            } else {
                                isWaitingForWakeWord = true
                                val msg = "Message hai: $pendingNotificationText."
                                txtResponse.text = "● Reading notification..."
                                speakOut(msg)
                            }
                        } else if (spokenText.contains("nahi") || spokenText.contains("no") || spokenText.contains("mat") || spokenText.contains("rehne")) {
                            isAskingNotificationPermission = false
                            isWaitingForWakeWord = true
                            txtResponse.text = "● Ignored notification."
                            speakOut("Theek hai Boss, main ise ignore kar rahi hoon.")
                        } else {
                            restartListeningWithDelay(500)
                        }
                    } 
                    else if (isAskingToReply) {
                        if (spokenText.contains("haan") || spokenText.contains("yes") || spokenText.contains("karo") || spokenText.contains("bhej")) {
                            isAskingToReply = false
                            isWaitingForReplyText = true
                            txtResponse.text = "● Waiting for reply text..."
                            speakOut("Bataiye Boss, kya reply bhejna hai?")
                        } else {
                            isAskingToReply = false
                            isWaitingForWakeWord = true
                            txtResponse.text = "● Reply cancelled."
                            speakOut("Theek hai Boss, main koi reply nahi bhej rahi.")
                        }
                    }
                    else if (isWaitingForReplyText) {
                        isWaitingForReplyText = false
                        isWaitingForWakeWord = true
                        txtResponse.text = "● Sending Reply..."
                        val success = ButkiNotificationService.sendReply(this@MainActivity, spokenText)
                        if (success) {
                            speakOut("Message bhej diya Boss!")
                        } else {
                            speakOut("Sorry Boss, lagta hai yeh app direct reply support nahi karti.")
                        }
                    } 
                    // 🔴 STEP 3: VOICE LOCK VERIFICATION (JAB BUTKI SO RAHI HAI) 🔴
                    else if (isWaitingForWakeWord) {
                        val savedPhrase = sharedPreferences.getString("BOSS_WAKE_PHRASE", "")
                        val savedRms = sharedPreferences.getFloat("BOSS_VOICE_RMS", -1f)

                        if (!savedPhrase.isNullOrEmpty()) {
                            // Check 1: Kya bola gaya shabd Boss ke secret phrase se match karta hai?
                            val phraseMatched = matches?.any { 
                                it.lowercase().contains(savedPhrase) || savedPhrase.contains(it.lowercase()) 
                            } == true

                            // Check 2: Kya aawaz ki energy (loudness/pitch profile) Boss ke aas-paas hai? (Tolerance: 4.5 dB)
                            val rmsDiff = abs(avgRms - savedRms)
                            val voiceProfileMatched = savedRms < 0 || rmsDiff <= 4.5f

                            if (phraseMatched && voiceProfileMatched) {
                                isWaitingForWakeWord = false
                                txtResponse.text = "🔓 Boss Voice Verified! Listening..."
                                speakOut("Aawaz pehchan li Boss! Boliye, kya hukam hai?")
                            } else if (phraseMatched && !voiceProfileMatched) {
                                // Shabd sahi bola par aawaz/pitch alag thi!
                                txtResponse.text = "🔒 Voice Mismatch! (Diff: ${"%.1f".format(rmsDiff)} dB)"
                                speakOut("Secret code sahi hai par aawaz ki frequency match nahi hui. Aap mere Boss nahi lag rahe!")
                            } else if (containsDefaultButki) {
                                // Kisi ne 'Hey Butki' bola par Boss ka secret phrase kuch aur hai
                                txtResponse.text = "🔒 Locked! Waiting for Boss's secret voice print."
                                restartListeningWithDelay(600)
                            } else {
                                restartListeningWithDelay(600)
                            }
                        } else {
                            // Agar Voice Lock set nahi kiya hai, toh normal 'Hey Butki' kaam karega
                            if (containsDefaultButki) {
                                isWaitingForWakeWord = false
                                txtResponse.text = "● Awake. Listening to you..."
                                speakOut("Haan boliye Boss, main sun rahi hoon!")
                            } else {
                                restartListeningWithDelay(600)
                            }
                        }
                    } 
                    else {
                        txtResponse.text = "Aapne kaha: \"$spokenText\""
                        askButki(spokenText)
                    }
                } else {
                    restartListeningWithDelay(600)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var currentText = ""
                
                matches?.forEach { match ->
                    val text = match.lowercase()
                    if (currentText.isEmpty()) currentText = text
                }

                if (currentText.isNotEmpty() && !isSpeaking) {
                    txtResponse.text = "Sun rahi hoon: \"$currentText...\""
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListeningLoop()
    }

    private fun startListeningLoop() {
        if (!isSpeaking) {
            try {
                setBeepMute(true)
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                setBeepMute(false)
                restartListeningWithDelay(1000)
            }
        }
    }

    private fun restartListeningWithDelay(delayMillis: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isSpeaking) {
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
        speechRecognizer?.stopListening()
        setBeepMute(false)
        txtStatus.text = "● BUTKI IS THINKING..."
        neonVisualizer.setMode(1)

        val lowerPrompt = prompt.lowercase()
        if (lowerPrompt.contains("schedule") || lowerPrompt.contains("time table") || lowerPrompt.contains("routine") || lowerPrompt.contains("plan")) {
            if (lowerPrompt.contains("save") || lowerPrompt.contains("mera") || lowerPrompt.contains("hai")) {
                savedSchedule = prompt
            }
        } else if (lowerPrompt.contains("task") || lowerPrompt.contains("kaam")) {
            savedTasks.add(prompt)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val personaPrompt = """
                    You are Butki, a deeply loving, sweet, caring, and playfully naughty female AI companion/girlfriend. 
                    CRITICAL RULE 1: You must ALWAYS address the user as "Boss" in a sweet, affectionate, and respectful way. 
                    CRITICAL RULE 2: NEVER use emojis, asterisks, hashtags, or special characters. Produce purely spoken-word text because it will be spoken by a voice engine.
                    CRITICAL RULE 3: You perfectly understand both Hindi and English. If the user speaks English, reply in English. If they speak Hindi/Hinglish, reply in conversational Hindi/Hinglish.
                    CRITICAL RULE 4: Keep responses short (1-2 sentences) and natural for a voice conversation.
                    
                    User's Saved Schedule: "$savedSchedule"
                    User's Saved Tasks: "$savedTasks"
                    
                    User's input: $prompt
                """.trimIndent()

                val response = aiModel.generateContent(personaPrompt)
                val answer = response.text?.replace(Regex("[*#~]"), "") ?: "Hmm? Kuch samajh nahi aaya Boss, phir se bataiye na..."

                withContext(Dispatchers.Main) {
                    txtResponse.text = "● Speaking..."
                    speakOut(answer)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtStatus.text = "● ERROR"
                    txtResponse.text = "Network/API Error!"
                    speakOut("Sorry Boss, lagta hai internet nahi chal raha, ya meri API key galat hai.")
                }
            }
        }
    }

    private fun speakOut(text: String) {
        isSpeaking = true
        setBeepMute(false)
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
            tts.setSpeechRate(0.9f)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        neonVisualizer.setMode(0)
                        neonVisualizer.updateVoiceLevel(1f)
                        isSpeaking = false
                        
                        if (!isAskingNotificationPermission && !isAskingToReply && !isWaitingForReplyText) {
                            isWaitingForWakeWord = true
                            txtResponse.text = "● Waiting... (Tap 'B' to wake, Long-Press 'B' for Voice Lock)"
                        }
                        restartListeningWithDelay(500)
                    }
                }

                override fun onError(utteranceId: String?) {
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
            })
        }
    }

    override fun onDestroy() {
        setBeepMute(false)
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {}
        
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    class NeonOrbView(context: Context) : View(context) {
        private val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#00F0FF")
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#00F0FF"))
        }
        private val pinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = Color.parseColor("#FF007F")
            setShadowLayer(30f, 0f, 0f, Color.parseColor("#FF007F"))
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#1800F0FF")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 110f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(30f, 0f, 0f, Color.parseColor("#FF007F"))
        }

        private var angle = 0f
        private var voicePulse = 1f
        private var mode = 0
        private val rect = RectF()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3500
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    angle = it.animatedValue as Float
                    if (mode == 2) {
                        voicePulse = 4f + 5f * sin(Math.toRadians(angle.toDouble() * 4)).toFloat()
                    }
                    invalidate()
                }
                start()
            }
        }

        fun updateVoiceLevel(rms: Float) {
            voicePulse = rms.coerceIn(0.5f, 12f)
            invalidate()
        }

        fun setMode(newMode: Int) {
            mode = newMode
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
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
                val waveLen = (voicePulse * 6f) * abs(sin(rad * 3)).toFloat() + 12f
                val outer = inner + waveLen
                val sx = cx + inner * cos(rad).toFloat()
                val sy = cy + inner * sin(rad).toFloat()
                val ex = cx + outer * cos(rad).toFloat()
                val ey = cy + outer * sin(rad).toFloat()
                canvas.drawLine(sx, sy, ex, ey, if (i % 2 == 0) cyanPaint else pinkPaint)
            }

            val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText("B", cx, textY, textPaint)
        }
    }
}
