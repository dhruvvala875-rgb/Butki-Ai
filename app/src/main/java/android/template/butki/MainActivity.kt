package android.template.butki

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
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
    private val isContinuousMode = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private var bufferSize = 2048
    private val silenceThreshold = 80.0 
    private val maxSilenceMillis = 1500L 

    private lateinit var txtResponse: TextView
    private lateinit var txtStatus: TextView
    private lateinit var neonVisualizer: NeonOrbView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#030308")
        }

        sharedPreferences = getSharedPreferences("ButkiPrefs", Context.MODE_PRIVATE)

        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf > 0) {
                bufferSize = minBuf * 2
            }
        } catch (e: Exception) {
            bufferSize = 4096
        }

        setupFuturisticUI()
        
        try {
            tts = TextToSpeech(this, this, "com.google.android.tts")
        } catch (e: Exception) {}

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        val savedKey = sharedPreferences.getString("GEMINI_API_KEY", "")
        if (savedKey.isNullOrEmpty()) {
            showApiKeyDialog()
        } else {
            geminiApiKey = savedKey.trim()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSeamlessListening() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            txtStatus.text = "[ SYS: MIC PERMISSION REQUIRED ]"
            return
        }
        if (geminiApiKey.isEmpty()) {
            showApiKeyDialog()
            return
        }

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
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    txtStatus.text = "[ SYS: MIC INIT FAILED ]"
                    return@post
                }

                audioRecord?.startRecording()
                isRecording = true

                txtStatus.text = "[ SYS: LISTENING... (TAP ORB TO STOP) ]"
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
                                val valD = buffer[i].toDouble()
                                sum += valD * valD
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
                mainHandler.post {
                    txtStatus.text = "[ SYS: AUDIO ERROR ]"
                }
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
            hint = "Enter Gemini API Key"
            setPadding(40, 40, 40, 40)
            typeface = Typeface.MONOSPACE
        }

        AlertDialog.Builder(this)
            .setTitle("BUTKI-AI AUTHENTICATION")
            .setMessage("Boss, please enter your Gemini API Key to initialize:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("INITIALIZE") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    sharedPreferences.edit().putString("GEMINI_API_KEY", key).apply()
                    geminiApiKey = key
                    txtStatus.text = "[ SYS: IDLE - TAP ORB TO SPEAK ]"
                } else {
                    showApiKeyDialog() 
                }
            }
            .show()
    }

    private fun setupFuturisticUI() {
        val root = RelativeLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#020205"), Color.parseColor("#0A0B1A"), Color.parseColor("#020205"))
            )
        }

        txtStatus = TextView(this).apply {
            text = "[ SYS: TAP ORB TO START ]"
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
                if (isRecording) {
                    stopSeamlessListening()
                    txtStatus.text = "[ SYS: PAUSED ]"
                    txtStatus.setTextColor(Color.GRAY)
                    setMode(0) 
                } else {
                    startSeamlessListening()
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
            text = "> SYSTEM READY. TAP ORB TO SPEAK..."
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
        txtStatus.text = "[ SYS: THINKING... ]"
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

                val instruction = "Tu mera smart AI assistant hai 'Butki'. Mera naam Dhruvkumar Dharmendrabhai Vala hai. Main gujarati samajh sakta hu par hindi bhi theek se janta hu. Main abhi ek android app bana raha hu Butki-Ai name se jisme tu kaam karega. Tu short or seedhi hindi/hinglish me jawab dega."
                
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
                    val reply = jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        
