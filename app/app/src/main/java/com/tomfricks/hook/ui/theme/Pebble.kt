package com.tomfricks.hook.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Pebble design system.
 *
 * Every tappable surface in the app (and in the keyboard) is a "pebble": a
 * rounded slab filled with a vertical gradient, wrapped in a coloured glow,
 * that springs inward when pressed. [PebbleTone] picks which gradient is used.
 */
enum class PebbleTone {
    /** Bright dark-blue — primary actions and reply suggestions. */
    BLUE,

    /** Near-black slate — the "Rizz" style command pill. */
    SLATE,

    /** Muted surface tint — settings rows, tips, secondary chrome. */
    MUTED,

    /** Destructive red. */
    DANGER
}

/** True when the active color scheme is a dark one. */
val isPebbleDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

private class PebbleColors(
    val gradient: List<Color>,
    val glow: Color,
    val content: Color,
    val rim: Color
)

@Composable
private fun pebbleColors(tone: PebbleTone): PebbleColors {
    val dark = isPebbleDark
    return when (tone) {
        PebbleTone.BLUE -> PebbleColors(
            gradient = if (dark) {
                listOf(PebbleBlueLight, PebbleBlue, PebbleBlueDeep)
            } else {
                listOf(BubbleOutgoingTop, PebbleBlue, BubbleOutgoingBottom)
            },
            glow = PebbleBlue,
            content = Color.White,
            rim = Color.White.copy(alpha = if (dark) 0.18f else 0.28f)
        )

        PebbleTone.SLATE -> PebbleColors(
            gradient = if (dark) {
                listOf(Color(0xFF2A3346), PebbleSlate, Color(0xFF10141F))
            } else {
                listOf(Color(0xFF39425A), PebbleSlate, Color(0xFF10141F))
            },
            glow = PebbleBlueInk,
            content = Color.White,
            rim = Color.White.copy(alpha = 0.14f)
        )

        PebbleTone.MUTED -> PebbleColors(
            gradient = if (dark) {
                listOf(Color(0xFF1B2233), DarkSurfaceVariant, Color(0xFF111725))
            } else {
                listOf(Color(0xFFFFFFFF), PebbleSlateLight, Color(0xFFDCE3F0))
            },
            glow = if (dark) Color.Black else PebbleBlueDeep,
            content = MaterialTheme.colorScheme.onSurface,
            rim = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.7f)
        )

        PebbleTone.DANGER -> PebbleColors(
            gradient = listOf(Color(0xFFFF6B6E), Error, Color(0xFFB3363A)),
            glow = Error,
            content = Color.White,
            rim = Color.White.copy(alpha = 0.22f)
        )
    }
}

/**
 * The raw pebble slab. Handles the gradient fill, rim highlight, glow shadow
 * and press spring; callers supply the content.
 */
@Composable
// combinedClickable is still marked experimental in this Compose version. It is
// the only way to get a long-press without hand-rolling gesture detection, and
// its signature has been stable for years.
@OptIn(ExperimentalFoundationApi::class)
fun PebbleSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tone: PebbleTone = PebbleTone.BLUE,
    cornerRadius: Dp = 22.dp,
    elevation: Dp = 6.dp,
    contentAlignment: Alignment = Alignment.Center,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val colors = pebbleColors(tone)

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pebble_scale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.55f else 0.3f,
        animationSpec = tween(150),
        label = "pebble_glow"
    )

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isPressed) elevation + 4.dp else elevation,
                shape = shape,
                ambientColor = colors.glow.copy(alpha = glowAlpha * 0.5f),
                spotColor = colors.glow.copy(alpha = glowAlpha),
                clip = false
            )
            .background(brush = Brush.verticalGradient(colors.gradient), shape = shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(colors.rim, Color.Transparent),
                    endY = 40f
                ),
                shape = shape
            )
            .border(width = 1.dp, color = colors.rim, shape = shape)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onLongClick = onLongClick,
                        onClick = onClick ?: {}
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * Full-width primary pebble button.
 *
 * @param subtitle an optional second line under [text], for a button that has
 *   to say what it leads to as well as what it is.
 */
