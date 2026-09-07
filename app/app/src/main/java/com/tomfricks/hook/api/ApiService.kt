package com.tomfricks.hook.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.tomfricks.hook.BuildConfig
import com.tomfricks.hook.data.EntitlementState
import com.tomfricks.hook.data.PreferencesRepository
import com.tomfricks.hook.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

interface HookApiInterface {
    // Response<T> rather than a bare body: a 402 is a *product state*, not a
    // crash, and we need its body to drive the paywall.
    @POST("/generate-replies")
    suspend fun generateReplies(@Body request: GenerateRepliesRequest): Response<GenerateRepliesResponse>

    @GET("/me")
    suspend fun me(): Response<MeResponse>

    // Same response shape as /me, but drops the server's cached RevenueCat
    // answer first. No body — the identity is in the auth headers.
    @POST("/entitlement/refresh")
    suspend fun refreshEntitlement(): Response<MeResponse>

    // 204, no body: the app has nothing to do with the answer beyond knowing
    // it landed.
    @POST("/onboarding")
    suspend fun submitOnboarding(@Body request: OnboardingProfileRequest): Response<Unit>

    // Also 204. Reporting offensive output is a policy requirement, so it is
    // never gated on Pro and never metered.
    @POST("/report")
    suspend fun reportContent(@Body request: ContentReportRequest): Response<Unit>
}

/** Suggestions, the updated hidden conversation context, and what's left of the allowance. */
data class GeneratedReplies(
    val suggestions: List<String>,
    val context: ConversationContext,
    val entitlement: EntitlementState
)

/**
 * Outcome of a generation. "Out of free generations" is deliberately its own
 * branch so callers can open the paywall instead of showing an error.
 */
sealed interface GenerateRepliesResult {
    data class Success(val replies: GeneratedReplies) : GenerateRepliesResult

    /** HTTP 402 — the lifetime free allowance is spent; the user must subscribe. */
    data class AllowanceExhausted(val freeLimit: Int, val used: Int) : GenerateRepliesResult

    /**
     * Carries a [ReplyError], never a string: whatever the server or the
     * network actually said is logged here and goes no further, so the keyboard
     * can only ever show copy we wrote.
     */
    data class Failure(val error: ReplyError) : GenerateRepliesResult
}

