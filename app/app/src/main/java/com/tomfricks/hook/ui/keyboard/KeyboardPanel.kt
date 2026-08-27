package com.tomfricks.hook.ui.keyboard

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomfricks.hook.keyboard.RizzSession
import com.tomfricks.hook.keyboard.RizzSession.TranscriptItem
import com.tomfricks.hook.ui.theme.HookMarkFlat
import com.tomfricks.hook.ui.theme.PebbleActionPill
import com.tomfricks.hook.ui.theme.PebbleBubble
import com.tomfricks.hook.ui.theme.PebbleIconButton
import com.tomfricks.hook.ui.theme.TypingBubble

/** Height of the keyboard surface — tall enough for a readable screenshot. */
private val PanelHeight = 300.dp

/**
 * The whole keyboard surface, laid out as a chat you can follow up in: each
 * captured screenshot sits on the left, replies land under it on the right, and
 * new screenshots append below as an ongoing thread.
 *
 * Generation has no loading screen on purpose — the transcript stays on screen
 * and an animated typing bubble shows at the bottom while replies are written.
 *
 * Rendered by the IME straight off [RizzSession], so replies that arrived while
 * this keyboard did not exist are already here.
 */
@Composable
fun KeyboardPanel(
    state: RizzSession.Status,
    items: List<TranscriptItem>,
    errorMessage: String?,
    isDarkTheme: Boolean,
    onGenerate: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    /** Long-press a suggestion, confirm, and it lands here. */
    onReportSuggestion: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onSwitchKeyboardClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackspaceClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPro: Boolean = false,
    freeRemaining: Int = 0,
    paywallRequired: Boolean = false,
    onUpgradeClick: () -> Unit = {}
) {
    // Pro is never nagged and never blocked.
    val showUpsell = paywallRequired && !isPro

    // How much of the panel the fade and the controls cover. The bottom bar
    // sizes itself from its pill, so this is measured rather than assumed, and
    // it is what the transcript scrolls through on its way under the fade.
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PanelHeight)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The chat runs the full height of the panel and passes behind the
        // controls — that's what the fade is fading. Laid out under it rather
        // than above it, the transcript would simply stop at the fade's top
        // edge, which reads as a hard band no matter how soft the gradient is.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp)
        ) {
            if (!isPro && !showUpsell) {
                AllowanceChip(
                    remaining = freeRemaining,
                    onClick = onUpgradeClick
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (items.isEmpty()) {
                    // Nothing to talk about yet: no chat, no screenshot card — just
                    // the logo and what to do next. Nothing scrolls, so this is
                    // centred in the clear space instead of under the controls.
                    EmptyState(
                        isDarkTheme = isDarkTheme,
                        title = if (state == RizzSession.Status.ERROR) {
                            "Couldn't read that one"
                        } else {
                            "Take a screenshot"
                        },
                        body = if (state == RizzSession.Status.ERROR) {
                            errorMessage ?: "Something went wrong"
                        } else {
                            "So Hook knows what's on your screen"
                        },
                        modifier = Modifier.padding(bottom = controlsHeight)
                    )
                } else {
                    ChatTranscript(
                        transcript = items,
                        state = state,
                        errorMessage = errorMessage,
                        isDarkTheme = isDarkTheme,
                        onSuggestionClick = onSuggestionClick,
                        onReportSuggestion = onReportSuggestion,
                        // Lets the newest reply settle clear of the controls
                        // while older ones keep scrolling up through the fade.
                        bottomInset = controlsHeight
                    )
                }
            }
        }

        // The controls rest on a dark fade that rises off the bottom of the
        // panel, so the "Rizz" pill and its neighbours read clearly
        // over the chat passing behind them and the chat dissolves into the
        // dark instead of being cut off by it.
        //
        // Drawn edge to edge with the padding on the controls inside it: inset
        // the fade itself and the gradient stops short of the panel, leaving a
        // bright margin around three of its sides.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged {
                    controlsHeight = with(density) { it.height.toDp() }
                }
                .background(
                    Brush.verticalGradient(
                        // Weighted towards the top so most of the height is
                        // spent going from nothing to faint. An even ramp puts
                        // half the darkening in the first few pixels, and that
                        // start edge is exactly what shows up as a seam.
                        0.0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.10f),
                        0.75f to Color.Black.copy(alpha = 0.38f),
                        1.0f to Color.Black.copy(alpha = 0.66f)
                    )
                )
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        ) {
            // The run-up: chat visible through the fade before it is dark
            // enough to sit controls on. Too short and there is no dissolve,
            // only a step. The upsell line sits inside it rather than in place
            // of it, so the fade is the same height either way.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showUpsell) {
                    Text(
                        text = "You're out of free rizz. Go Pro for unlimited.",
                        style = MaterialTheme.typography.bodySmall,
                        // This lands in the near-transparent top of the fade,
                        // so the panel background — not the scrim — is what it
                        // has to read against. White vanished in light theme.
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            KeyboardBottomBar(
                state = state,
                showUpsell = showUpsell,
                onGenerate = onGenerate,
                onUpgradeClick = onUpgradeClick,
                onNewChatClick = onNewChatClick,
                onSwitchKeyboardClick = onSwitchKeyboardClick,
                onSettingsClick = onSettingsClick,
                onBackspaceClick = onBackspaceClick
            )
        }
    }
}

