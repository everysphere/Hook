package com.tomfricks.hook.data

/**
 * Everything the onboarding flow asks for: profile facts first, then the three
 * settings that shape how a reply is written.
 *
 * Answers are stored as strings — the profile ones as the label the user read
 * ("18-24"), the settings ones as the enum name, so they round-trip through
 * [UserPreferences] unchanged.
 */
enum class OnboardingField {
    GENDER,
    SEXUALITY,
    AGE_RANGE,
    LOOKING_FOR,
    STYLE,
    TONE,
    FLIRT_LEVEL
}

/**
 * One tappable answer.
 *
 * [value] is what gets stored when [label] is what gets read — they differ for
 * the settings questions, where the store wants `GEN_Z_SLANG` and the user
 * wants "Gen-Z Slang". [preview] is the sample reply shown while this answer is
 * selected, so the choice is made by seeing it rather than by guessing.
 */
data class AnswerOption(
    val label: String,
    val value: String = label,
    val preview: String? = null,
    val proOnly: Boolean = false
)

/**
 * One question screen: what's asked, why, the answers, and the label on the
 * button that leaves it.
 */
data class OnboardingQuestion(
    val field: OnboardingField,
    val title: String,
    val subtitle: String? = null,
    val options: List<AnswerOption>,
    val action: String = "Continue"
)

/** The facts about the user, asked before anything is set up. */
val ProfileQuestions = listOf(
    OnboardingQuestion(
        field = OnboardingField.GENDER,
        title = "What's your gender?",
        subtitle = "This will be used to cater your generated responses",
        options = listOf("Male", "Female", "Other").map { AnswerOption(it) }
    ),
    OnboardingQuestion(
        field = OnboardingField.SEXUALITY,
        title = "What's your sexuality?",
        subtitle = "This will be used to cater your generated responses",
        options = listOf("Straight", "Gay", "Lesbian", "Bisexual").map { AnswerOption(it) }
    ),
    OnboardingQuestion(
        field = OnboardingField.AGE_RANGE,
        title = "What's your age?",
        subtitle = "Depending on your age, we'll recommend a different app setup for you.",
        options = listOf(
            "Under 18",
            "18-24",
            "25-34",
            "35-44",
            "45-54",
            "55-64",
            "Over 64"
        ).map { AnswerOption(it) }
    ),
    OnboardingQuestion(
        field = OnboardingField.LOOKING_FOR,
        title = "I'm looking for...",
        subtitle = "This question ensures we help you attract compatible partners!",
        options = listOf(
            "💕 A relationship",
            "🔥 Fun & hookups",
            "🍷 Casual dates",
            "🤔 Still figuring it out"
        ).map { AnswerOption(it) }
    )
)

// The three settings questions are built from the enums themselves, so an
// option can never drift from what the app actually supports — including which
// ones are Pro.
private fun MessageStyle.option(preview: String) =
    AnswerOption(label = displayName, value = name, preview = preview)

private fun MessageTone.option(preview: String) =
    AnswerOption(label = displayName, value = name, preview = preview, proOnly = proOnly)

private fun FlirtLevel.option(preview: String) =
    AnswerOption(label = displayName, value = name, preview = preview, proOnly = proOnly)

/**
 * How replies should be written. Each answer carries a sample in that exact
 * voice — the previews are the whole point of asking here rather than burying
 * it in settings.
 */
val StyleQuestions = listOf(
    OnboardingQuestion(
        field = OnboardingField.STYLE,
        title = "What's your style?",
        options = listOf(
            MessageStyle.LOWERCASE.option(
                "generated messages will look like this. here are some more words."
            ),
            MessageStyle.SENTENCE_CASE.option(
                "Generated messages will look like this. Here are some more words."
            )
        )
    ),
    OnboardingQuestion(
        field = OnboardingField.TONE,
        title = "What's your tone?",
        options = listOf(
            MessageTone.SMOOTH.option("you had me at the dog. when do we meet?"),
            MessageTone.FUNNY.option(
                "I'd say something clever here, but I'm saving it for the first date."
            ),
            MessageTone.GEN_Z_SLANG.option("ngl you're giving main character energy"),
            MessageTone.RESPECTFUL.option(
                "Your profile made me smile — what's been the highlight of your week?"
            )
        )
    ),
    OnboardingQuestion(
        field = OnboardingField.FLIRT_LEVEL,
        title = "What's your flirt level?",
        options = listOf(
            FlirtLevel.LESS.option("you seem fun. what's your week looking like?"),
            FlirtLevel.MEDIUM.option("you might be my type, wanna prove me right?"),
            FlirtLevel.BOLD.option("you're trouble, and I'm not looking for a way out"),
            FlirtLevel.EXTRA_BOLD.option(
                "skip the small talk — when are you coming over tonight?"
            )
        ),
        // The last question of the flow: the button builds the keyboard rather
        // than continuing to yet another screen.
        action = "Generate keyboard"
    )
)
