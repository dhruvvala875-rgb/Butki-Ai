package android.template.butki

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.template.butki.utilities.ButkiState
import android.template.butki.utilities.DeVAStateManager
import android.template.butki.utilities.DeltaStateColorMapper
import android.template.butki.utilities.Logger
import android.template.butki.v2.AgentService
import android.template.butki.views.DeltaSymbolView

class MainActivity : BaseNavigationActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var deltaSymbol: DeltaSymbolView
    private lateinit var devaStateManager: DeVAStateManager
    private lateinit var stateChangeListener: (ButkiState) -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_content)

        statusTextView = findViewById(R.id.status_text)
        deltaSymbol = findViewById(R.id.delta_symbol)

        // Set Default Assistant Button Click
        findViewById<TextView>(R.id.btn_set_default_assistant)?.setOnClickListener {
            Toast.makeText(this, "Butki-Ai is ready for action!", Toast.LENGTH_SHORT).show()
        }

        initializeStateManager()
        
        // Tap on Delta symbol to trigger command
        deltaSymbol.setOnClickListener {
            if (devaStateManager.getCurrentState() == ButkiState.IDLE) {
                devaStateManager.setState(ButkiState.THINKING)
                AgentService.start(this, "Hello Butki")
                
                statusTextView.text = "[ SYS: BUTKI-AI ACTIVE ]"
                deltaSymbol.startGlow()
            } else {
                devaStateManager.setState(ButkiState.IDLE)
                deltaSymbol.stopGlow()
                statusTextView.text = "[ SYS: IDLE - TAP TO WAKE ]"
            }
        }
    }

    private fun initializeStateManager() {
        // Mocking state manager for clean integration
        devaStateManager = DeVAStateManager.getInstance(this)
        updateDeltaVisuals(ButkiState.IDLE)
    }

    private fun updateDeltaVisuals(state: ButkiState) {
        val color = DeltaStateColorMapper.getColor(this, state)
        deltaSymbol.setColor(color)
        if (DeltaStateColorMapper.isActiveState(state)) {
            deltaSymbol.startGlow()
        } else {
            deltaSymbol.stopGlow()
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.d("MainActivity", "Butki-Ai resumed successfully.")
    }
}
