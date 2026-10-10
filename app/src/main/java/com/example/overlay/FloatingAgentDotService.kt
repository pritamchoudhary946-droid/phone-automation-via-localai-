package com.example.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.example.OmniAgentApplication
import com.example.accessibility.AgentAccessibilityService
import com.example.agent.AgentAction
import com.example.agent.OrchestratorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class FloatingAgentDotService : Service() {

    companion object {
        private const val TAG = "FloatingAgentDot"
        var isRunning = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            try {
                val intent = Intent(context, FloatingAgentDotService::class.java)
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Android 8+ blocks background startService; the caller is expected to be
                // in the foreground. Swallow instead of crashing the host app.
                Log.w(TAG, "Could not start floating dot service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingAgentDotService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var dotView: View? = null
    private var panelView: View? = null

    private lateinit var dotParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var isPanelOpen = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    // Touch & Drag Tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var downTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initFloatingDot()
        initExpandedPanel()
        observeAgentState()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    private fun vibrateBrief() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        } catch (_: Exception) {}
    }

    private fun initFloatingDot() {
        val size = dpToPx(58f)

        dotParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dpToPx(16f)
            y = dpToPx(200f)
        }

        val frame = FrameLayout(this)
        frame.layoutParams = FrameLayout.LayoutParams(size, size)

        // Glassy animated AI orb (OpenAI/ChatGPT-style dot).
        val orb = AgentOrbView(this)
        frame.addView(orb, FrameLayout.LayoutParams(size, size))

        // State indicator badge (top right)
        val badge = View(this).apply {
            val badgeSize = dpToPx(13f)
            val badgeDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#12B886")) // Mint success green
                setStroke(dpToPx(1.5f), Color.WHITE)
            }
            background = badgeDrawable
            tag = "status_badge"
            val badgeParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(1f)
                rightMargin = dpToPx(1f)
            }
            layoutParams = badgeParams
        }
        frame.addView(badge)

        // Touch Listener for Drag & Drop, Tap, Double Tap, and Long Press
        frame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = dotParams.x
                    initialY = dotParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    val newX = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, screenWidth - size)
                    val newY = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, screenHeight - size)
                    dotParams.x = newX
                    dotParams.y = newY
                    try {
                        windowManager?.updateViewLayout(dotView, dotParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (isPanelOpen) {
                        updatePanelPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    val duration = System.currentTimeMillis() - downTime

                    if (deltaX < 18 && deltaY < 18) {
                        vibrateBrief()
                        if (duration > 650) {
                            // Long Press: Direct Voice Command Listening
                            onLongPress()
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 350) {
                                // Double Tap: Instant Screen Analysis & Readout
                                onDoubleTap()
                            } else {
                                // Single Tap: Toggle Floating Command & Control Panel
                                onSingleTap()
                            }
                            lastTapTime = now
                        }
                    }
                    true
                }
                else -> false
            }
        }

        dotView = frame
        windowManager?.addView(dotView, dotParams)
    }

    private fun initExpandedPanel() {
        val panelWidth = dpToPx(320f)

        panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(14f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF")) // Clean light surface
                cornerRadius = dpToPx(20f).toFloat()
                setStroke(dpToPx(1.5f), Color.parseColor("#E1E4F0"))
            }
        }

        // Outside touch dismiss
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hidePanel()
                true
            } else {
                false
            }
        }

        // Header: Title & Close Button
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "Jarvis"
            setTextColor(Color.parseColor("#4A46D6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#1B1C33"))
            val p = dpToPx(4f)
            setPadding(p, p, p, p)
            setOnClickListener { hidePanel() }
        }

        headerLayout.addView(titleText)
        headerLayout.addView(closeBtn)
        container.addView(headerLayout)

        // Status Banner Text
        val statusText = TextView(this).apply {
            text = "Ready • Gol dot se phone control karein"
            setTextColor(Color.parseColor("#5A5F78"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dpToPx(4f), 0, dpToPx(10f))
            tag = "panel_status_text"
        }
        container.addView(statusText)

        // Voice Command & Text Input Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val editInput = EditText(this).apply {
            hint = "Bol ke ya type karke command dein..."
            setHintTextColor(Color.parseColor("#9AA0B6"))
            setTextColor(Color.parseColor("#1B1C33"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F3FA"))
                cornerRadius = dpToPx(10f).toFloat()
                setStroke(dpToPx(1f), Color.parseColor("#E1E4F0"))
            }
            val editPad = dpToPx(10f)
            setPadding(editPad, editPad, editPad, editPad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_GO
            tag = "command_edit_text"
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    val cmd = text.toString().trim()
                    if (cmd.isNotEmpty()) {
                        handlePhoneOrAgentCommand(cmd)
                        text.clear()
                        hidePanel()
                    }
                    true
                } else false
            }
        }

        val micBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor("#4A46D6"))
            val micPad = dpToPx(7f)
            setPadding(micPad, micPad, micPad, micPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E3E2FF"))
            }
            tag = "panel_mic_button"
            setOnClickListener {
                toggleVoiceListening(editInput)
            }
        }

        val sendBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            val btnPad = dpToPx(6f)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4A46D6"))
            }
            val ml = dpToPx(6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(34f), dpToPx(34f)).apply {
                setMargins(ml, 0, 0, 0)
            }
            setOnClickListener {
                val command = editInput.text.toString().trim()
                if (command.isNotEmpty()) {
                    handlePhoneOrAgentCommand(command)
                    editInput.text.clear()
                    hidePanel()
                }
            }
        }

        inputRow.addView(editInput)
        val space = View(this).apply { layoutParams = LinearLayout.LayoutParams(dpToPx(6f), 1) }
        inputRow.addView(space)
        inputRow.addView(micBtn)
        inputRow.addView(sendBtn)
        container.addView(inputRow)

        // Section: Direct Phone Controls Label
        val sectionLabel = TextView(this).apply {
            text = "Quick Controls"
            setTextColor(Color.parseColor("#4A46D6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(12f), 0, dpToPx(4f))
        }
        container.addView(sectionLabel)

        // Function helper to create stylish control buttons
        fun createControlBtn(label: String, bgColor: String = "#F1F3FA", textColor: String = "#1B1C33", onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor(textColor))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgColor))
                    cornerRadius = dpToPx(8f).toFloat()
                    setStroke(dpToPx(1f), Color.parseColor("#E1E4F0"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36f), 1f).apply {
                    setMargins(dpToPx(2f), 0, dpToPx(2f), 0)
                }
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    vibrateBrief()
                    onClick()
                }
            }
        }

        // Row 1: Core Navigation & Screen Controls
        val controlsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(2f), 0, dpToPx(4f))
        }
        controlsRow1.addView(createControlBtn("Home") {
            executeDirectPhoneAction("Home") { AgentAccessibilityService.instance?.pressHome() }
        })
        controlsRow1.addView(createControlBtn("Back") {
            executeDirectPhoneAction("Back") { AgentAccessibilityService.instance?.pressBack() }
        })
        controlsRow1.addView(createControlBtn("Recents") {
            executeDirectPhoneAction("Recent Apps") { AgentAccessibilityService.instance?.pressRecents() }
        })
        controlsRow1.addView(createControlBtn("Screenshot") {
            executeDirectPhoneAction("Screenshot") { AgentAccessibilityService.instance?.takeSystemScreenshot() }
        })
        container.addView(controlsRow1)

        // Row 2: Scroll, Read, Notifications & Stop
        val controlsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(2f), 0, dpToPx(4f))
        }
        controlsRow2.addView(createControlBtn("Down") {
            scope.launch { AgentAccessibilityService.instance?.scroll("down") }
        })
        controlsRow2.addView(createControlBtn("Up") {
            scope.launch { AgentAccessibilityService.instance?.scroll("up") }
        })
        controlsRow2.addView(createControlBtn("Read") {
            triggerReadScreen()
            hidePanel()
        })
        controlsRow2.addView(createControlBtn("Notifs") {
            executeDirectPhoneAction("Notifications") { AgentAccessibilityService.instance?.openNotifications() }
        })
        controlsRow2.addView(createControlBtn("Stop", "#FEE2E2", "#DC2626") {
            OmniAgentApplication.instance.orchestrator.cancelCurrentTask()
            Toast.makeText(this, "Jarvis: Task Stopped", Toast.LENGTH_SHORT).show()
            hidePanel()
        })
        container.addView(controlsRow2)

        // Quick App Launcher Row
        val appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(4f), 0, 0)
        }
        appRow.addView(createControlBtn("YouTube", "#E3E2FF", "#4A46D6") {
            quickLaunchApp("youtube")
        })
        appRow.addView(createControlBtn("Chrome", "#E3E2FF", "#4A46D6") {
            quickLaunchApp("chrome")
        })
        appRow.addView(createControlBtn("Camera", "#E3E2FF", "#4A46D6") {
            quickLaunchApp("camera")
        })
        appRow.addView(createControlBtn("Settings", "#E3E2FF", "#4A46D6") {
            quickLaunchApp("settings")
        })
        appRow.addView(createControlBtn("WhatsApp", "#DCFCE7", "#15803D") {
            quickLaunchApp("whatsapp")
        })
        container.addView(appRow)

        panelView = container
    }

    private fun executeDirectPhoneAction(name: String, action: () -> Boolean?) {
        val a11y = AgentAccessibilityService.instance
        if (a11y != null) {
            val success = action() == true
            Toast.makeText(this, "Jarvis: $name executed", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Accessibility Service enable karein Settings me", Toast.LENGTH_SHORT).show()
        }
    }

    private fun quickLaunchApp(appName: String) {
        val appDir = OmniAgentApplication.instance.appDirectory
        val pkg = appDir.resolvePackage(appName)
        if (pkg != null) {
            val launched = appDir.launchApp(pkg)
            if (launched) {
                Toast.makeText(this, "Opening $appName...", Toast.LENGTH_SHORT).show()
                hidePanel()
                return
            }
        }
        Toast.makeText(this, "$appName app nahi mila device par", Toast.LENGTH_SHORT).show()
    }

    private fun updatePanelPosition() {
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = dpToPx(320f)

        if (dotParams.x + dpToPx(58f) + panelWidth < screenWidth) {
            panelParams.x = dotParams.x + dpToPx(62f)
        } else {
            panelParams.x = (dotParams.x - panelWidth - dpToPx(8f)).coerceAtLeast(dpToPx(8f))
        }
        panelParams.y = dotParams.y.coerceAtLeast(dpToPx(40f))

        try {
            if (isPanelOpen && panelView != null) {
                windowManager?.updateViewLayout(panelView, panelParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onSingleTap() {
        if (isPanelOpen) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun onDoubleTap() {
        Toast.makeText(this, "Jarvis: Reading active screen...", Toast.LENGTH_SHORT).show()
        triggerReadScreen()
    }

    private fun onLongPress() {
        showPanel()
        val editInput = panelView?.findViewWithTag<EditText>("command_edit_text")
        if (editInput != null) {
            startVoiceListening(editInput)
        }
    }

    private fun showPanel() {
        if (isPanelOpen || panelView == null) return
        updatePanelPosition()
        try {
            windowManager?.addView(panelView, panelParams)
            isPanelOpen = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hidePanel() {
        if (!isPanelOpen || panelView == null) return
        try {
            windowManager?.removeView(panelView)
            isPanelOpen = false
            stopVoiceListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleVoiceListening(targetEditText: EditText) {
        if (isListeningVoice) {
            stopVoiceListening()
        } else {
            startVoiceListening(targetEditText)
        }
    }

    private fun startVoiceListening(targetEditText: EditText) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required for voice commands", Toast.LENGTH_SHORT).show()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            stopVoiceListening()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Jarvis ko bol ke bataiye...")
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningVoice = true
                    updateMicVisual(true)
                    Toast.makeText(applicationContext, "Listening... Bolna shuru kijiye", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    isListeningVoice = false
                    updateMicVisual(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        targetEditText.setText(spokenText)
                        Toast.makeText(applicationContext, "Heard: \"$spokenText\"", Toast.LENGTH_SHORT).show()
                        handlePhoneOrAgentCommand(spokenText)
                        hidePanel()
                    }
                }
                override fun onError(error: Int) {
                    isListeningVoice = false
                    updateMicVisual(false)
                    val errorDesc = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        else -> "Voice error: $error"
                    }
                    Toast.makeText(applicationContext, errorDesc, Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    updateMicVisual(false)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListeningVoice = false
            updateMicVisual(false)
            Toast.makeText(this, "Failed to start microphone: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
        isListeningVoice = false
        updateMicVisual(false)
    }

    private fun updateMicVisual(listening: Boolean) {
        val micBtn = panelView?.findViewWithTag<ImageView>("panel_mic_button") ?: return
        if (listening) {
            micBtn.setColorFilter(Color.WHITE)
            micBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F43F5E")) // Red pulsing
            }
        } else {
            micBtn.setColorFilter(Color.parseColor("#4A46D6"))
            micBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E3E2FF"))
            }
        }
    }

    /**
     * Core Command Dispatcher:
     * Dispatches phone-controlling functions instantly when told via voice or text,
     * or delegates to multi-step autonomous Gemma agent for complex tasks.
     */
    fun handlePhoneOrAgentCommand(rawCommand: String) {
        val cmd = rawCommand.trim().lowercase()
        val a11y = AgentAccessibilityService.instance

        Log.i(TAG, "[COMMAND_DISPATCH] Processing command: '$rawCommand'")

        // 1. Direct Phone Navigation & Hardware Controls
        when {
            cmd == "home" || cmd == "go home" || cmd.contains("ghar jao") || cmd.contains("main screen") -> {
                executeDirectPhoneAction("Home") { a11y?.pressHome() }
                return
            }
            cmd == "back" || cmd == "go back" || cmd.contains("peeche") || cmd.contains("piche jao") -> {
                executeDirectPhoneAction("Back") { a11y?.pressBack() }
                return
            }
            cmd == "recents" || cmd == "recent apps" || cmd == "overview" || cmd.contains("multitask") -> {
                executeDirectPhoneAction("Recent Apps") { a11y?.pressRecents() }
                return
            }
            cmd.contains("screenshot") || cmd.contains("screen shot") || cmd.contains("photo lo") -> {
                executeDirectPhoneAction("Screenshot") { a11y?.takeSystemScreenshot() }
                return
            }
            cmd == "scroll down" || cmd == "niche scroll" || cmd == "scroll niche" || cmd == "swipe down" || cmd == "down" -> {
                scope.launch { a11y?.scroll("down") }
                Toast.makeText(this, "Jarvis: Scrolling Down", Toast.LENGTH_SHORT).show()
                return
            }
            cmd == "scroll up" || cmd == "upar scroll" || cmd == "scroll upar" || cmd == "swipe up" || cmd == "up" -> {
                scope.launch { a11y?.scroll("up") }
                Toast.makeText(this, "Jarvis: Scrolling Up", Toast.LENGTH_SHORT).show()
                return
            }
            cmd.contains("notification") || cmd == "notifs" || cmd == "open notification" -> {
                executeDirectPhoneAction("Notifications") { a11y?.openNotifications() }
                return
            }
            cmd.contains("quick settings") || cmd == "qs" -> {
                executeDirectPhoneAction("Quick Settings") { a11y?.openQuickSettings() }
                return
            }
            cmd == "read screen" || cmd == "screen read" || cmd.contains("kya hai screen pe") || cmd == "analyze" -> {
                triggerReadScreen()
                return
            }
            cmd == "stop" || cmd == "cancel" || cmd.contains("ruko") || cmd.contains("band karo") -> {
                OmniAgentApplication.instance.orchestrator.cancelCurrentTask()
                Toast.makeText(this, "Jarvis: Agent Stopped", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 2. Direct Single App Launch ("open [app]" or "[app] kholo")
        if ((cmd.startsWith("open ") || cmd.startsWith("launch ") || cmd.endsWith(" kholo")) && !cmd.contains(" and ") && !cmd.contains(" aur ")) {
            val appQuery = cmd
                .removePrefix("open ")
                .removePrefix("launch ")
                .removeSuffix(" kholo")
                .trim()

            val appDir = OmniAgentApplication.instance.appDirectory
            val resolved = appDir.resolvePackage(appQuery)
            if (resolved != null) {
                val launched = appDir.launchApp(resolved)
                if (launched) {
                    Toast.makeText(this, "Jarvis: Opening $appQuery", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        // 3. Direct Screen Tap Command ("tap [target]" or "click [target]")
        if ((cmd.startsWith("tap ") || cmd.startsWith("click ")) && a11y != null) {
            val target = rawCommand.substringAfter(" ").trim()
            scope.launch {
                val tapped = a11y.tapTargetDirectly(target)
                if (tapped) {
                    Toast.makeText(applicationContext, "Tapped '$target'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, "Could not tap '$target'. Starting autonomous agent...", Toast.LENGTH_SHORT).show()
                    OmniAgentApplication.instance.orchestrator.startTask(rawCommand)
                }
            }
            return
        }

        // 4. Multi-step Autonomous Task (Pass to Gemma Orchestrator)
        Toast.makeText(this, "Jarvis executing: \"$rawCommand\"", Toast.LENGTH_SHORT).show()
        OmniAgentApplication.instance.orchestrator.startTask(rawCommand)
    }

    private fun triggerReadScreen() {
        scope.launch {
            val a11y = AgentAccessibilityService.instance
            if (a11y != null) {
                val snapshot = a11y.dumpHierarchy()
                val actionable = snapshot.nodes.filter { it.isClickable || it.isEditable }
                val summary = if (actionable.isNotEmpty()) {
                    actionable.take(5).joinToString(", ") { it.bestLabel() }
                } else {
                    "No actionable elements detected"
                }
                Toast.makeText(
                    applicationContext,
                    "Screen: ${snapshot.packageName?.substringAfterLast('.') ?: "App"}\nActionable: $summary",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(applicationContext, "Enable Accessibility in Settings first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeAgentState() {
        stateObserverJob = scope.launch {
            OmniAgentApplication.instance.orchestrator.orchestratorState.collectLatest { state ->
                updateStatusBadge(state)
            }
        }
    }

    private fun updateStatusBadge(state: OrchestratorState) {
        val badge = dotView?.findViewWithTag<View>("status_badge")
        val statusText = panelView?.findViewWithTag<TextView>("panel_status_text")

        val (color, text) = when (state) {
            is OrchestratorState.Idle -> Color.parseColor("#12B886") to "Ready • Gol dot se phone control karein"
            is OrchestratorState.Planning -> Color.parseColor("#F59E0B") to "Thinking: Gemma Planning Step ${state.stepIndex}..."
            is OrchestratorState.Executing -> Color.parseColor("#5B5BE8") to "Acting: ${state.action.getDisplayName()}"
            is OrchestratorState.Replanning -> Color.parseColor("#F59E0B") to "Replanning alternative path..."
            is OrchestratorState.WaitingConfirmation -> Color.parseColor("#F43F5E") to "Waiting Confirmation for ${state.riskType}!"
            is OrchestratorState.Paused -> Color.parseColor("#F59E0B") to "Agent Task Paused"
            is OrchestratorState.Completed -> Color.parseColor("#12B886") to "Task Completed: ${state.summary}"
            is OrchestratorState.Failed -> Color.parseColor("#F43F5E") to
                if (state.escalated) "Need help: ${state.blockedOn ?: state.reason}" else "Task Failed: ${state.reason}"
        }

        badge?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dpToPx(1.5f), Color.WHITE)
        }

        statusText?.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stateObserverJob?.cancel()
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        stopVoiceListening()
        if (isPanelOpen && panelView != null) {
            try {
                windowManager?.removeView(panelView)
            } catch (_: Exception) {}
        }
        if (dotView != null) {
            try {
                windowManager?.removeView(dotView)
            } catch (_: Exception) {}
        }
    }
}