class ApiService(
    context: Context,
    private val serverUrl: String = DEFAULT_SERVER_URL
) {

    private val appContext = context.applicationContext
    private val preferencesRepository = PreferencesRepository(appContext)
    private val gson = Gson()

    /**
     * Every request carries the shared app key and this install's stable id —
     * the server rejects anything missing either (401 / 400 respectively).
     */
    private val authInterceptor = Interceptor { chain ->
        val builder = chain.request().newBuilder()
            .header(HEADER_API_KEY, BuildConfig.APP_API_KEY)
        appUserId()?.let { builder.header(HEADER_APP_USER_ID, it) }
        chain.proceed(builder.build())
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(serverUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(HookApiInterface::class.java)

    suspend fun generateReplies(
        screenshot: Bitmap,
        preferences: UserPreferences,
        context: ConversationContext = ConversationContext()
    ): GenerateRepliesResult = withContext(Dispatchers.IO) {
        try {
            // Convert bitmap to base64
            val base64Image = bitmapToBase64(screenshot)

            // Convert UserPreferences to PreferencesDto
            val preferencesDto = PreferencesDto(
                style = preferences.style.name,
                tone = preferences.tone.name,
                flirtLevel = preferences.flirtLevel.name,
                replyLength = preferences.replyLength.name,
                emojiUse = preferences.emojiUse.name,
                profileName = preferences.profileName,
                profileGender = preferences.profileGender,
                profilePronouns = preferences.profilePronouns,
                profileBio = preferences.profileBio
            )

            // print preferencesDto
            Log.d(TAG, "PreferencesDto: $preferencesDto")

            // Create request — carry the hidden session context forward
            val request = GenerateRepliesRequest(
                screenshotBase64 = base64Image,
                preferences = preferencesDto,
                context = context
            )

            // Make API call
            val response = api.generateReplies(request)

            if (!response.isSuccessful) {
                return@withContext errorResult(response.code(), response.errorBody()?.string())
            }

            val body = response.body()
            if (body == null) {
                Log.e(TAG, "POST /generate-replies returned an empty body")
                return@withContext GenerateRepliesResult.Failure(ReplyError.SERVER)
            }

            val entitlement = body.toEntitlementState()
            cacheEntitlement(entitlement)

            val suggestions = body.suggestions.filter { it.isNotBlank() }
            if (suggestions.isEmpty()) {
                Log.e(TAG, "POST /generate-replies returned no usable suggestions")
                GenerateRepliesResult.Failure(ReplyError.UNREADABLE)
            } else {
                Log.d(TAG, "Received ${suggestions.size} suggestions")
                GenerateRepliesResult.Success(
                    GeneratedReplies(suggestions, body.context, entitlement)
                )
            }
        } catch (e: Exception) {
            // Everything the exception knows stops here, at Logcat.
            Log.e(TAG, "Error generating replies", e)
            GenerateRepliesResult.Failure(e.toReplyError())
        }
    }

    /** Network exceptions, mapped onto the two things a user can act on. */
    private fun Exception.toReplyError(): ReplyError = when (this) {
        is SocketTimeoutException -> ReplyError.TIMEOUT
        is UnknownHostException, is ConnectException, is SSLException -> ReplyError.NO_CONNECTION
        // Covers the rest of the okhttp/IO family: a socket that died mid-call
        // reads to the user as "no connection", because that's what it is.
        is IOException -> ReplyError.NO_CONNECTION
        else -> ReplyError.SERVER
    }

    /**
     * Send the finished onboarding profile. Called once, after the last screen.
     *
     * Failure is not the user's problem — they have finished onboarding either
     * way — so this only reports whether to stop retrying, and the caller keeps
     * the "not yet sent" flag until it returns true.
     */
    suspend fun submitOnboarding(
        profile: OnboardingProfileRequest
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.submitOnboarding(profile)
            if (!response.isSuccessful) {
                logHttpError(response.code(), "POST /onboarding")
                // 4xx means this body will never be accepted; retrying it every
                // launch would just be noise. 5xx and network errors are worth
                // another go later.
                return@withContext response.code() in 400..499
            }
            Log.d(TAG, "Onboarding profile sent")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not send the onboarding profile; will retry", e)
            false
        }
    }

    /**
     * Flag one generated reply as offensive.
     *
     * Reports whether it landed so the keyboard can say "couldn't send that"
     * rather than silently swallowing a report the user believes they filed.
     */
    suspend fun reportContent(
        text: String,
        reason: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.reportContent(ContentReportRequest(text = text, reason = reason))
            if (!response.isSuccessful) {
                logHttpError(response.code(), "POST /report")
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not send the content report", e)
            false
        }
    }

    /**
     * Entitlement + allowance for this install. Called on launch and after a
     * purchase so the paywall and the keyboard's counter stay honest.
     */
    suspend fun fetchEntitlement(): Result<EntitlementState> = withContext(Dispatchers.IO) {
        entitlementResult("GET /me") { api.me() }
    }

    /**
     * Ask the server to re-check RevenueCat for this install *now*, ignoring its
     * short-lived entitlement cache.
     *
     * Called right after a purchase grants Pro. Without it the server can keep
     * answering "not Pro" for the rest of its cache TTL, i.e. reject a
     * generation with 402 from someone who has just paid.
     */
    suspend fun refreshServerEntitlement(): Result<EntitlementState> = withContext(Dispatchers.IO) {
        entitlementResult("POST /entitlement/refresh") { api.refreshEntitlement() }
    }

    /** Shared handling for the two routes that answer with [MeResponse]. */
    private suspend fun entitlementResult(
        route: String,
        call: suspend () -> Response<MeResponse>
    ): Result<EntitlementState> = try {
        val response = call()
        if (!response.isSuccessful) {
            logHttpError(response.code(), route)
            Result.failure(Exception("$route failed with HTTP ${response.code()}"))
        } else {
            val body = response.body()
            if (body == null) {
                Result.failure(Exception("Empty $route response"))
            } else {
                val entitlement = EntitlementState(
                    isPro = body.isPro,
                    freeUsed = body.freeUsed,
                    freeLimit = body.freeLimit.orDefaultLimit()
                )
                cacheEntitlement(entitlement)
                Result.success(entitlement)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error calling $route", e)
        Result.failure(e)
    }

    /** Maps a non-2xx response onto the typed result, logging enough to debug it. */
    private suspend fun errorResult(code: Int, rawBody: String?): GenerateRepliesResult {
        if (code == HTTP_PAYMENT_REQUIRED) {
            val payload = rawBody?.let {
                runCatching { gson.fromJson(it, AllowanceExhaustedResponse::class.java) }.getOrNull()
            }
            val freeLimit = payload?.freeLimit.orDefaultLimit()
            val used = payload?.used ?: freeLimit
            Log.i(TAG, "Free allowance exhausted ($used/$freeLimit) — paywall required")
            cacheEntitlement(
                EntitlementState(isPro = false, freeUsed = used, freeLimit = freeLimit)
            )
            return GenerateRepliesResult.AllowanceExhausted(freeLimit, used)
        }

        // The body can carry provider internals ("Groq rate limit reached for
        // model …"), so it is logged and dropped — the user gets our copy.
        logHttpError(code, "POST /generate-replies")
        rawBody?.takeIf { it.isNotBlank() }?.let { Log.d(TAG, "Error body: $it") }
        val error = when (code) {
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED -> ReplyError.SETUP
            // The AI provider's per-minute budget, not a broken server: it
            // clears on its own, so ask for patience rather than a retry loop.
            HTTP_TOO_MANY_REQUESTS -> ReplyError.BUSY
            else -> ReplyError.SERVER
        }
        return GenerateRepliesResult.Failure(error)
    }

    private fun logHttpError(code: Int, route: String) {
        when (code) {
            HTTP_BAD_REQUEST -> Log.e(
                TAG,
                "$route rejected the $HEADER_APP_USER_ID header (HTTP 400). " +
                    "Install id: ${PreferencesRepository.cachedAppUserId}"
            )

            HTTP_UNAUTHORIZED -> Log.e(
                TAG,
                "$route rejected the $HEADER_API_KEY header (HTTP 401). " +
                    "Is APP_API_KEY set in local.properties?"
            )

            else -> Log.e(TAG, "$route failed with HTTP $code")
        }
    }

    /**
     * Persist what the server just told us. Doing it here means every surface —
     * keyboard, home, paywall — sees a fresh count with no extra plumbing.
     */
    private suspend fun cacheEntitlement(state: EntitlementState) {
        runCatching { preferencesRepository.updateEntitlement(state) }
            .onFailure { Log.w(TAG, "Could not cache entitlement state", it) }
    }

    /**
     * The install id for the `X-App-User-Id` header. Served from the
     * process-wide cache; the blocking fallback only runs when the app hasn't
     * warmed it yet, and interceptors never run on the main thread.
     */
    private fun appUserId(): String? = PreferencesRepository.cachedAppUserId
        ?: runCatching { runBlocking { preferencesRepository.getOrCreateAppUserId() } }
            .onFailure { Log.e(TAG, "Could not resolve the app user id", it) }
            .getOrNull()

    private fun GenerateRepliesResponse.toEntitlementState() = EntitlementState(
        isPro = isPro,
        freeUsed = freeUsed,
        freeLimit = freeLimit.orDefaultLimit()
    )

    /** A missing/zero limit means the server didn't say; fall back to the known model. */
    private fun Int?.orDefaultLimit(): Int =
        if (this != null && this > 0) this else PreferencesRepository.DEFAULT_FREE_LIMIT

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Scale down before compression to make upload instant (<60KB instead of 4MB).
        // Vision tokens dominate Groq's free-tier TPM, so keep this tight.
        val maxWidth = 384
        val scaledBitmap = if (bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
            val newHeight = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        val byteArray = outputStream.toByteArray()

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://hook-u23k.onrender.com"

        private const val TAG = "ApiService"
        private const val HEADER_API_KEY = "X-Api-Key"
        private const val HEADER_APP_USER_ID = "X-App-User-Id"
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
