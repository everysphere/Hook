package com.tomfricks.hook.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.tomfricks.hook.data.AnswerOption
import com.tomfricks.hook.data.OnboardingField
import com.tomfricks.hook.data.OnboardingQuestion
import com.tomfricks.hook.data.OnboardingSync
import com.tomfricks.hook.data.PreferencesRepository
import com.tomfricks.hook.data.ProfileQuestions
import com.tomfricks.hook.data.StyleQuestions
import com.tomfricks.hook.service.ScreenshotDetectionService
import com.tomfricks.hook.ui.theme.PebbleButton
import com.tomfricks.hook.ui.theme.PebbleDialog
import com.tomfricks.hook.ui.theme.PebbleOption
import com.tomfricks.hook.ui.theme.PebbleTextButton
import com.tomfricks.hook.ui.theme.PebbleTone
import com.tomfricks.hook.ui.theme.ProBadge
import com.tomfricks.hook.utils.PermissionUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One screen of onboarding. The list of these *is* the flow. */
private sealed interface Step {

    /**
     * Stable name for this step, used to resume where the user left off. It
     * outlives reordering, which an index would not.
     */
    val key: String

    /**
     * A step past which the user is never sent back — because what happens
     * there can't be repeated. Reaching one records it, so a crash or a
     * force-quit resumes here instead of at the first question.
     */
    val checkpoint: Boolean get() = false

    /** A question with a fixed set of answers. */
    data class Ask(val question: OnboardingQuestion) : Step {
        override val key: String get() = question.field.name
    }

    /** The system setup: enable the keyboard, switch to it, allow photos and notifications. */
    data object EnableKeyboard : Step {
        override val key = "keyboard"
    }

    /** Hook used for real, on a stand-in conversation. */
    data object Demo : Step {
        override val key = "demo"
        override val checkpoint = true
    }

    /** The beat after the demo lands. */
    data object Smooth : Step {
        override val key = "smooth"
        override val checkpoint = true
    }

    /** Everything is set; the only way out is into the app. */
    data object Done : Step {
        override val key = "done"
    }
}

private val Steps: List<Step> =
    ProfileQuestions.map { Step.Ask(it) } +
        Step.EnableKeyboard +
        Step.Demo +
        Step.Smooth +
        StyleQuestions.map { Step.Ask(it) } +
        Step.Done

/**
 * The whole of onboarding: who you are, turning the keyboard on, how it should
 * write, and a hand-off into the app.
 *
 * Every screen shares the same chrome — back, progress, and a "why we ask"
 * escape hatch — and one primary button at the bottom whose label comes from
 * the step. Adding a question means adding it to [ProfileQuestions] or
 * [StyleQuestions]; nothing here needs to change.
 *
 * @param onComplete onboarding is finished and recorded.
 * @param onBackFromStart back pressed on the very first screen, which this
 *   screen has nothing behind.
 */
