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
    private var generativeModel: GenerativeModel? = null 
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var audioManager: AudioManager

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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupFuturisticUI()
        tts = TextToSpeech(this, this)
        
        sharedPreferences = getSharedPreferences("ButkiPrefs", Context.MODE_PRIVATE)

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
            txtResponse.text = "● System Ready. Boliye \"Hey Butki\" ya 'B' par tap karein!"
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
            // 🔴 NAYA FEATURE: Orb ('B') par tap karke direct baat karein (Bina Wake Word ke)
            setOnClickListener {
                if (!isSpeaking) {
                    isWaitingForWakeWord = false
                    txtResponse.text = "● Awake. Boliye Boss, main sun rahi hoon..."
                    speakOut("Haan boliye Boss!")
                }
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
            text = "System Ready! Boliye \"Hey Butki\" ya beech wale 'B' par tap karein!"
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
        
