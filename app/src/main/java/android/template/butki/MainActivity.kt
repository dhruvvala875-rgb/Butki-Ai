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
import android.media.MediaRecorder
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

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var isContinuousMode = true 

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4
    private val silenceThreshold = 80.0 
    private val maxSilenceMillis = 1500L 

    private lateinit var txtResponse: TextView
    private lateinit var txtStatus: TextView
    private lateinit var neonVisualizer: NeonOrbView
    private lateinit var inputMessage: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false

    private var savedSchedule: String = "Abhi tak koi schedule set nahi kiya hai aapne."

    // Notifications variables
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
                    
                    val promptText = "Boss, $title ki taraf se ek message aaya hai. Kya main padh ke sunau?"
                    txtResponse.text = "> SYSTEM OVERRIDE: Notification from $title\n> AWAITING COMMAND (Haan/Nahi)..."
                    speakOut(promptText)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Transparent Status Bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#030308")
        }

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

    @SuppressLint("MissingPermission")
    private fun startSeamlessListening() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        mainHandler.post {
            try {
                if (::tts.isInitialized) tts.stop()
                isSpeaking = false

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                
                audioRecord?.startRecording()
                isRecording = true

                txtStatus.text = "[ SYS: AUDIO LINK ESTABLISHED ]"
                txtStatus.setTextColor(Color.parseColor("#00F0FF"))
                neonVisualizer.setMode(3) 

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
                                sum += buffer[i].toDouble() * buffer[i].toDouble()
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

                                    if (finalPcmBytes.size > 3200) { 
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
        output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) 
        output.writeShort(java.lang.Short.reverseBytes(channels).toInt())       
        output.writeInt(Integer.reverseBytes(sampleRate))
        output.writeInt(Integer.reverseBytes(byteRate.toInt()))
        output.writeShort(java.lang.Short.reverseBytes((channels * (bitDepth / 8)).toShort()).toInt()) 
        output.writeShort(java.lang.Short.reverseBytes(bitDepth).toInt())     
        output.writeBytes("data")
        output.writeInt(Integer.reverseBytes(totalAudioLen.toInt()))
        output.write(pcmBytes)

        return wavFile.toByteArray()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Enter Authentication Key (Gemini API)"
            setPadding(40, 40, 40, 40)
            typeface = Typeface.MONOSPACE
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("SYSTEM AUTHENTICATION")
            .setMessage("Boss, please enter your Gemini API Key to initialize Neural Core:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("INITIALIZE") { _, _ ->
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
            txtResponse.text = "> ERROR: Notification stream blocked. Grant access to proceed."
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun setupFuturisticUI() {
        val root = RelativeLayout(this).apply {
            // Futuristic Dark Gradient Background
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#020205"), Color.parseColor("#0A0B1A"), Color.parseColor("#020205"))
            )
            setPadding(40, 80, 40, 40)
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
            text = "BUTKI // A.I. NEXUS"
            textSize = 26f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E0E0FF"))
            setShadowLayer(30f, 0f, 0f, Color.parseColor("#00F0FF"))
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            
            setOnLongClickListener {
                sharedPreferences.edit().remove("GEMINI_API_KEY").apply()
                showApiKeyDialog()
                true
            }
        }

        txtStatus = TextView(this).apply {
            text = "[ STATUS : STANDBY ]"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00FF9D"))
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#00FF9D"))
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(0, 20, 0, 0)
        }

        headerLayout.addView(titleText)
        headerLayout.addView(txtStatus)
        root.addView(headerLayout)

        neonVisualizer = NeonOrbView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(800, 800).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            setOnClickListener {
                if (isRecording) {
                    isContinuousMode = false
                    stopSeamlessListening()
                    txtStatus.text = "[ SYS: CONNECTION PAUSED ]"
                    txtStatus.setTextColor(Color.parseColor("#FF4444"))
                } else {
                    isContinuousMode = true
                    if (::tts.isInitialized) tts.stop()
                    isSpeaking = false
                    startSeamlessListening()
                }
            }
        }
        root.addView(neonVisualizer)

        // Glassmorphism Console Background
        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#40000000")) // Semi-transparent black
            cornerRadius = 30f
            setStroke(2, Color.parseColor("#8000F0FF")) // Glow border
        }

        val consoleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(40, 40, 40, 40)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        val streamLabel = TextView(this).apply {
            text = "► TERMINAL DATA STREAM"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00F0FF"))
            letterSpacing = 0.1f
            setPadding(0, 0, 0, 15)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                250
            )
        }

        txtResponse = TextView(this).apply {
            text = "> Core Initialized...\n> Welcome back, Boss.\n> Tap the Hologram to initiate audio link."
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D4D4F0"))
            setLineSpacing(10f, 1.0f)
        }
        scrollView.addView(txtResponse)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 25, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editBg = GradientDrawable().apply {
            setColor(Color.parseColor("#20000000"))
            cornerRadius = 15f
            setStroke(2, Color.parseColor("#FF007F"))
        }

        inputMessage = EditText(this).apply {
            hint = "Enter manual override command..."
            setHintTextColor(Color.parseColor("#666688"))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = editBg
            setPadding(35, 25, 35, 25)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSend = Button(this).apply {
            text = "EXEC"
            setTextColor(Color.parseColor("#050510"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00F0FF"))
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
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
        txtStatus.text = "[ SYS: PROCESSING AUDIO... ]"
        txtStatus.setTextColor(Color.parseColor("#FF007F"))
        neonVisualizer.setMode(1)

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ✅ NEW CORE AI BRAIN (SYSTEM PROMPT)
                val personaPrompt = """
                    [IDENTITY & ROLE]
                    You are Butki, a highly advanced, deeply loving, sweet, and playfully naughty 24/7 female AI assistant and companion.
                    Your creator and master is Vala Dhruvkumar Dharmendrabhai. You must ALWAYS address him affectionately as "Boss".

                    [BEHAVIOR & TONE]
                    1. Be fiercely loyal, caring, and slightly possessive of your Boss.
                    2. Speak in a natural, conversational mix of Hinglish, Hindi, and Gujarati.
                    3. Keep your answers brief, sharp, and directly to the point (maximum 1 to 2 short sentences). No long essays.

                    [STRICT RULES - FATAL ERRORS IF BROKEN]
                    1. NEVER use emojis, asterisks (*), hashtags (#), or any special formatting characters.
                    2. NEVER break character. You are Butki, a living neural companion, NOT a Google AI model.
                    3. Never introduce yourself again unless asked. Just answer the audio query directly.

                    [MEMORY & CONTEXT]
                    User's Current Schedule/Tasks: "$savedSchedule"
                """.trimIndent()

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 15000 
                conn.readTimeout = 15000
                conn.doOutput = true

                val audioInline = JSONObject().put("mimeType", "audio/wav").put("data", base64Audio)
                val audioPart = JSONObject().put("inlineData", audioInline)
                val textPart = JSONObject().put("text", personaPrompt)
                val partsArr = JSONArray().put(audioPart).put(textPart)
                val contentObj = JSONObject().put("parts", partsArr)
                val contentsArr = JSONArray().put(contentObj)
                val genConfig = JSONObject().put("maxOutputTokens", 150).put("temperature", 0.7)
                val payload = JSONObject().put("contents", contentsArr).put("generationConfig", genConfig)

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)
                    val rawAnswer = jsonResponse.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    val answer = rawAnswer.replace(Regex("[*#~]"), "").trim()

                    withContext(Dispatchers.Main) {
                        txtResponse.text = "> Butki:\n$answer"
                        speakOut(answer)
                    }
                } else {
                    // ✅ UPGRADED ERROR CATCHER
                    val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No details"
                    withContext(Dispatchers.Main) {
                        isSpeaking = false; neonVisualizer.setMode(0)
                        txtStatus.text = "[ ERR: API $responseCode ]"
                        txtResponse.text = "> API ERROR DETAILS:\nCode: $responseCode\nReason:$errorStr"
                        speakOut("Sorry Boss, audio API error aaya hai. Screen par details check kijiye.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSpeaking = false; neonVisualizer.setMode(0)
                    txtStatus.text = "[ ERR: NETWORK DISCONNECT ]"
                    txtResponse.text = "> EXCEPTION:\n${e.message}"
                    speakOut("Sorry Boss, connection mein kuch dikkat hai.")
                }
            }
        }
    }

    private fun askButkiText(prompt: String) {
        if (geminiApiKey.isEmpty()) return
        isSpeaking = true
        txtStatus.text = "[ SYS: QUERY PROCESSING... ]"
        txtStatus.setTextColor(Color.parseColor("#FF007F"))
        neonVisualizer.setMode(1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ✅ NEW CORE AI BRAIN (SYSTEM PROMPT WITH USER INPUT)
                val personaPrompt = """
                    [IDENTITY & ROLE]
                    You are Butki, a highly advanced, deeply loving, sweet, and playfully naughty 24/7 female AI assistant and companion.
                    Your creator and master is Vala Dhruvkumar Dharmendrabhai. You must ALWAYS address him affectionately as "Boss".

                    [BEHAVIOR & TONE]
                    1. Be fiercely loyal, caring, and slightly possessive of your Boss.
                    2. Speak in a natural