@Composable
fun OnboardingFlow(
    onComplete: () -> Unit,
    onBackFromStart: () -> Unit,
    preferencesRepository: PreferencesRepository,
    isPro: Boolean = false
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(0) }

    /** The earliest step still reachable — raised by every checkpoint passed. */
    var floor by remember { mutableIntStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    val answers = remember { mutableStateMapOf<OnboardingField, String>() }
    var showWhyWeAsk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Resume where they got to. Nothing renders until this has been read, so a
    // returning user never sees the first question flash past.
    LaunchedEffect(Unit) {
        val saved = preferencesRepository.onboardingCheckpointFlow.first()
        val savedIndex = Steps.indexOfFirst { it.key == saved }
        if (savedIndex > 0) {
            index = savedIndex
            floor = savedIndex
        }
        restored = true
    }

    if (!restored) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    val step = Steps[index]

    fun back() {
        when {
            // A checkpoint is a one-way door: the demo can't be un-run, and the
            // applause after it can't be un-earned.
            index > floor -> index--
            floor == 0 -> onBackFromStart()
            else -> Unit
        }
    }

    fun forward() {
        // Persist on every step, not once at the end: the keyboard setup sends
        // the user out to system settings, and answers already given shouldn't
        // depend on them coming back.
        scope.launch {
            preferencesRepository.updateOnboardingAnswers(answers.toMap())
            if (index < Steps.lastIndex) {
                val next = index + 1
                index = next
                val arriving = Steps[next]
                if (arriving.checkpoint) {
                    floor = next
                    preferencesRepository.setOnboardingCheckpoint(arriving.key)
                }
            } else {
                // Recorded first, sent second: the flag is what guarantees the
                // user never sees any of this again, and it must not depend on
                // the network.
                preferencesRepository.setOnboardingCompleted()
                onComplete()
                OnboardingSync.syncIfNeeded(context, preferencesRepository)
            }
        }
    }

    BackHandler { back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The demo is pretending to be another app; our chrome would give the
        // game away, so it gets the whole screen.
        if (step != Step.Demo) {
            FlowTopBar(
                progress = (index + 1).toFloat() / Steps.size,
                onBack = ::back,
                onHelp = { showWhyWeAsk = true }
            )
        }

        when (step) {
            is Step.Ask -> QuestionStep(
                question = step.question,
                answer = answers[step.question.field],
                isPro = isPro,
                onAnswer = { answers[step.question.field] = it },
                onContinue = ::forward,
                modifier = Modifier.weight(1f)
            )

            Step.EnableKeyboard -> EnableKeyboardStep(
                onDone = ::forward,
                modifier = Modifier.weight(1f)
            )

            Step.Demo -> DemoChatStep(
                onFinished = ::forward,
                modifier = Modifier.weight(1f)
            )

            Step.Smooth -> SmoothStep(
                onContinue = ::forward,
                modifier = Modifier.weight(1f)
            )

            Step.Done -> DoneStep(
                onStart = ::forward,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showWhyWeAsk) {
        PebbleDialog(
            title = "Why we ask",
            subtitle = "Your answers shape the replies Hook writes for you — who " +
                "you are, who you're talking to, and how forward to be. They stay " +
                "on this device and in the replies we generate for you.",
            onDismiss = { showWhyWeAsk = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                PebbleTextButton(text = "Got it", onClick = { showWhyWeAsk = false })
            }
        }
    }
}

/**
 * A question, its answers, and the sample reply that makes the difference
 * between them visible.
 */
@Composable
private fun QuestionStep(
    question: OnboardingQuestion,
    answer: String?,
    isPro: Boolean,
    onAnswer: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var lockedNotice by remember(question.field) { mutableStateOf<String?>(null) }
    val selected = question.options.firstOrNull { it.value == answer }
    // Before anything is picked, preview the first answer rather than an empty
    // card — the point is to show what these words mean.
    val preview = selected?.preview ?: question.options.firstOrNull()?.preview

    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = question.title,
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (question.subtitle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = question.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Scrolls on its own so a long list — the seven age brackets — can
        // never push the button off the bottom.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (preview != null) {
                item {
                    PreviewCard(text = preview)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            items(question.options) { option ->
                val locked = option.proOnly && !isPro
                AnswerRow(
                    option = option,
                    selected = option.value == answer,
                    showProBadge = locked,
                    onClick = {
                        if (locked) {
                            // Say why nothing happened. A row that ignores taps
                            // in silence reads as broken, not as locked.
                            lockedNotice = option.label
                        } else {
                            lockedNotice = null
                            onAnswer(option.value)
                        }
                    }
                )
            }
        }

        lockedNotice?.let { label ->
            Text(
                text = "\"$label\" is part of Hook Pro — you can unlock it right " +
                    "after setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 10.dp)
            )
        }

        PebbleButton(
            text = question.action,
            tone = PebbleTone.SLATE,
            // Nothing to continue *to* until the question is answered.
            enabled = answer != null,
            onClick = onContinue,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun AnswerRow(
    option: AnswerOption,
    selected: Boolean,
    showProBadge: Boolean,
    onClick: () -> Unit
) {
    PebbleOption(
        text = option.label,
        selected = selected,
        selectedTone = PebbleTone.SLATE,
        onClick = onClick,
        // The badge is the whole explanation of why this row won't take: it is
        // a Pro answer, and this user isn't Pro yet.
        trailing = if (showProBadge) {
            { ProBadge() }
        } else {
            null
        }
    )
}

/** The "this is what your replies will sound like" card. */
@Composable
private fun PreviewCard(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color = MaterialTheme.colorScheme.surface, shape = shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = shape
            )
            .padding(16.dp)
    ) {
        Text(
            text = "Example",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The system setup, as a checklist that fills itself in.
 *
 * Each item is something Android can actually be asked about, so the ticks are
 * real state rather than "we sent you to Settings, so let's assume". The button
 * is always the next unfinished item, and only becomes "Continue" once there
 * isn't one.
 */
@Composable
private fun EnableKeyboardStep(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val setup = rememberKeyboardSetup()
    var photoAsked by remember { mutableStateOf(false) }
    var notificationsAsked by remember { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { photoAsked = true }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAsked = true
        if (granted) ScreenshotDetectionService.startIfAllowed(context)
    }

    LaunchedEffect(setup.notifications, setup.selected) {
        if (setup.notifications && setup.selected) {
            ScreenshotDetectionService.startIfAllowed(context)
        }
    }

    val tasks = listOf(
        SetupTask(
            title = "Turn on the Hook keyboard",
            detail = "In Settings, under on-screen keyboards",
            done = setup.enabled,
            action = "Open Settings",
            onAction = { PermissionUtils.openKeyboardSettings(context) }
        ),
        SetupTask(
            title = "Switch to Hook",
            detail = "Pick Hook from the keyboard chooser",
            done = setup.selected,
            action = "Choose Hook",
            onAction = { PermissionUtils.showKeyboardPicker(context) }
        ),
        SetupTask(
            title = "Allow access to your photos",
            detail = if (photoAsked && !setup.photos) {
                "Android said no — tap to open Hook's settings and allow photos"
            } else {
                "So Hook can read the screenshots you take"
            },
            done = setup.photos,
            action = "Allow Photo Access",
            // A second refusal makes the system dialog stop appearing at all,
            // so from then on the only way through is app settings.
            onAction = {
                if (photoAsked && !setup.photos) {
                    PermissionUtils.openAppSettings(context)
                } else {
                    photoLauncher.launch(PermissionUtils.photoPermission)
                }
            }
        ),
        SetupTask(
            title = "Keep the Hook notification on",
            detail = if (notificationsAsked && !setup.notifications) {
                "Android said no — tap to open Hook's notification settings"
            } else {
                "Hook watches for screenshots while you're in another app, and " +
                    "Android only lets it while a notification says so"
            },
            done = setup.notifications,
            action = "Allow Notifications",
            // Below Android 13 there is no dialog to show, and after a refusal
            // there is no longer one — either way, settings is the only route.
            onAction = {
                val permission = PermissionUtils.notificationPermission
                if (permission != null && !notificationsAsked) {
                    notificationLauncher.launch(permission)
                } else {
                    PermissionUtils.openNotificationSettings(context)
                }
            }
        )
    )
    val nextIndex = tasks.indexOfFirst { !it.done }
    val next = tasks.getOrNull(nextIndex)

    // Nothing left to ask for: move on by itself. Making someone tap Continue
    // on a screen where every line is already ticked is just a toll booth.
    LaunchedEffect(next == null) {
        if (next == null) {
            delay(700)  // let the last tick land where they can see it
            onDone()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Text(
            text = "How to enable the Hook keyboard",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(28.dp))

        tasks.forEachIndexed { position, task ->
            SetupTaskRow(
                number = position + 1,
                task = task,
                current = position == nextIndex,
                showConnector = position < tasks.lastIndex
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        PebbleButton(
            text = next?.action ?: "Continue",
            tone = PebbleTone.SLATE,
            onClick = next?.onAction ?: onDone
        )

        Spacer(modifier = Modifier.height(12.dp))

        // The keyboard doesn't always show up in the chooser straight away;
        // this is the escape hatch rather than a dead end.
        Text(
            text = "Don't see the Hook keyboard?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { PermissionUtils.openAppSettings(context) }
                .padding(vertical = 10.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

private data class SetupTask(
    val title: String,
    val detail: String,
    val done: Boolean,
    val action: String,
    val onAction: () -> Unit
)

@Composable
private fun SetupTaskRow(
    number: Int,
    task: SetupTask,
    current: Boolean,
    showConnector: Boolean
) {
    val done = task.done
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = when {
                            done -> DoneGreen
                            current -> MaterialTheme.colorScheme.onBackground
                            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (current) {
                            MaterialTheme.colorScheme.background
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done || !current) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                }
            )
            Text(
                text = task.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The tick colour. Green reads as "done" everywhere, in either theme. */
private val DoneGreen = Color(0xFF34C759)

@Composable
private fun DoneStep(
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Congratulations\nyour custom keyboard is set up!",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "You can change any of this later in settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        PebbleButton(
            text = "Let's get started!",
            tone = PebbleTone.SLATE,
            onClick = onStart
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/** Back chevron, progress, and the "why are you asking me this" escape hatch. */
@Composable
private fun FlowTopBar(
    progress: Float,
    onBack: () -> Unit,
    onHelp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(4.dp)
                .size(24.dp)
        )

        ProgressTrack(
            progress = progress,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = "Why we ask",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onHelp)
                .padding(4.dp)
                .size(24.dp)
        )
    }
}

/** The thin filled bar between the chevron and the help icon. */
@Composable
private fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "progress"
    )
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .height(7.dp)
            .clip(shape)
            .background(
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                shape = shape
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(7.dp)
                .background(color = MaterialTheme.colorScheme.onBackground, shape = shape)
        )
    }
}
