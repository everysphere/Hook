package com.tomfricks.hook.data

data class UserPreferences(
    val style: MessageStyle = MessageStyle.SENTENCE_CASE,
    val tone: MessageTone = MessageTone.SMOOTH,
    val flirtLevel: FlirtLevel = FlirtLevel.MEDIUM,
    val replyLength: ReplyLength = ReplyLength.NORMAL,
    val emojiUse: EmojiUse = EmojiUse.EXPRESSIVE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val profileName: String = "",
    val profileGender: String = "",
    val profileSexuality: String = "",
    val profileBio: String = "",
    val profilePronouns: String = "",
    // Asked during onboarding. Free-form strings like the rest of the profile,
    // holding the answer as the user read it ("18-24", "Fun & hookups") so it
    // can go straight into a prompt without a lookup table.
    val profileAgeRange: String = "",
    val profileLookingFor: String = "",
    val hasCompletedOnboarding: Boolean = false
)

enum class MessageStyle(val displayName: String) {
    LOWERCASE("lowercase"),
    SENTENCE_CASE("Sentence case")
}

/**
 * `proOnly` marks a choice as part of Hook Pro. The defaults and a couple of
 * everyday options stay free so the app is usable without a subscription; the
 * more characterful settings are the upsell.
 */
enum class MessageTone(val displayName: String, val proOnly: Boolean = false) {
    GEN_Z_SLANG("Gen-Z Slang", proOnly = true),
    RESPECTFUL("Respectful", proOnly = true),
    FUNNY("Funny"),
    SMOOTH("Smooth")
}

enum class FlirtLevel(val displayName: String, val proOnly: Boolean = false) {
    LESS("Less"),
    MEDIUM("Moderate"),
    BOLD("Bold", proOnly = true),
    EXTRA_BOLD("Extra Bold", proOnly = true)
}

enum class ReplyLength(val displayName: String, val proOnly: Boolean = false) {
    SHORT("Short"),
    NORMAL("Normal"),
    EXTENDED("Extended", proOnly = true)
}

enum class EmojiUse(val displayName: String) {
    NEVER("Never"),
    MINIMAL("Minimal"),
    EXPRESSIVE("Expressive")
}

enum class ThemeMode(val displayName: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System")
}
