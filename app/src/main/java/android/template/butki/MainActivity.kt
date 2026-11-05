package com.deva.voice

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.*
import com.deva.voice.v2.AgentService
import com.deva.voice.utilities.FreemiumManager
import com.deva.voice.utilities.Logger
import com.deva.voice.utilities.OnboardingManager
import com.deva.voice.utilities.PermissionManager
import com.deva.voice.utilities.UserIdManager
import com.deva.voice.utilities.UserProfileManager
import com.deva.voice.utilities.VideoAssetManager
import com.deva.voice.utilities.WakeWordManager
import com.deva.voice.utilities.DeVAState
import com.deva.voice.utilities.DeVAStateManager
import com.deva.voice.utilities.DeltaStateColorMapper
import com.deva.voice.views.DeltaSymbolView
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.File

class MainActivity : BaseNavigationActivity() {

    private lateinit var handler: Handler
    private lateinit var managePermissionsButton: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var userId: String
    private lateinit var permissionManager: PermissionManager
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var auth: FirebaseAuth
    private lateinit var tasksLeftTag: View
    private lateinit var freemiumManager: FreemiumManager
    private lateinit var wakeWordHelpLink: TextView
    private lateinit var increaseLimitsLink: TextView
    private lateinit var onboardingManager: OnboardingManager
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    private lateinit var billingStatusTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var devaStateManager: DeVAStateManager
    private lateinit var stateChangeListener: (DeVAState) -> Unit
    private lateinit var proSubscriptionTag: View
    private lateinit var permissionsTag: View
    private lateinit var permissionsStatusTag: TextView
    private lateinit var tasksLeftText: TextView
    private lateinit var deltaSymbol: DeltaSymbolView

    companion object {
        const val ACTION_WAKE_WORD_FAILED = "com.deva.voice.WAKE_WORD_FAILED"
        const val ACTION_PURCHASE_UPDATED = "com.deva.voice.PURCHASE_UPDATED"
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            com.deva.voice.services.ScreenCaptureService.resultCode = result.resultCode
            com.deva.voice.services.ScreenCaptureService.projectionIntent = result.data!!
            
            startOverlayService()
            Toast.makeText(this, "Butki Vision Ready! Tap the floating icon.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCaptureWithMediaProjection()
        } else {
            Toast.makeText(this, "Overlay permission is required for Butki Vision", Toast.LENGTH_LONG).show()
        }
    }
    
    private val wakeWordFailureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_WAKE_WORD_FAILED) {
                Logger.d("MainActivity", "Received wake word failure broadcast.")
                updateUI()
                showWakeWordFailureDialog()
            }
        }
    }
    
    private val purchaseUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PURCHASE_UPDATED) {
                Logger.d("MainActivity", "Received purchase update broadcast.")
                showLoading(true)
                performBillingCheck()
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this
