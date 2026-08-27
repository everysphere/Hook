package com.tomfricks.hook.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionUtils {

    /**
     * The permission that lets Hook read the screenshot the user just took.
     *
     * Android 13 split media out of storage; below that, images come with the
     * broad storage permission.
     */
    val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** True once Hook can read screenshots — without it, detection sees nothing. */
    fun hasPhotoAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, photoPermission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The permission behind the ongoing notification the screenshot service
     * must show, or null where there is nothing to ask for.
     *
     * Android 13 made notifications a runtime permission; before that they were
     * granted at install. Nullable rather than a lie, so a caller can't
     * accidentally launch a permission dialog on a device that has none — see
     * [openNotificationSettings] for the way through on those.
     */
    val notificationPermission: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    /**
     * True once Hook may post notifications — without it, Android 13+ silently
     * withholds the foreground service's notification, and the screenshot
     * watcher runs with nothing on screen to say so.
     *
     * This asks the user-facing toggle rather than the permission, which is the
     * honest answer on every API level: below 33 there is no runtime grant to
     * check, but the user can still have switched notifications off in Settings.
     */
    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Hook's own entry in the system app-settings screen. */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    /**
     * Hook's notification settings, where the toggle actually lives.
     *
     * The only route left once the runtime dialog is spent — and the only route
     * at all below Android 13, where there was never a dialog to spend.
     */
    fun openNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            // No per-app notification screen before Oreo; the app's own settings
            // page is the closest thing to it.
            openAppSettings(context)
        }
    }

    /**
     * Check if Hook keyboard is enabled in system settings
     */
    fun isKeyboardEnabled(context: Context): Boolean {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledInputMethods = inputMethodManager.enabledInputMethodList
        val packageName = context.packageName
        
        return enabledInputMethods.any { it.packageName == packageName }
    }
    
    /**
     * Check if Hook keyboard is currently selected
     */
    fun isKeyboardSelected(context: Context): Boolean {
        val defaultKeyboard = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return defaultKeyboard?.contains(context.packageName) == true
    }
    
    /**
     * Open keyboard settings
     */
    fun openKeyboardSettings(context: Context) {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * Show input method picker
     */
    fun showKeyboardPicker(context: Context) {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showInputMethodPicker()
    }
}
