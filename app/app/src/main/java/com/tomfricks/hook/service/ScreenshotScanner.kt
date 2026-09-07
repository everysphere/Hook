package com.tomfricks.hook.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Finds a screenshot that was just taken. The foreground service watches
 * MediaStore; the keyboard also calls this when that service isn't running
 * (no notification permission) so opening the IME still picks up a shot
 * taken a few seconds earlier.
 */
object ScreenshotScanner {

    private const val TAG = "ScreenshotScanner"

    /** Observer path: MediaStore often announces within this window. */
    private const val OBSERVER_FRESH_MS = 20_000L

    /**
     * Keyboard-show path: the user screenshots, then switches to Hook.
     * Longer than [OBSERVER_FRESH_MS] because that switch is not instant.
     */
    private const val LOOKBACK_MS = 60_000L

    private val lock = Any()

    @Volatile
    private var lastPath: String? = null

    fun consume(context: Context, uri: Uri): Bitmap? =
        read(context, uri, OBSERVER_FRESH_MS)

    fun consumeLatest(context: Context): Bitmap? =
        read(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, LOOKBACK_MS)

    private fun read(context: Context, uri: Uri, freshMs: Long): Bitmap? {
        // MediaStore fires onChange several times per capture (insert + updates).
        // Without this lock two concurrent reads can both pass the lastPath check
        // and the keyboard stacks two identical screenshots for one tap.
        synchronized(lock) {
            val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED
            )
            val sort = if (uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            } else {
                null
            }
            return try {
                context.contentResolver.query(uri, projection, null, null, sort)?.use { cursor ->
                    val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    if (pathIndex < 0 || dateIndex < 0) return null
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(pathIndex) ?: continue
                        val dateAdded = cursor.getLong(dateIndex) * 1000
                        if (System.currentTimeMillis() - dateAdded >= freshMs) {
                            if (sort != null) return null
                            continue
                        }
                        if (File(path).name.startsWith(".pending-")) continue
                        if (!looksLikeScreenshot(path)) continue
                        if (path == lastPath) return null
                        val bitmap = load(path) ?: continue
                        lastPath = path
                        return bitmap
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading screenshot", e)
                null
            }
        }
    }

    private fun looksLikeScreenshot(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("screenshot") ||
            lower.contains("screen shot") ||
            lower.contains("screen_shot")
    }

    private fun load(path: String): Bitmap? {
        return try {
            if (!File(path).exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds, 1080, 1920)
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading screenshot", e)
            null
        }
    }

    private fun sampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