@Composable
fun PebbleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PebbleTone = PebbleTone.BLUE,
    fillWidth: Boolean = true,
    enabled: Boolean = true,
    subtitle: String? = null
) {
    val contentColor = if (tone == PebbleTone.MUTED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.White
    }
    val sized = if (fillWidth) modifier.fillMaxWidth() else modifier
    PebbleSurface(
        // Same idiom as PebbleActionPill: a null onClick makes the surface
        // inert, and the dimming is what says so.
        onClick = if (enabled) onClick else null,
        modifier = if (enabled) sized else sized.alpha(0.5f),
        tone = tone
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 28.dp,
                vertical = if (subtitle == null) 16.dp else 14.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** The dark command pill, e.g. "Rizz". */
@Composable
fun PebbleActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    PebbleSurface(
        // A null onClick makes the surface non-clickable; dim it so the disabled
        // state reads clearly.
        onClick = if (enabled) onClick else null,
        modifier = if (enabled) modifier else modifier.alpha(0.5f),
        tone = PebbleTone.SLATE,
        cornerRadius = 18.dp,
        elevation = 4.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Square pebble holding a single icon — keyboard chrome, nav affordances. */
@Composable
fun PebbleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PebbleTone = PebbleTone.MUTED,
    buttonSize: Dp = 46.dp
) {
    val tint = if (tone == PebbleTone.MUTED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.White
    }
    PebbleSurface(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        tone = tone,
        cornerRadius = 14.dp,
        elevation = 3.dp
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * A blue outgoing chat bubble — used for reply suggestions in the keyboard and
 * in the demo transcript.
 */
@Composable
fun PebbleBubble(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    PebbleSurface(
        onClick = onClick,
        modifier = modifier,
        tone = PebbleTone.BLUE,
        cornerRadius = 20.dp,
        elevation = 5.dp,
        contentAlignment = Alignment.CenterStart,
        onLongClick = onLongClick
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

/** A grey incoming bubble, for demo transcripts. */
@Composable
fun IncomingBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    val dark = isPebbleDark
    Box(
        modifier = modifier
            .background(
                color = if (dark) BubbleIncomingDark else BubbleIncomingLight,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * An incoming-style "typing" bubble: three dots that pulse in sequence, shown
 * at the bottom of the transcript while replies are being generated. Styled to
 * match [IncomingBubble] so it reads as the AI "writing back".
 */
@Composable
fun TypingBubble(
    modifier: Modifier = Modifier
) {
    val dark = isPebbleDark
    val transition = rememberInfiniteTransition(label = "typing")

    Box(
        modifier = modifier
            .background(
                color = if (dark) BubbleIncomingDark else BubbleIncomingLight,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                val dotAlpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 600,
                            delayMillis = index * 160,
                            easing = LinearEasing
                        ),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "typing_dot_$index"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .background(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

/**
 * Themed replacement for [androidx.compose.material3.AlertDialog] — a pebble
 * card instead of the platform popup, so dialogs match the rest of the app.
 */
@Composable
fun PebbleDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth(0.9f)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(28.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(20.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

/**
 * A selectable row inside a [PebbleDialog]: blue pebble when chosen.
 *
 * [trailing] is an optional slot pinned to the right edge — used for the "PRO"
 * badge on subscription-gated choices.
 *
 * [selectedTone] picks the chosen-state fill. Settings dialogs keep the blue;
 * onboarding answers use [PebbleTone.SLATE], where a whole column of blue would
 * shout over the question it belongs to.
 */
@Composable
fun PebbleOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTone: PebbleTone = PebbleTone.BLUE,
    trailing: (@Composable () -> Unit)? = null
) {
    val tone = if (selected) selectedTone else PebbleTone.MUTED
    val contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    PebbleSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        tone = tone,
        cornerRadius = 16.dp,
        elevation = if (selected) 4.dp else 2.dp,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
            trailing?.invoke()
        }
    }
}

/** The "PRO" pill that marks a subscription-gated choice. */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Text(
        text = "PRO",
        modifier = modifier
            .background(color = PebbleBlueDeep, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )
}

/** Flat text action for dialog footers. */
@Composable
fun PebbleTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

/** Title + subtitle settings row, rendered as a muted pebble. */
@Composable
fun PebbleRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tone: PebbleTone = PebbleTone.MUTED,
    trailingIcon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val contentColor = if (tone == PebbleTone.MUTED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.White
    }
    PebbleSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        tone = tone,
        cornerRadius = 18.dp,
        elevation = 3.dp,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
            if (trailingIcon != null) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