/**
 * The free-generation counter for non-Pro users, tucked into the top-right of
 * the panel. Tapping it opens the paywall in the main app.
 */
@Composable
private fun AllowanceChip(
    remaining: Int,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (remaining > 0) "$remaining free left" else "Go Pro",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(shape)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = shape
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * A captured screenshot, shown as an attachment card in the chat — the way a
 * quoted image sits above the replies in a messaging app.
 */
@Composable
private fun ScreenshotCard(
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .width(210.dp)
            .height(150.dp)
            // Lifted off the transcript a little more than the bubbles are —
            // this is a photo pinned to the chat, not another message in it.
            .shadow(elevation = 8.dp, shape = shape)
            .clip(shape)
            .background(
                color = if (isDarkTheme) Color(0xFF141B2B) else Color(0xFFEDF1F9),
                shape = shape
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            content()
        } else {
            HookMarkFlat(size = 52.dp, contentDescription = "No screenshot yet")
        }
    }
}

/**
 * The confirm step between long-pressing a suggestion and filing a report.
 *
 * There is a step at all because a long-press is easy to trigger by accident
 * while reaching for a bubble, and a report that fires on the press itself
 * would be invisible to the person who caused it.
 */
@Composable
private fun ReportConfirmStrip(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Report this reply?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Report",
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onConfirm)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "Cancel",
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What a reported suggestion turns into.
 *
 * The reply itself is replaced rather than struck through: the user just told
 * us it was offensive, so leaving it on screen — still tappable — would be a
 * strange thing to do next.
 */
