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
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.RelativeLayout
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#020205"), Color.parseColor("#0A0B1A"), Color.parseColor("#020205"))
            )
        }

        txtStatus = TextView(this).apply {
            text = "[ SYS: INITIALIZING... ]"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            id = View.generateViewId()
        }

        val statusParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            topMargin = 100
        }
        root.addView(txtStatus, statusParams)

        neonVisualizer = NeonOrbView(this).apply {
            id = View.generateViewId()
            setOnClickListener {
                if (isContinuousMode) {
                    if (isRecording) {
                        stopSeamlessListening()
                        txtStatus.text = "[ SYS: AUDIO LINK DISCONNECTED ]"
                        txtStatus.setTextColor(Color.GRAY)
                        setMode(0) 
                    } else {
                        startSeamlessListening()
                    }
                }
            }
        }

        val orbParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            800 
        ).apply {
            addRule(RelativeLayout.BELOW, txtStatus.id)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            topMargin = 50
        }
        root.addView(neonVisualizer, orbParams)

        txtResponse = TextView(this).apply {
            text = "> SYSTEM READY. WAITING FOR COMMAND..."
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 16f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 20, 40, 20)
            id = View.generateViewId()
        }

        val responseParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.BELOW, neonVisualizer.id)
            topMargin = 50
        }
        root.addView(txtResponse, responseParams)

        setContentView(root)
    }

    private fun processSpokenAudio(wavBytes: ByteArray) {
        txtStatus.text = "[ SYS: PROCESSING AUDIO... ]"
        txtStatus.setTextColor(Color.parseColor("#008B94"))
        neonVisualizer.setMode(1) 
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$geminiApiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                var instruction = "Tu mera smart AI assistant hai 'Butki'. Mera naam Dhruvkumar Dharmendrabhai Vala hai. Main muthoot finance me assistant branch manager hu. Main gujarati samajh sakta hu par hindi bhi theek se janta hu. Main abhi ek android app bana raha hu Butki-Ai name se jisme tu kaam karega. Tu short or seedhi hindi/hinglish me jawab dega."
                
                if (pendingNotificationTitle.isNotEmpty() && isWaitingForReplyText) {
                    instruction = "User abhi is notification ka reply de raha hai: '$pendingNotificationTitle: $pendingNotificationText'. User ne jo abhi bola, usko exactly text format mein convert karo, bina kisi extra explanation ke. Ye text aage send kiya jayega."
                } else if (pendingNotificationTitle.isNotEmpty()) {
                    instruction = "User ko ye notification mila hai: '$pendingNotificationTitle: $pendingNotificationText'. User ne abhi iske baare mein kuch bola. Agar user 'haan', 'padho', ya 'suna de' bole to usko notification content suna do. Agar 'nahi' bole to ignore kar do. Aur agar user sidhe reply ka content bol raha ho, to usko confirm karo."
                }

                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "audio/wav")
                                        put("data", base64Audio)
                                    })
                                })
                            })
                        })
                    })
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", instruction) })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                    })
                }

                val os = DataOutputStream(conn.outputStream)
                os.writeBytes(jsonBody.toString())
                os.flush()
                os.close()

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    var reply = jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    withContext(Dispatchers.Main) {
                        handleAIResponse(reply.trim())
                    }
                } else {
                    val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    withContext(Dispatchers.Main) {
                        txtResponse.text = "> ERROR CONNECTING TO NEURAL CORE: $responseCode"
                        speakOut("Boss, server se connect karne mein problem aa rahi hai.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtResponse.text = "> SYSTEM FAILURE: ${e.message}"
                    speakOut("Boss, kuch error aaya hai.")
                }
            }
        }
    }

    private fun handleAIResponse(reply: String) {
        if (isWaitingForReplyText && pendingNotificationTitle.isNotEmpty()) {
            val replyText = reply
            isWaitingForReplyText = false
            
            val intent = Intent("butki.SEND_REPLY")
            intent.putExtra("reply_text", replyText)
            sendBroadcast(intent)
            
            val confirmMsg = "Maine reply send kar diya hai."
            txtResponse.text = "> REPLY SENT:\n$replyText"
            speakOut(confirmMsg)
            
            pendingNotificationTitle = ""
            pendingNotificationText = ""
            canReplyToCurrentNotification = false
            return
        }
        
        if (pendingNotificationTitle.isNotEmpty()) {
            val lowerReply = reply.lowercase(Locale.getDefault())
            if (lowerReply.contains("haan") || lowerReply.contains("padh") || lowerReply.contains("sun")) {
                val notifMsg = "Message hai: $pendingNotificationText"
                if (canReplyToCurrentNotification) {
                    txtResponse.text = "> NOTIFICATION CONTENT:\n$pendingNotificationText\n\n> AWAITING COMMAND (Reply karna hai?)..."
                    speakOut("$notifMsg. Kya main iska reply karu?")
                    isAskingToReply = true
                } else {
                    txtResponse.text = "> NOTIFICATION CONTENT:\n$pendingNotificationText"
                    speakOut(notifMsg)
                    pendingNotificationTitle = ""
                    pendingNotificationText = ""
                }
            } else if (lowerReply.contains("nahi") || lowerReply.contains("ignore")) {
                 txtResponse.text = "> NOTIFICATION IGNORED"
                 speakOut("Theek hai, ignore kar diya.")
                 pendingNotificationTitle = ""
                 pendingNotificationText = ""
                 canReplyToCurrentNotification = false
                 isAskingToReply = false
            } else if (isAskingToReply && (lowerReply.contains("haan") || lowerReply.contains("kar do") || lowerReply.contains("reply"))) {
                 isAskingToReply = false
                 isWaitingForReplyText = true
                 txtResponse.text = "> READY FOR REPLY RECORDING..."
                 speakOut("Kahiye, kya likhu?")
            }
            else {
                 txtResponse.text = "> COMMAND NOT RECOGNIZED FOR NOTIFICATION"
                 speakOut("Mujhe samajh nahi aaya boss.")
            }
            return
        }

        txtResponse.text = "> RESPONSE:\n$reply"
        speakOut(reply)
    }

    private fun speakOut(text: String) {
        if (::tts.isInitialized) {
            isSpeaking = true
            txtStatus.text = "[ SYS: AUDIO OUT ESTABLISHED ]"
            txtStatus.setTextColor(Color.parseColor("#FFFFFF"))
            neonVisualizer.setMode(2) 
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ButkiVoice")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback
            } else {
                 val voice = Voice("hi-IN-language", Locale("hi", "IN"), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
                 tts.voice = voice
            }
            
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isContinuousMode) {
                        mainHandler.post {
                            startSeamlessListening()
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                }
            })
            
            if (isContinuousMode && !isRecording) {
                 mainHandler.post { startSeamlessListening() }
            }
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        stopSeamlessListening()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}

