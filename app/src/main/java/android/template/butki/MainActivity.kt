package android.template.butki

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private lateinit var generativeModel: GenerativeModel

    private lateinit var txtResponse: TextView
    private lateinit var txtStatus: TextView
    private lateinit var neonVisualizer: NeonOrbView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false
    private var isWaitingForWakeWord = true

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

        setupFuturisticUI()
        tts = TextToSpeech(this, this)

        // API KEY YAHAN DALEIN
        val keyPart1 = "AQ.Ab8RN6" 
        val keyPart2 = "LxUmOiNNoF2b0O5guB6n5gz3vlGSwUcbJOcjT42KJzPA" 
        
        generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = keyPart1 + keyPart2 
        )

        try {
            registerReceiver(notificationReceiver, IntentFilter("butki.NEW_NOTIFICATION"), RECEIVER_EXPORTED)
        } catch (e: Exception) {
            try {
                registerReceiver(notificationReceiver, IntentFilter("butki.NEW_NOTIFICATION"))
            } catch (ex: Exception) { }
        }

        checkNotificationPermission()
        checkPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        val cn = ComponentName(this, ButkiNotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())
        
        if (enabled && ::txtResponse.isInitialized && txtResponse.text.toString().contains("allow kijiye")) {
            txtResponse.text = "● System Ready. Boliye: \"Hey Butki\""
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
            text = "NEURAL AUDIO STREAM //"
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
            text = "System Ready! Pehle boliye: \"Hey Butki\""
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

    private fun setupContinuousSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // Boss, yahan se wo 2.5 second wala delay hata diya hai taaki turant sune.
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isSpeaking) {
                    if (isAskingNotificationPermission || isAskingToReply || isWaitingForReplyText) {
                        txtStatus.text = "● WAITING FOR YOUR ANSWER..."
                    } else if (isWaitingForWakeWord) {
                        txtStatus.text = "● WAITING FOR 'HEY BUTKI'..."
                    } else {
                        txtStatus.text = "● LISTENING TO YOUR COMMAND..."
                    }
                    neonVisualizer.setMode(0)
                }
            }

            override fun onBeginningOfSpeech() {
                if (isWaitingForWakeWord && !isAskingNotificationPermission && !isAskingToReply && !isWaitingForReplyText) {
                    txtStatus.text = "● LISTENING..."
                } else {
                    txtStatus.text = "● RECEIVING COMMAND..."
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isSpeaking) {
                    neonVisualizer.updateVoiceLevel(rmsdB.coerceAtLeast(0f))
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                neonVisualizer.updateVoiceLevel(1f)
            }

            override fun onError(error: Int) {
                neonVisualizer.updateVoiceLevel(1f)
                if (!isSpeaking) {
                    // Agar error aaye (jaise aawaz na samajh aaye) toh turant restart
                    restartListeningWithDelay(100)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var spokenText = ""
                var containsButki = false

                matches?.forEach { match ->
                    val text = match.lowercase()
                    if (spokenText.isEmpty()) spokenText = text 
                    if (text.contains("butki") || text.contains("bootki") || text.contains("bhootki") || text.contains("bhutki") || text.contains("chutki") || text.contains("putki") || text.contains("boot ki") || text.contains("hutki") || text.contains("book ki")) {
                        containsButki = true
                    }
                }

                if (spokenText.isNotEmpty()) {
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
                    else if (isWaitingForWakeWord) {
                        if (containsButki) {
                            isWaitingForWakeWord = false
                            txtResponse.text = "● Awake. Listening to you..."
                            speakOut("Haan boliye Boss, main sun rahi hoon!")
                        } else {
                            restartListeningWithDelay(300)
                        }
                    } 
                    else {
                        txtResponse.text = "Aapne kaha: \"$spokenText\""
                        askButki(spokenText)
                    }
                } else {
                    restartListeningWithDelay(300)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var containsButki = false
                var currentText = ""
                
                matches?.forEach { match ->
                    val text = match.lowercase()
                    if (currentText.isEmpty()) currentText = text
                    if (text.contains("butki") || text.contains("bootki") || text.contains("bhootki") || text.contains("bhutki") || text.contains("chutki") || text.contains("putki") || text.contains("boot ki") || text.contains("hutki") || text.contains("book ki")) {
                        containsButki = true
                    }
                }

                // Yahan screen par live typing dikhegi taaki pata chale Butki kya sun rahi hai
                if (currentText.isNotEmpty() && !isSpeaking) {
                    txtResponse.text = "Sun rahi hoon: \"$currentText...\""
                }

                if (!isSpeaking && !isAskingNotificationPermission && !isAskingToReply && !isWaitingForReplyText && isWaitingForWakeWord) {
                    if (containsButki) {
                        speechRecognizer?.stopListening()
                        isWaitingForWakeWord = false
                        txtResponse.text = "● Awake. Listening to you..."
                        speakOut("Haan boliye Boss!")
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListeningLoop()
    }

    private fun startListeningLoop() {
        if (!isSpeaking) {
            try {
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                restartListeningWithDelay(200)
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
        isSpeaking = true
        speechRecognizer?.stopListening()
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

                val response = generativeModel.generateContent(personaPrompt)
                val answer = response.text?.replace(Regex("[*#~]"), "") ?: "Hmm? Kuch samajh nahi aaya Boss, phir se bataiye na..."

                withContext(Dispatchers.Main) {
                    txtResponse.text = "● Speaking..."
                    speakOut(answer)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtStatus.text = "● ERROR"
                    txtResponse.text = "Network/API Error!"
                    speakOut("Sorry Boss, lagta hai internet nahi chal raha, ya meri API key expire ho gayi hai.")
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
                            txtResponse.text = "● Waiting in background..."
                        }
                        restartListeningWithDelay(200)
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
                        restartListeningWithDelay(200)
                    }
                }
            })
        }
    }

    override fun onDestroy() {
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
                val waveLen = (voicePulse * 6f) * kotlin.math.abs(sin(rad * 3)).toFloat() + 12f
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
