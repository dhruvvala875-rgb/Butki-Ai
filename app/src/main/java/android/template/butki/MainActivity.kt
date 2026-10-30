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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var generativeModel: GenerativeModel? = null 
    private lateinit var sharedPreferences: SharedPreferences

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
                    pendingNotificationTitle = title
                    pendingNotificationText = text
                    canReplyToCurrentNotification = canReply
                    
                    isAskingNotificationPermission = true
                    isAskingToReply = false
                    isWaitingForReplyText = false
                    
                    val promptText = "Boss, $title ki taraf se ek message aaya hai. Kya main padh ke sunau? Jawab dene ke liye B par tap karein."
                    txtResponse.text = "● Notification from $title\nTap 'B' to say Haan or Nahi."
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

        val savedGeminiKey = sharedPreferences.getString("GEMINI_API_KEY", "")
        if (savedGeminiKey.isNullOrEmpty()) {
            showGeminiApiKeyDialog()
        } else {
            setupGeminiAI(savedGeminiKey)
            checkTinyFishKey()
        }
    }

    private fun showGeminiApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste Gemini API Key here"
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Setup Gemini AI")
            .setMessage("Boss, please enter your Gemini API Key:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    sharedPreferences.edit().putString("GEMINI_API_KEY", key).apply()
                    setupGeminiAI(key)
                    checkTinyFishKey()
                } else {
                    showGeminiApiKeyDialog() 
                }
            }
            .show()
    }

    private fun checkTinyFishKey() {
        val savedTinyKey = sharedPreferences.getString("TINYFISH_API_KEY", "")
        if (savedTinyKey.isNullOrEmpty()) {
            val input = EditText(this).apply {
                hint = "Paste TinyFish API Key here"
                setPadding(40, 40, 40, 40)
            }

            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Setup TinyFish Agent")
                .setMessage("Boss, please enter your TinyFish API Key for web tasks:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Save & Start") { _, _ ->
                    val key = input.text.toString().trim()
                    if (key.isNotEmpty()) {
                        sharedPreferences.edit().putString("TINYFISH_API_KEY", key).apply()
                        checkNotificationPermission()
                    } else {
                        checkTinyFishKey()
                    }
                }
                .show()
        } else {
            checkNotificationPermission()
        }
    }

    private fun setupGeminiAI(apiKey: String) {
        generativeModel = GenerativeModel(
            modelName = "gemini-3.8-flash",
            apiKey = apiKey.trim() 
        )
    }

    // 🔴 TINYFISH WEB AGENT INTEGRATION (USING BUILT-IN HttpURLConnection)
    private fun callTinyFishAgent(targetUrl: String, userGoal: String) {
        val tinyFishApiKey = sharedPreferences.getString("TINYFISH_API_KEY", "") ?: ""
        if (tinyFishApiKey.isEmpty()) {
            checkTinyFishKey()
            return
        }

        isSpeaking = true
        txtStatus.text = "● TINYFISH WORKING..."
        neonVisualizer.setMode(1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://agent.tinyfish.ai/v1/automate")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("X-API-Key", tinyFishApiKey)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                }

                val jsonBody = JSONObject().apply {
                    put("url", targetUrl)
                    put("goal", userGoal)
                }

                conn.outputStream.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                conn.outputStream.flush()

                val responseCode = conn.responseCode
                val responseStream = if (responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }

                val responseString = BufferedReader(InputStreamReader(responseStream)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    sb.toString()
                }

                withContext(Dispatchers.Main) {
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        txtResponse.text = "✅ TinyFish Result:\n$responseString"
                        speakOut("Boss, TinyFish ne web data nikal liya hai.")
                    } else {
                        txtResponse.text = "❌ TinyFish Error ($responseCode):\n$responseString"
                        speakOut("Boss, TinyFish task mein error aa gaya.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtResponse.text = "❌ TinyFish Connection Failed: ${e.localizedMessage}"
                    speakOut("Sorry Boss, TinyFish se connect nahi ho paya.")
                }
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
                sharedPreferences.edit().clear().apply()
                showGeminiApiKeyDialog()
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
                if (::tts.isInitialized) tts.stop()
                isSpeaking = false
                startCleanPopupMic()
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
            text = "NEURAL STREAM // TAP 'B' OR TYPE BELOW"
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
            text = "System Ready Boss!\n1. Bolne ke liye beech wale 'B' par Tap karein.\n2. Ya niche box mein type karke Send dabayein!"
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
                    inputMessage.setText("")
                    processSpokenText(msg.lowercase())
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

    private fun startCleanPopupMic() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye Boss, Butki sun rahi hai...")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 200)
        } catch (e: Exception) {
            Toast.makeText(this, "Mic Error! Niche box me type karke baat karein.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK && data != null) {
            val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.trim() ?: ""
            
            if (spokenText.isNotEmpty()) {
                processSpokenText(spokenText.lowercase())
            }
        }
    }

    private fun processSpokenText(spokenText: String) {
        if (isAskingNotificationPermission) {
            if (spokenText.contains("haan") || spokenText.contains("yes") || spokenText.contains("padh") || spokenText.contains("sunao")) {
                isAskingNotificationPermission = false
                if (canReplyToCurrentNotification) {
                    isAskingToReply = true
                    txtResponse.text = "Msg: \"$pendingNotificationText\"\n(Tap 'B' to say Haan/Nahi for reply)"
                    speakOut("Message hai: $pendingNotificationText. Kya iska reply karna hai Boss?")
                } else {
                    txtResponse.text = "Msg: \"$pendingNotificationText\""
                    speakOut("Message hai: $pendingNotificationText.")
                }
            } else {
                isAskingNotificationPermission = false
                txtResponse.text = "● Notification Ignored."
                speakOut("Theek hai Boss, main ise ignore kar rahi hoon.")
            }
        } 
        else if (isAskingToReply) {
            if (spokenText.contains("haan") || spokenText.contains("yes") || spokenText.contains("karo") || spokenText.contains("bhej")) {
                isAskingToReply = false
                isWaitingForReplyText = true
                txtResponse.text = "● Tap 'B' and speak your reply message..."
                speakOut("Bataiye Boss, kya reply bhejna hai? B par tap karke boliye.")
            } else {
                isAskingToReply = false
                txtResponse.text = "● Reply Cancelled."
                speakOut("Theek hai Boss, koi reply nahi bhej rahi.")
            }
        }
        else if (isWaitingForReplyText) {
            isWaitingForReplyText = false
            val success = ButkiNotificationService.sendReply(this@MainActivity, spokenText)
            if (success) {
                txtResponse.text = "✅ Sent Reply: \"$spokenText\""
                speakOut("Message bhej diya Boss!")
            } else {
                txtResponse.text = "❌ Reply Failed."
                speakOut("Sorry Boss, reply nahi ja saka.")
            }
        } 
        else if (spokenText.contains("search web") || spokenText.contains("web search") || spokenText.contains("extract")) {
            txtResponse.text = "TinyFish web agent task shuru kar rahi hai..."
            speakOut("Theek hai Boss, web se data nikal rahi hoon.")
            callTinyFishAgent("https://google.com", spokenText)
        }
        else {
            txtResponse.text = "Boss: \"$spokenText\"\nButki soch rahi hai..."
            askButki(spokenText)
        }
    }

    private fun askButki(prompt: String) {
        val aiModel = generativeModel
        if (aiModel == null) {
            txtResponse.text = "API Key missing!"
            speakOut("Boss, meri API key nahi mil rahi hai.")
            return
        }

        isSpeaking = true
        txtStatus.text = "● BUTKI IS THINKING..."
        neonVisualizer.setMode(1)

        val lowerPrompt = prompt.lowercase()
        if (lowerPrompt.contains("schedule") || lowerPrompt.contains("time table") || lowerPrompt.contains("routine")) {
            savedSchedule = prompt
        } else if (lowerPrompt.contains("task") || lowerPrompt.contains("kaam")) {
            savedTasks.add(prompt)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val personaPrompt = """
                    You are Butki, a deeply loving, sweet, caring, and playfully naughty female AI companion/girlfriend. 
                    CRITICAL RULE 1: Always address the user as "Boss" affectionately. 
                    CRITICAL RULE 2: NEVER use emojis, asterisks, or special characters.
                    CRITICAL RULE 3: Reply in conversational Hindi/Hinglish (1-2 short sentences).
                    User's Saved Schedule: "$savedSchedule"
                    User's input: $prompt
                """.trimIndent()

                val response = aiModel.generateContent(personaPrompt)
                val answer = response.text?.replace(Regex("[*#~]"), "") ?: "Kuch samajh nahi aaya Boss, phir se boliye na."

                withContext(Dispatchers.Main) {
                    txtResponse.text = "Boss: \"$prompt\"\n\nButki: \"$answer\""
                    speakOut(answer)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtStatus.text = "● API / NETWORK ERROR"
                    txtResponse.text = "Error: ${e.localizedMessage}\n(Title 'BUTKI A.I.' par long-press karke nayi API key daalein)"
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
            val result = tts.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.getDefault()
            }

            // 🔴 SOFT & CARING FEMALE VOICE TUNING
            tts.setPitch(1.10f)   
            tts.setSpeechRate(0.92f) 

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        neonVisualizer.setMode(0)
                        neonVisualizer.updateVoiceLevel(1f)
                        isSpeaking = false
                        txtStatus.text = "● READY! TAP 'B' TO SPEAK"
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
