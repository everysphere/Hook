package com.tomfricks.hook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tomfricks.hook.billing.BillingManager
import com.tomfricks.hook.data.UserPreferences
import com.tomfricks.hook.ui.navigation.HookNavigation
import com.tomfricks.hook.ui.theme.HookTheme

class MainActivity : ComponentActivity() {

    /**
     * Bumped every time something asks for the paywall. A counter rather than a
     * flag so a second request while the app is already open still navigates.
     */
    private var paywallRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 disables the edge-to-edge opt-out. Draw behind the system
        // bars, then inset the Compose tree so controls stay tappable.
        enableEdgeToEdge()

        handlePaywallIntent(intent)

        setContent {
            val app = application as HookApplication
            val userPreferences by app.preferencesRepository.userPreferencesFlow.collectAsState(
                initial = UserPreferences()
            )

            HookTheme(themeMode = userPreferences.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                        HookNavigation(paywallRequest = paywallRequest)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The keyboard launches us with FLAG_ACTIVITY_NEW_TASK while the app is
        // often already alive; singleTop routes that here instead of onCreate.
        setIntent(intent)
        handlePaywallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // A subscription can be bought, cancelled or refunded outside the app.
        BillingManager.refreshInBackground()
    }

    /** The keyboard can't host a Play purchase sheet, so it sends the user here. */
    private fun handlePaywallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_PAYWALL, false) == true) {
            paywallRequest++
        }
    }

    companion object {
        /** Boolean extra: open straight onto the paywall route. */
        const val EXTRA_SHOW_PAYWALL = "com.tomfricks.hook.extra.SHOW_PAYWALL"
    }
}
