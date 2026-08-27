package com.tomfricks.hook.ui.screens.onboarding

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tomfricks.hook.keyboard.HookKeyboardService
import com.tomfricks.hook.utils.PermissionUtils
import kotlinx.coroutines.delay

private const val TAG = "KeyboardSetup"

/** Everything onboarding needs to know about Hook's status as a keyboard. */
data class KeyboardSetup(
    /** Hook appears in the system's list of enabled keyboards. */
    val enabled: Boolean,
    /** Hook is the *selected* keyboard — the one that comes up when you type. */
    val selected: Boolean,
    /** Hook may read the screenshots the user takes. */
    val photos: Boolean,
    /** Hook may post notifications — what makes the watcher visible while it runs. */
    val notifications: Boolean = false,
    /** Hook's own keyboard view is on screen right now. */
    val showing: Boolean
) {
    /**
     * True once Hook is the user's keyboard. This — not [showing] — is what the
     * demo is waiting for: the keyboard being *chosen* is the thing that had to
     * happen, and whether its view is up at this instant depends on whether
     * something is focused.
     */
    val isHooksTurn: Boolean get() = selected || showing
}

/**
 * Live keyboard status, by three independent means — because being wrong here
 * strands the user on a screen whose instructions they have already followed.
 *
 * 1. A [ContentObserver] on the settings the system actually writes when a
 *    keyboard is enabled or switched to. This is the event-driven path and the
 *    one that fires the instant the user picks Hook in the chooser.
 * 2. A poll, as a backstop for devices where that notification doesn't arrive.
 * 3. ON_RESUME, which covers coming back from a full Settings screen.
 *
 * The earlier version had only the resume hook, and then only a poll; neither
 * was enough on its own, so now it is all three and they cost almost nothing.
 */
@Composable
fun rememberKeyboardSetup(): KeyboardSetup {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val showing by HookKeyboardService.isShowing.collectAsState()

    var polled by remember { mutableStateOf(readKeyboardSetup(context)) }

    fun refresh(reason: String) {
        val fresh = readKeyboardSetup(context)
        if (fresh != polled) {
            Log.d(TAG, "$reason: enabled=${fresh.enabled} selected=${fresh.selected} photos=${fresh.photos} notifications=${fresh.notifications}")
            polled = fresh
        }
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh("settings changed")
        }
        val resolver = context.contentResolver
        // Written when a keyboard is switched to, and when one is turned on or
        // off. Named by string rather than constant: only DEFAULT_INPUT_METHOD
        // is guaranteed public API.
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            observer
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_input_methods"),
            false,
            observer
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            refresh("poll")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh("resumed")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return polled.copy(showing = showing)
}

private fun readKeyboardSetup(context: Context) = KeyboardSetup(
    enabled = PermissionUtils.isKeyboardEnabled(context),
    selected = PermissionUtils.isKeyboardSelected(context),
    photos = PermissionUtils.hasPhotoAccess(context),
    notifications = PermissionUtils.hasNotificationAccess(context),
    showing = false
)
