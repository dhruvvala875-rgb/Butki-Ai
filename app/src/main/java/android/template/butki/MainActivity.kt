package android.template.butki

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var sharedPreferences: SharedPreferences
    private var geminiApiKey: String = ""

    // 🔴 100% SILENT & REAL-TIME GEMINI AUDIO LISTENER (NO BEEP, NO SPEECHRECOGNIZER)
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var isContinuousMode = true 

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    private val silenceThreshold = 650.0 // Aawaz ki sensitivity
    private val maxSilenceMillis = 1200L // 1.2 second chup rehne par sentence complete

    private lateinit var txtResponse: TextView
    private lateinit var txtStatus: TextView
    private lateinit var neonVisualizer: NeonOrbView
    private lateinit var inputMessage: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false

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
                
                if (title.isNotEmpty() && text.isNotEmpty() && !isSpeaking) {
                    stopSeamlessListening()
                    pendingNotificationTitle = title
                    pendingNotificationText = text
                    canReplyToCurrentNotification = canReply
                    
                    isAskingNotificationPermission = true
                    isAskingToReply = false
                    isWaitingForReplyText = false
                    
                    val promptText = "Boss, $title ki taraf se ek message aaya hai. Kya main padh ke sunau?"
                    txtResponse.text = "● Notification from $title\nSay Haan or Nahi."
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
        
        tts = TextToSpeech(this, this, "com.google.android.tts")

        try {
            val filter = IntentFilter("butki.NEW_NOTIFICATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(notificationReceiver, filter)
            }
        } catch (e: Exception) { }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        val savedKey = sharedPreferences.getString("GEMINI_API_KEY", "")
        if (savedKey.isNullOrEmpty()) {
            showApiKeyDialog()
        } else {
            geminiApiKey = savedKey.trim()
            checkNotificationPermission()
        }
    }

    // 🔴 RAW AUDIO AUDIO-RECORD ENGINE (ZERO BEEP, DIRECT GEMINI AUDIO INPUT)
    @SuppressLint("MissingPermission")
    private fun startSeamlessListening() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        mainHandler.post {
            try {
                if (::tts.isInitialized) tts.stop()
                isSpeaking = false

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                val sessionId = audioRecord?.audioSessionId ?: 0
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.enabled = true
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.enabled = true

                audioRecord?.startRecording()
                isRecording = true

                txtStatus.text = "● LISTENING... (SHUDH SUN RAHI HOON)"
                txtStatus.setTextColor(Color.parseColor("#00F0FF"))
                neonVisualizer.setMode(3) // Mode 3 = Live Mic Pulse

                recordingJob = CoroutineScope(Dispatchers.IO).launch {
                    val buffer = ShortArray(bufferSize / 2)
                    val speechStream = ByteArrayOutputStream()
                    var isUserSpeaking = false
                    var lastSpokeTime = System.currentTimeMillis()

                    while (isActive && isRecording) {
                        val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readCount > 0) {
                            var sum = 0.0
                            for (i in 0 until readCount) {
                                sum += buffer[i] * buffer[i]
                            }
                            val rms = sqrt(sum / readCount)

                            withContext(Dispatchers.Main) {
                                val normalizedLevel = (rms / 250.0).toFloat().coerceIn(0.5f, 12f)
                                neonVisualizer.updateVoiceLevel(normalizedLevel)
                            }

                            if (rms > silenceThreshold) {
                                if (!isUserSpeaking) {
                                    isUserSpeaking = true
                                    speechStream.reset()
                                }
                                lastSpokeTime = System.currentTimeMillis()
                            }

                            if (isUserSpeaking) {
                                for (i in 0 until readCount) {
                                    val s = buffer[i].toInt()
                                    speechStream.write(s and 0xFF)
                                    speechStream.write((s shr 8) and 0xFF)
                                }

                                if (System.currentTimeMillis() - lastSpokeTime > maxSilenceMillis) {
                                    isUserSpeaking = false
                                    val finalPcmBytes = speechStream.toByteArray()
                                    speechStream.reset()

                                    if (finalPcmBytes.size > 3200) { // Sirf tabhi bhejo jab aawaz lambi ho
                                        stopSeamlessListening()
                                        val wavBytes = pcmToWav(finalPcmBytes)
                                        withContext(Dispatchers.Main) {
                                            processSpokenAudio(wavBytes)
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                isRecording = false
            }
        }
    }

    private fun stopSeamlessListening() {
        isRecording = false
        try {
            recordingJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }

    private fun pcmToWav(pcmBytes: ByteArray): ByteArray {
        val channels = 1.toShort()
        val bitDepth = 16.toShort()
        val byteRate = (sampleRate * channels * (bitDepth / 8)).toLong()
        val totalDataLen = pcmBytes.size + 36
        val totalAudioLen = pcmBytes.size.toLong()

        val wavFile = ByteArrayOutputStream()
        val output = DataOutputStream(wavFile)

        output.writeBytes("RIFF")
        output.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeInt(Integer.reverseBytes(16))
        output.writeShort(java.lang.Short.reverseBytes(1))
        output.writeShort(java.lang.Short.reverseBytes(channels))
        output.writeInt(Integer.reverseBytes(sampleRate))
        output.writeInt(Integer.reverseBytes(byteRate.toInt()))
        output.writeShort(java.lang.Short.reverseBytes((channels * (bitDepth / 8)).toShort()))
        output.writeShort(java.lang.Short.reverseBytes(bitDepth))
        output.writeBytes("data")
        output.writeInt(Integer.reverseBytes(totalAudioLen.toInt()))
        output.write(pcmBytes)

        return wavFile.toByteArray()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste Gemini API Key here"
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Setup Gemini AI")
            .setMessage("Boss, please enter your Gemini API Key to activate my brain:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save & Start") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    sharedPreferences.edit().putString("GEMINI_API_KEY", key).apply()
                    geminiApiKey = key
                    checkNotificationPermission()
                } else {
                    showApiKeyDialog() 
                }
            }
            .show()
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
            setPadding(40, 70, 40, 40)
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
            text = "● READY! TAP 'B' TO SPEAK"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00FF9D"))
            setShadowLayer(15f, 0f, 0f, Color.parseColor("#00FF9D"))
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 0)
        }

        headerLayout.addView(titleText)
        headerLayout.addView(txtStatus)
        root.addView(headerLayout)

        neonVisualizer = NeonOrbView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(650, 650).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            setOnClickListener {
                if (isRecording) {
                    isContinuousMode = false
                    stopSeamlessListening()
                    txtStatus.text = "● PAUSED. TAP 'B' TO START"
                } else {
                    isContinuousMode = true
                    if (::tts.isInitialized) tts.stop()
                    isSpeaking = false
                    startSeamlessListening()
                }
            }
        }
        root.addView(neonVisualizer)

        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#121225"))
            cornerRadius = 40f
            setStroke(3, Color.parseColor("#00F0FF"))
        }

        val consoleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(40, 35, 40, 35)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        val streamLabel = TextView(this).apply {
            text = "DIRECT AUDIO NEURAL LIVE STREAM"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00F0FF"))
            setPadding(0, 0, 0, 12)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                220
            )
        }

        txtResponse = TextView(this).apply {
            text = "System Ready Boss!\n1. Tap 'B' for zero-beep direct audio chat.\n2. Or type in box and hit SEND!"
            textSize = 15f
            setTextColor(Color.parseColor("#F5F5FF"))
            setLineSpacing(8f, 1.0f)
        }
        scrollView.addView(txtResponse)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E38"))
            cornerRadius = 25f
            setStroke(2, Color.parseColor("#FF007F"))
        }

        inputMessage = EditText(this).apply {
            hint = "Yahan likh kar bhi baat kar sakte hain..."
            setHintTextColor(Color.parseColor("#8888AA"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = editBg
            setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSend = Button(this).apply {
            text = "SEND"
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00F0FF"))
                cornerRadius = 25f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 20 }
            
            setOnClickListener {
                val msg = inputMessage.text.toString().trim()
                if (msg.isNotEmpty()) {
                    stopSeamlessListening()
                    inputMessage.setText("")
                    askButkiText(msg)
                }
            }
        }

        inputRow.addView(inputMessage)
        inputRow.addView(btnSend)

        consoleLayout.addView(streamLabel)
        consoleLayout.addView(scrollView)
        consoleLayout.addView(inputRow)
        root.addView(consoleLayout)

        setContentView(root)
    }

    private fun processSpokenAudio(wavBytes: ByteArray) {
        if (geminiApiKey.isEmpty()) return

        isSpeaking = true
        txtStatus.text = "● BUTKI IS LISTENING & THINKING..."
        txtStatus.setTextColor(Color.parseColor("#FF007F"))
        neonVisualizer.setMode(1)

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val personaPrompt = """
                    You are Butki, a deeply loving, sweet, caring, and playfully naughty female AI companion/girlfriend. 
                    CRITICAL RULE 1: Always address the user as "Boss" affectionately. 
                    CRITICAL RULE 2: NEVER use emojis, asterisks, or special characters.
                    CRITICAL RULE 3: Reply in natural conversational Hinglish/Gujarati/Hindi (1-2 short sentences). Understand user's spoken audio directly without spelling errors.
                    User's Saved Schedule: "$savedSchedule"
                """.trimIndent()

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 15000 
                conn.readTimeout = 15000
                conn.doOutput = true

                val audioInline = JSONObject()
                    .put("mimeType", "audio/wav")
                    .put("data", base64Audio)
                val audioPart = JSONObject().put("inlineData", audioInline)

                val textPart = JSONObject().put("text", personaPrompt)

                val partsArr = JSONArray().put(audioPart).put(textPart)
                val contentObj = JSONObject().put("parts", partsArr)
                val contentsArr = JSONArray().put(contentObj)

                val genConfig = JSONObject()
                    .put("maxOutputTokens", 150)
                    .put("temperature", 0.7)

                val payload = JSONObject()
                    .put("contents", contentsArr)
                    .put("generationConfig", genConfig)

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)
                    
                    val rawAnswer = jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    val answer = rawAnswer.replace(Regex("[*#~]"), "").trim()

                    withContext(Dispatchers.Main) {
                        txtResponse.text = "Butki: \"$answer\""
                        speakOut(answer)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isSpeaking = false
                        neonVisualizer.setMode(0)
                        txtStatus.text = "● API ERROR ($responseCode)"
                        speakOut("Sorry Boss, audio processing mein error aaya.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSpeaking = false
                    neonVisualizer.setMode(0)
                    txtStatus.text = "● NETWORK ERROR"
                    speakOut("Sorry Boss, connection mein kuch dikkat hai.")
                }
            }
        }
    }

    private fun askButkiText(prompt: String) {
        if (geminiApiKey.isEmpty()) return

        isSpeaking = true
        txtStatus.text = "● BUTKI IS THINKING..."
        txtStatus.setTextColor(Color.parseColor("#FF007F"))
        neonVisualizer.setMode(1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val personaPrompt = """
                    You are Butki, a deeply loving, sweet, caring, and playfully naughty female AI companion/girlfriend. 
                    CRITICAL RULE 1: Always address the user as "Boss" affectionately. 
                    CRITICAL RULE 2: NEVER use emojis, asterisks, or special characters.
                    CRITICAL RULE 3: Reply in natural conversational Hinglish/Gujarati (1-2 short sentences).
                    User input: $prompt
                """.trimIndent()

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 10000 
                conn.readTimeout = 10000
                conn.doOutput = true

                val partObj = JSONObject().put("text", personaPrompt)
                val partsArr = JSONArray().put(partObj)
                val contentObj = JSONObject().put("parts", partsArr)
                val contentsArr = JSONArray().put(contentObj)

                val genConfig = JSONObject().put("maxOutputTokens", 120).put("temperature", 0.7)
                val payload = JSONObject().put("contents", contentsArr).put("generationConfig", genConfig)

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)
                    val rawAnswer = jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    val answer = rawAnswer.replace(Regex("[*#~]"), "").trim()

                    withContext(Dispatchers.Main) {
                        txtResponse.text = "Boss: \"$prompt\"\n\nButki: \"$answer\""
                        speakOut(answer)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isSpeaking = false
                        neonVisualizer.setMode(0)
                        txtStatus.text = "● API ERROR"
                        speakOut("Sorry Boss, error aa gaya.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSpeaking = false
                    neonVisualizer.setMode(0)
                    txtStatus.text = "● NETWORK ERROR"
                    speakOut("Sorry Boss, internet slow hai.")
                }
            }
        }
    }

    private fun speakOut(text: String) {
        stopSeamlessListening()
        isSpeaking = true
        txtStatus.text = "● BUTKI IS SPEAKING..."
        txtStatus.setTextColor(Color.parseColor("#00FF9D"))
        neonVisualizer.setMode(2)
        neonVisualizer.updateVoiceLevel(8f)

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "BUTKI_VOICE")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "BUTKI_VOICE")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale("hi", "IN")
            tts.setLanguage(locale)

            try {
                val voices = tts.voices
                if (voices != null) {
                    var bestVoice: Voice? = null
                    for (v in voices) {
                        if (v.locale.language == "hi" && (v.name.lowercase().contains("female") || v.name.lowercase().contains("wavenet"))) {
                            bestVoice = v
                            break
                        }
                    }
                    if (bestVoice != null) tts.voice = bestVoice
                }
            } catch (e: Exception) {}

            tts.setPitch(1.05f)   
            tts.setSpeechRate(0.95f) 

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        neonVisualizer.setMode(0)
                        neonVisualizer.updateVoiceLevel(1f)
                        isSpeaking = false
                        
                        // 🔴 AUTO CONTINUOUS LISTENING: Butki ke bolte hi agla audio sunna shuru!
                        if (isContinuousMode) {
                            mainHandler.postDelayed({
                                startSeamlessListening()
                            }, 400)
                        } else {
                            txtStatus.text = "● READY! TAP 'B' TO SPEAK"
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        txtStatus.text = "● READY! TAP 'B' TO SPEAK"
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        stopSeamlessListening()
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
