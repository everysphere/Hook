package com.tomfricks.hook.keyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tomfricks.hook.MainActivity
import com.tomfricks.hook.api.ApiService
import com.tomfricks.hook.api.GenerateRepliesResult
import com.tomfricks.hook.api.ReplyError
import com.tomfricks.hook.billing.BillingManager
import com.tomfricks.hook.data.EntitlementState
import com.tomfricks.hook.data.PreferencesRepository
import com.tomfricks.hook.data.ThemeMode
import com.tomfricks.hook.data.UserPreferences
import com.tomfricks.hook.service.ScreenshotDetectionService
import com.tomfricks.hook.service.ScreenshotScanner
import com.tomfricks.hook.ui.keyboard.KeyboardPanel
import com.tomfricks.hook.ui.theme.HookTheme
import com.tomfricks.hook.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HookKeyboardService : InputMethodService(), LifecycleOwner {

    companion object {
        private val _isShowing = MutableStateFlow(false)

        /**
         * True while this keyboard is the one on screen.
         *
         * The IME and the app share a process, so the onboarding demo can watch
         * this to know the user has actually switched to Hook — which is the one
         * thing it can't ask Android about, since the switch happens without
         * ever leaving our activity.
         */
        val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()
    }

    private lateinit var preferencesRepository: PreferencesRepository
    private var apiService: ApiService? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override val lifecycle: Lifecycle
        get() = lifecycleOwner.lifecycle

    private val lifecycleOwner = KeyboardLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = PreferencesRepository(this)

        // Initialize API service — it needs a Context to resolve this install's
        // id for the X-App-User-Id header.
        apiService = ApiService(this)

        ScreenshotDetectionService.startIfAllowed(this)

        lifecycleOwner.onCreate()
    }

    override fun onCreateInputView(): View {
        lifecycleOwner.onResume()

        // Set lifecycle owner on the InputMethodService window's DecorView
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(lifecycleOwner)
            decorView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }

        return ComposeView(this).apply {
            // Use DisposeOnDetachedFromWindowOrReleasedFromPool strategy for IME
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )

            setContent {
                val userPreferences by preferencesRepository.userPreferencesFlow.collectAsState(
                    initial = UserPreferences()
                )

                val systemInDarkTheme = isSystemInDarkTheme()
                val isDarkTheme = when (userPreferences.themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> systemInDarkTheme
                }

                // The cached allowance renders instantly; RevenueCat's live
                // answer wins as soon as it lands.
                val entitlement by preferencesRepository.entitlementFlow.collectAsState(
                    initial = EntitlementState()
                )
                val billingIsPro by BillingManager.isPro.collectAsState()
                val isPro = billingIsPro || entitlement.isPro

                LaunchedEffect(isPro) {
                    if (isPro) RizzSession.clearPaywallRequired()
                }

                HookTheme(themeMode = userPreferences.themeMode) {
                    // Rendered straight off the shared session, so the whole
                    // transcript — every screenshot, reply and typing bubble —
                    // that built up while this keyboard did not exist is already
                    // here.
                    KeyboardPanel(
                        state = RizzSession.status,
                        items = RizzSession.items,
                        errorMessage = RizzSession.error,
                        isDarkTheme = isDarkTheme,
                        onGenerate = ::onGenerateClicked,
                        onSuggestionClick = ::onSuggestionClicked,
                        onReportSuggestion = ::onSuggestionReported,
                        onNewChatClick = ::onNewChatClicked,
                        onSwitchKeyboardClick = ::onSwitchKeyboardClicked,
                        onSettingsClick = ::openSettings,
                        onBackspaceClick = ::onBackspaceClicked,
                        isPro = isPro,
                        freeRemaining = entitlement.remaining,
                        paywallRequired = RizzSession.paywallRequired,
                        onUpgradeClick = ::openPaywall
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        _isShowing.value = true

        // Typical flow: screenshot the chat, *then* open the keyboard. Anything
        // generated in the meantime is kept; only a stale round is dropped.
        RizzSession.clearIfStale()

        // Permission may have been granted since onCreate; try again. If it
        // is still denied, pick up a screenshot taken while the keyboard was
        // down — without running an invisible foreground service.
        if (!ScreenshotDetectionService.startIfAllowed(this)) {
            if (PermissionUtils.hasPhotoAccess(this)) {
                serviceScope.launch(Dispatchers.IO) {
                    val bitmap = ScreenshotScanner.consumeLatest(this@HookKeyboardService)
                        ?: return@launch
                    withContext(Dispatchers.Main) {
                        RizzSession.onScreenshot(bitmap)
                        RizzSession.claimForScreenshot()?.let { generateReplies(it) }
                    }
                }
            }
        } else {
            RizzSession.claimForScreenshot()?.let { generateReplies(it) }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        _isShowing.value = false
        // Don't pause here as the view might be reused
    }

    override fun onDestroy() {
        super.onDestroy()
        _isShowing.value = false
        lifecycleOwner.onDestroy()
        ScreenshotDetectionService.stop(this)
    }

    /**
     * The action pill: the one place a *repeat* round on the same screenshot is
     * allowed, because the user asked for it in so many words. The claim also
     * absorbs double taps and taps landing while a round is in flight.
     */
    private fun onGenerateClicked() {
        val generation = RizzSession.claimForRetry()
        if (generation != null) {
            generateReplies(generation)
        } else if (!RizzSession.isGenerating) {
            RizzSession.onUnstartable(ReplyError.NO_SCREENSHOT)
        }
    }

    /**
     * Run one claimed round. The claim already put the session into GENERATING,
     * and its token decides whether the answer is still wanted by the time it
     * arrives — a newer screenshot, or "+", retires it in flight.
     */
    private fun generateReplies(generation: RizzSession.Generation) {
        serviceScope.launch {
            val token = generation.token
            val prefs = preferencesRepository.userPreferencesFlow.first()

            // Carry the hidden session context into the request and store the
            // server's updated context (in memory only) before showing replies.
            val snapshot = ConversationSession.snapshot()

            when (
                val result =
                    apiService?.generateReplies(generation.screenshot, prefs, snapshot.context)
            ) {
                is GenerateRepliesResult.Success -> {
                    val generated = result.replies
                    // A superseded round leaves nothing behind — not the
                    // suggestions, and not the hidden context either.
                    if (RizzSession.isLive(token)) {
                        ConversationSession.update(snapshot.sessionId, generated.context)
                        RizzSession.onSuggestions(token, generated.suggestions)
                        Log.d("HookKeyboard", "Generated ${generated.suggestions.size} suggestions")
                    } else {
                        Log.d("HookKeyboard", "Dropping superseded round $token")
                    }
                }

                is GenerateRepliesResult.AllowanceExhausted -> {
                    // Not a failure: swap the panel over to the upsell so the
                    // user can go buy Pro in the main app.
                    RizzSession.onAllowanceExhausted(token)
                    Log.i(
                        "HookKeyboard",
                        "Free allowance spent (${result.used}/${result.freeLimit})"
                    )
                }

                is GenerateRepliesResult.Failure -> {
                    RizzSession.onFailure(token, result.error)
                    Log.e("HookKeyboard", "Generation failed: ${result.error}")
                }

                null -> RizzSession.onFailure(token, ReplyError.SERVER)
            }
        }
    }

    private fun onSuggestionClicked(suggestion: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(suggestion, 1)

        // Keep the panel open: the tapped reply moves into the sent transcript
        // and is recorded in the hidden session context so the next generation
        // knows what was actually sent.
        RizzSession.markSent(suggestion)
        ConversationSession.recordSentReply(suggestion)
    }

    /**
     * File a report against one generated reply.
     *
     * Fire-and-forget on the service scope, not the composition: the panel has
     * already told the user the report is in, and an IME's composition can go
     * away the moment they switch apps. Nothing here is worth interrupting them
     * for — a report that fails to reach the server is logged and dropped
     * rather than turned into an error over a keyboard.
     */
    private fun onSuggestionReported(suggestion: String) {
        serviceScope.launch {
            apiService?.reportContent(suggestion, reason = "offensive")
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Google Play's purchase sheet needs a real Activity, which an IME is not,
     * so hand the user over to MainActivity on the paywall route.
     */
    private fun openPaywall() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_SHOW_PAYWALL, true)
        }
        startActivity(intent)
    }

    /**
     * Start a fresh session: wipe the hidden conversation context and every
     * on-screen trace so the next screenshot begins a brand-new conversation.
     */
    private fun onNewChatClicked() {
        ConversationSession.reset()
        RizzSession.reset()
        Log.d("HookKeyboard", "New session started")
    }

    private fun onBackspaceClicked() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    /**
     * Open the system IME picker so the user can leave Hook.
     *
     * Newer Android puts this on the nav bar; older versions and a lot of OEM
     * skins don't, which is why the globe lives on the keyboard itself. Try
     * the last keyboard first — that's almost always Gboard — and fall back
     * to the picker when Android won't switch directly.
     */
    private fun onSwitchKeyboardClicked() {
        if (switchToPreviousInputMethod()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun stopScreenshotService() {
        val intent = Intent(this, ScreenshotDetectionService::class.java).apply {
            action = ScreenshotDetectionService.ACTION_STOP
        }
        startService(intent)
    }
}