class NeonOrbView(context: Context) : View(context) {

    private val paintCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGlow1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGlow2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var currentMode = 0 
    private var voiceLevel = 1f 
    
    private var time = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            time += 0.1f
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setMode(mode: Int) {
        this.currentMode = mode
        if (mode == 0) voiceLevel = 1f
    }

    fun updateVoiceLevel(level: Float) {
        this.voiceLevel = level
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 4f)

        when (currentMode) {
            0 -> { 
                paintCore.color = Color.parseColor("#444444")
                paintGlow1.color = Color.TRANSPARENT
                paintGlow2.color = Color.TRANSPARENT
                canvas.drawCircle(cx, cy, baseRadius, paintCore)
            }
            1 -> { 
                val rOffset = (sin(time) * 20f)
                val colorHex = "#008B94" 
                
                paintCore.color = Color.parseColor(colorHex)
                
                paintGlow1.color = Color.parseColor("#44$colorHex".replace("##", "#")) 
                canvas.drawCircle(cx, cy, baseRadius + 30f + rOffset, paintGlow1)
                
                paintGlow2.color = Color.parseColor("#22$colorHex".replace("##", "#"))
                canvas.drawCircle(cx, cy, baseRadius + 60f + (rOffset * 1.5f), paintGlow2)
                
                canvas.drawCircle(cx, cy, baseRadius, paintCore)
            }
            2 -> { 
                val rOffset = (cos(time * 1.5f) * 30f)
                val colorHex = "#FFFFFF" 
                
                paintCore.color = Color.parseColor(colorHex)
                
                paintGlow1.color = Color.parseColor("#66$colorHex".replace("##", "#"))
                canvas.drawCircle(cx, cy, baseRadius + 40f + rOffset, paintGlow1)
                
                canvas.drawCircle(cx, cy, baseRadius, paintCore)
            }
            3 -> { 
                val dynamicRadius = baseRadius * voiceLevel
                val colorHex = "#00F0FF" 
                
                paintCore.color = Color.parseColor(colorHex)
                
                paintGlow1.color = Color.parseColor("#55$colorHex".replace("##", "#"))
                canvas.drawCircle(cx, cy, dynamicRadius + 40f, paintGlow1)
                
                paintGlow2.color = Color.parseColor("#22$colorHex".replace("##", "#"))
                canvas.drawCircle(cx, cy, dynamicRadius + 80f, paintGlow2)
                
                canvas.drawCircle(cx, cy, baseRadius, paintCore)
            }
        }
    }
}