@Composable
private fun ReportAcknowledgement() {
    Text(
        text = "Reported. Thanks — we'll take a look.",
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * One vertical chat rendered from the ordered session transcript: screenshots on
 * the left, sent replies and tappable suggestions on the right, and the typing
 * bubble while replies are being written. Multiple screenshots interleave with
 * replies so follow-ups read as one ongoing thread.
 */
@Composable
private fun ChatTranscript(
    transcript: List<TranscriptItem>,
    state: RizzSession.Status,
    errorMessage: String?,
    isDarkTheme: Boolean,
    onSuggestionClick: (String) -> Unit,
    onReportSuggestion: (String) -> Unit,
    bottomInset: Dp = 0.dp
) {
    val listState = rememberLazyListState()
    val showError = state == RizzSession.Status.ERROR && errorMessage != null

    // Which suggestion is asking "report this?" right now, and which have
    // already been reported. Local to the transcript because neither outlives
    // the panel: a report is filed and done, and the acknowledgement only has
    // to survive until the conversation moves on.
    var pendingReport by remember { mutableStateOf<String?>(null) }
    val reportedTexts = remember { mutableStateListOf<String>() }

    LaunchedEffect(transcript.size, showError) {
        val count = transcript.size + if (showError) 1 else 0
        if (count > 0) {
            listState.animateScrollToItem(count)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        items(transcript) { item ->
            when (item) {
                is TranscriptItem.Screenshot -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    ScreenshotCard(isDarkTheme = isDarkTheme) {
                        // Anchor the crop to the top so the start of the
                        // conversation is visible instead of the middle.
                        Image(
                            bitmap = item.bitmap.asImageBitmap(),
                            contentDescription = "Captured screenshot",
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                is TranscriptItem.SentReply -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    PebbleBubble(
                        text = item.text,
                        onClick = null,
                        modifier = Modifier.widthIn(max = 240.dp)
                    )
                }

                is TranscriptItem.Suggestion -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    when {
                        item.text in reportedTexts -> ReportAcknowledgement()

                        item.text == pendingReport -> ReportConfirmStrip(
                            onConfirm = {
                                pendingReport = null
                                reportedTexts.add(item.text)
                                onReportSuggestion(item.text)
                            },
                            onCancel = { pendingReport = null }
                        )

                        else -> PebbleBubble(
                            text = item.text,
                            onClick = { onSuggestionClick(item.text) },
                            // Long-press rather than a visible button on every
                            // bubble: reporting is rare, and a permanent affordance
                            // next to each suggestion would crowd a 300dp panel and
                            // sit in the way of the tap that actually matters.
                            onLongClick = { pendingReport = item.text },
                            modifier = Modifier.widthIn(max = 240.dp)
                        )
                    }
                }

                TranscriptItem.Typing -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TypingBubble()
                }
            }
        }

        if (showError) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.widthIn(max = 240.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyboardBottomBar(
    state: RizzSession.Status,
    showUpsell: Boolean,
    onGenerate: () -> Unit,
    onUpgradeClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onSwitchKeyboardClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackspaceClick: () -> Unit
) {
    val isGenerating = state == RizzSession.Status.GENERATING

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // "+" clears the chat and starts a fresh hidden session.
        PebbleIconButton(
            icon = Icons.Default.Add,
            contentDescription = "New chat",
            onClick = onNewChatClick
        )

        PebbleActionPill(
            text = when {
                showUpsell -> "Go Pro"
                isGenerating -> "Cooking…"
                else -> "Rizz"
            },
            // An IME can't host the Play purchase sheet, so this hands the user
            // to MainActivity on the paywall route instead.
            onClick = if (showUpsell) onUpgradeClick else onGenerate,
            // Locked while a round is cooking so it can't be fired twice.
            enabled = showUpsell || !isGenerating,
            modifier = Modifier.weight(1f)
        )

        // Older Android and many OEM skins have no nav-bar IME switcher, so
        // this globe is the way back to Gboard (or any other keyboard).
        PebbleIconButton(
            icon = Icons.Default.Language,
            contentDescription = "Switch keyboard",
            onClick = onSwitchKeyboardClick
        )

        // Settings moved to their own gear now that "+" starts a new chat.
        PebbleIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "Hook settings",
            onClick = onSettingsClick
        )

        PebbleIconButton(
            icon = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Backspace",
            onClick = onBackspaceClick
        )
    }
}

/**
 * Shown whenever there is nothing to reply to: instruction on top, app mark
 * below it on a soft tile. No chat, no screenshot card.
 */
@Composable
private fun EmptyState(
    isDarkTheme: Boolean,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .size(76.dp)
                .background(
                    color = if (isDarkTheme) Color(0xFF161D2E) else Color.White,
                    shape = RoundedCornerShape(22.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(22.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            HookMarkFlat(size = 48.dp)
        }
    }
}
