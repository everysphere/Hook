package com.tomfricks.hook.ui.screens.paywall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.tomfricks.hook.billing.BillingManager
import com.tomfricks.hook.billing.BillingOperation
import com.tomfricks.hook.billing.OfferingsState
import com.tomfricks.hook.billing.PurchaseResult
import com.tomfricks.hook.ui.theme.isPebbleDark
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TERMS_URL = "https://hook.tomlin7.com/terms"
private const val PRIVACY_URL = "https://hook.tomlin7.com/privacy"

private val PRO_BENEFITS = listOf(
    "Unlimited text suggestions",
    "Get more matches + dates",
    "Improve your texting game"
)

/**
 * The paywall route.
 *
 * Deliberately hand-rolled rather than RevenueCat's dashboard-configured
 * Paywall: the layout below is the one we want, and the hosted templates can't
 * reproduce it. Packages, prices and the purchase flow still come straight from
 * the SDK — nothing about the plan line-up is hardcoded, it renders whatever the
 * current offering says.
 */
@Composable
fun PaywallScreen(
    onNavigateBack: () -> Unit
) {
    val isAvailable by BillingManager.isAvailable.collectAsState()

    // Keyed on isAvailable, not Unit: on a cold start the SDK is still being
    // configured when this screen composes, both refreshes no-op, and the
    // offerings would sit at Idle forever — a spinner with nothing behind it.
    // Re-running once the SDK is up is what gets the plans on screen.
    LaunchedEffect(isAvailable) {
        if (!isAvailable) return@LaunchedEffect
        BillingManager.refreshCustomerInfo()
        BillingManager.refreshOfferings()
    }

    UnlockPaywall(onNavigateBack = onNavigateBack)
}

// ---------------------------------------------------------------------------
// Palette
//
// The paywall runs a flatter, neutral-grey palette of its own rather than the
// app's blue-tinted Pebble scheme: the design is white cards on near-white with
// a near-black ink, and Pebble's blue casts a tint through every shadow and
// border. Dark-theme values come from the app scheme so the screen still flips.
// ---------------------------------------------------------------------------

private class PaywallColors(
    /** Near-black: headings, ticks, selected borders, the CTA. */
    val ink: Color,
    /** Secondary grey: subtitle, review handle, the Restore link. */
    val subtle: Color,
    /** Hairline grey: unselected plan borders and the empty radio ring. */
    val hairline: Color,
    /** Card and bottom-panel fill. */
    val panel: Color,
    /** Fill behind the chosen plan. */
    val selectedFill: Color,
    /** Background wash behind the scrolling half, top to bottom. */
    val backdrop: List<Color>,
    /** Sits on top of [ink] — the tick inside a filled dot, the CTA label. */
    val onInk: Color,
    /** The CTA's own vertical gradient. */
    val ctaGradient: List<Color>
)

@Composable
private fun paywallColors(): PaywallColors {
    val scheme = MaterialTheme.colorScheme
    return if (isPebbleDark) {
        PaywallColors(
            ink = scheme.onBackground,
            subtle = scheme.onSurfaceVariant,
            hairline = scheme.outline,
            panel = scheme.surface,
            selectedFill = scheme.onSurface.copy(alpha = 0.10f),
            backdrop = listOf(scheme.surface, scheme.background),
            onInk = scheme.surface,
            ctaGradient = listOf(scheme.onBackground, scheme.onBackground)
        )
    } else {
        PaywallColors(
            ink = Color(0xFF1C1C1E),
            subtle = Color(0xFF8A8A8E),
            hairline = Color(0xFFD8D8DC),
            panel = Color(0xFFFFFFFF),
            selectedFill = Color(0xFFF2F2F4),
            backdrop = listOf(Color(0xFFFAFAFB), Color(0xFFEDEDF0)),
            onInk = Color(0xFFFFFFFF),
            ctaGradient = listOf(Color(0xFF44444A), Color(0xFF2B2B30), Color(0xFF1F1F23))
        )
    }
}

/** The orange of the review stars. */
private val StarGold = Color(0xFFFF9F0A)

@Composable
private fun UnlockPaywall(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val uriHandler = LocalUriHandler.current
    val colors = paywallColors()

    val isPro by BillingManager.isPro.collectAsState()
    val offeringsState by BillingManager.offeringsState.collectAsState()
    val isAvailable by BillingManager.isAvailable.collectAsState()

    // Both owned by the BillingManager singleton rather than this composition,
    // so a purchase that outlives an Activity recreation still reports back.
    val isWorking by BillingManager.purchaseInFlight.collectAsState()
    val outcome by BillingManager.lastOutcome.collectAsState()

    var selectedPackage by remember { mutableStateOf<Package?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // The guarantee: whatever happened, and whether or not this screen was alive
    // when it happened, the user is told about it.
    LaunchedEffect(outcome) {
        val finished = outcome ?: return@LaunchedEffect
        statusMessage = when (val result = finished.result) {
            is PurchaseResult.Success -> when (finished.operation) {
                BillingOperation.PURCHASE ->
                    "You're on ${BillingManager.PRO_DISPLAY_NAME}. Enjoy."

                BillingOperation.RESTORE ->
                    "${BillingManager.PRO_DISPLAY_NAME} restored."
            }
            // Backing out of the Play sheet is a choice, not a failure. Say
            // nothing at all rather than showing an error for it.
            is PurchaseResult.Cancelled -> null
            is PurchaseResult.Failed -> result.message
        }
        BillingManager.consumeOutcome()
    }

    val isLoadingOfferings =
        offeringsState is OfferingsState.Idle || offeringsState is OfferingsState.Loading

    // Whatever the dashboard's current offering contains, ordered shortest
    // period first (weekly → yearly) rather than by a fixed list.
    val packages = remember(offeringsState) {
        val all = (offeringsState as? OfferingsState.Ready)
            ?.offering
            ?.availablePackages
            .orEmpty()
            .sortedBy { it.planRank() }

        // Monthly is deliberately not offered on this screen: the two-card
        // layout is a straight weekly-vs-yearly choice, and a third option in
        // the middle is what makes people leave without picking anything. It
        // still comes back if it is somehow the only thing the offering has,
        // because an empty paywall is worse than an unplanned one.
        all.filterNot { it.planKey() == PlanKey.MONTHLY }.ifEmpty { all }
    }
    val bestValue = remember(packages) {
        if (packages.size > 1) packages.maxByOrNull { it.planRank() } else null
    }
    val discounts = remember(packages) { discountPercents(packages) }

    LaunchedEffect(packages) {
        if (selectedPackage == null || packages.none { it.identifier == selectedPackage?.identifier }) {
            selectedPackage = bestValue ?: packages.firstOrNull()
        }
    }

    // Last line of defence against the post-purchase entitlement race: even if
    // BillingManager.purchase() gave up before the entitlement landed, the
    // listener still flips isPro afterwards. However it arrives, Pro arriving
    // while this screen is open means the purchase worked — drop whatever error
    // copy is on screen and show the same success line the purchase path does.
    val wasProOnEntry = remember { isPro }
    LaunchedEffect(isPro) {
        if (isPro && !wasProOnEntry) {
            statusMessage = "You're on ${BillingManager.PRO_DISPLAY_NAME}. Enjoy."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors.backdrop))
    ) {
        // ---- Scrolling top half: pitch and social proof ----------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // No close button, by design: the reference screen leads with the
            // Restore link alone and Android's back gesture already pops this
            // route, so there is no dead end to rescue.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, end = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (isWorking) "Working…" else "Restore",
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            // The recovery route for anyone who already paid —
                            // reachable whatever else on this screen went wrong.
                            if (isWorking) return@clickable
                            statusMessage = null
                            BillingManager.startRestore()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.subtle
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Unlock ${BillingManager.PRO_DISPLAY_NAME}",
                style = MaterialTheme.typography.displaySmall,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Proven to get more matches + dates",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 16.sp,
                color = colors.subtle,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            ReviewsCarousel(colors = colors)

            Spacer(modifier = Modifier.height(44.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PRO_BENEFITS.forEach { benefit ->
                    BenefitRow(text = benefit, colors = colors)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }

        // ---- Pinned bottom panel: plans, CTA, the legal small print ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // A flat top edge — the panel is separated from the wash above
                // by a soft shadow, not by a corner radius.
                .shadow(
                    elevation = 10.dp,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                    clip = false
                )
                .background(color = colors.panel)
                .padding(horizontal = 16.dp)
                .padding(top = 24.dp, bottom = 12.dp)
        ) {
            when {
                isPro -> {
                    Text(
                        text = "You're on ${BillingManager.PRO_DISPLAY_NAME}. " +
                            "Thanks for the support.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ContinueButton(
                        text = "Done",
                        showChevron = false,
                        colors = colors,
                        onClick = onNavigateBack
                    )
                }

                !isAvailable -> {
                    Text(
                        text = "Subscriptions aren't available in this build yet. " +
                            "Check back after the next update.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.subtle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                packages.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingOfferings) {
                            CircularProgressIndicator(color = colors.ink)
                        } else {
                            Text(
                                text = "Couldn't load the plans. " +
                                    "Check your connection and try again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.subtle,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    PlanPicker(
                        packages = packages,
                        selected = selectedPackage,
                        discounts = discounts,
                        colors = colors,
                        onSelect = { selectedPackage = it }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = colors.ink,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "No Commitment - Cancel Anytime",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.ink
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val chosen = selectedPackage
                    ContinueButton(
                        text = when {
                            isWorking -> "Working…"
                            chosen != null && chosen.hasFreeTrial() -> "Start free trial 🙌"
                            else -> "Continue 🙌"
                        },
                        showChevron = !isWorking,
                        colors = colors,
                        onClick = {
                            if (isWorking) return@ContinueButton
                            if (activity == null || chosen == null) {
                                statusMessage = "Couldn't start the purchase. Try again."
                                return@ContinueButton
                            }
                            statusMessage = null
                            // Deliberately not launched from a composition scope —
                            // see BillingManager.startPurchase.
                            BillingManager.startPurchase(activity, chosen)
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = headlinePrice(chosen),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        color = colors.subtle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val message = statusMessage
            if (message != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.subtle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Google Play requires the renewal terms to be visible on the screen
            // where the subscription is offered. Sized to sit under the fold of
            // the reference layout without competing with it.
            Text(
                text = billingDisclosure(selectedPackage),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = colors.subtle.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FooterLink("Terms", colors) { uriHandler.openUri(TERMS_URL) }
                Spacer(modifier = Modifier.width(20.dp))
                FooterLink("Privacy", colors) { uriHandler.openUri(PRIVACY_URL) }
            }
        }
    }
}

@Composable
private fun FooterLink(text: String, colors: PaywallColors, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        fontSize = 11.sp,
        color = colors.subtle.copy(alpha = 0.8f)
    )
}

/**
 * The near-black call to action: full-bleed pill, centred label, chevron pinned
 * to the right edge, and the app's press-spring so it still feels like Hook.
 */
@Composable
private fun ContinueButton(
    text: String,
    showChevron: Boolean,
    colors: PaywallColors,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cta_scale"
    )
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isPressed) 12.dp else 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.30f),
                clip = false
            )
            .background(brush = Brush.verticalGradient(colors.ctaGradient), shape = shape)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.16f), shape = shape)
            .clip(shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .height(60.dp)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onInk,
            textAlign = TextAlign.Center
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onInk,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(28.dp)
            )
        }
    }
}

/**
 * The plans, side by side when there are two or three of them — the shape the
 * design is built around — and stacked once there are more than a row can hold
 * legibly.
 */
@Composable
private fun PlanPicker(
    packages: List<Package>,
    selected: Package?,
    discounts: Map<String, Int>,
    colors: PaywallColors,
    onSelect: (Package) -> Unit
) {
    if (packages.size in 2..3) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            packages.forEach { availablePackage ->
                PlanCard(
                    availablePackage = availablePackage,
                    selected = availablePackage.identifier == selected?.identifier,
                    discountPercent = discounts[availablePackage.identifier],
                    colors = colors,
                    onClick = { onSelect(availablePackage) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            packages.forEach { availablePackage ->
                PlanCard(
                    availablePackage = availablePackage,
                    selected = availablePackage.identifier == selected?.identifier,
                    discountPercent = discounts[availablePackage.identifier],
                    colors = colors,
                    onClick = { onSelect(availablePackage) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * One selectable plan: period, the price normalised to a weekly rate, a radio
 * dot, and — when it's cheaper per week than the priciest plan — a discount pill
 * straddling the top edge. Flat by design: borders, no shadow.
 */
@Composable
private fun PlanCard(
    availablePackage: Package,
    selected: Boolean,
    discountPercent: Int?,
    colors: PaywallColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedness by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180),
        label = "plan_selectedness"
    )
    val border = lerp(colors.hairline, colors.ink, selectedness)
    val fill = lerp(colors.panel, colors.selectedFill, selectedness)
    val shape = RoundedCornerShape(14.dp)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // Room for the discount pill to straddle the top edge.
                .padding(top = 10.dp)
                .defaultMinSize(minHeight = 66.dp)
                .clip(shape)
                .background(color = fill)
                .border(width = if (selected) 2.dp else 1.dp, color = border, shape = shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = availablePackage.planTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = availablePackage.weeklyRateLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1
                )
                if (availablePackage.hasFreeTrial()) {
                    Text(
                        text = "Free trial",
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 14.sp,
                        color = colors.subtle,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            SelectionDot(selected = selected, colors = colors)
        }

        if (discountPercent != null) {
            DiscountPill(
                percent = discountPercent,
                colors = colors,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

/** Filled tick when chosen, hollow ring when not. */
@Composable
private fun SelectionDot(selected: Boolean, colors: PaywallColors) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .then(
                if (selected) {
                    Modifier.background(color = colors.ink, shape = CircleShape)
                } else {
                    Modifier.border(width = 2.dp, color = colors.hairline, shape = CircleShape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.onInk,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** The dark "81% OFF" pill that sits on the top edge of a discounted plan. */
@Composable
private fun DiscountPill(percent: Int, colors: PaywallColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color = colors.ink, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = "$percent% OFF",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onInk
        )
    }
}

@Composable
private fun BenefitRow(text: String, colors: PaywallColors) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(color = colors.ink, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.onInk,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            color = colors.ink
        )
    }
}

/** A single testimonial shown in the [ReviewsCarousel]. */
private data class Review(
    val headline: String,
    val body: String,
    val handle: String,
    val rating: Int = 5
)

// Placeholder testimonial copy — safe for the owner to edit or replace with
// real reviews. These are illustrative only, not attributed to real users.
private val REVIEWS = listOf(
    Review(
        headline = "actually works lol",
        body = "before i averaged 1 match/week, now i get like 7 matches/week. i use " +
            "it to spam likes right before going to sleep lmao",
        handle = "rizzgod1"
    ),
    Review(
        headline = "no more dry texts",
        body = "i used to overthink every reply for ten minutes. now it takes ten " +
            "seconds and the convo actually goes somewhere.",
        handle = "matt_parr"
    ),
    Review(
        headline = "matched date in 3 days",
        body = "the tones are unreal. funny when i want funny, smooth when i want " +
            "smooth. my matches think i got way funnier overnight.",
        handle = "abhinav.s"
    )
)

/**
 * Auto-advancing testimonials carousel with the neighbouring cards peeking in
 * at the edges. Purely decorative social proof — it wraps back to the first card
 * and never blocks the purchase flow.
 */
@Composable
private fun ReviewsCarousel(colors: PaywallColors) {
    val pagerState = rememberPagerState(pageCount = { REVIEWS.size })

    // Drift to the next card every few seconds, looping at the end.
    LaunchedEffect(pagerState) {
        while (true) {
            delay(3500)
            val next = (pagerState.currentPage + 1) % REVIEWS.size
            pagerState.animateScrollToPage(next)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 40.dp),
            pageSpacing = 20.dp
        ) { page ->
            ReviewCard(review = REVIEWS[page], colors = colors)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Page indicator dots, mirroring the onboarding/demo pattern.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(REVIEWS.size) { index ->
                val active = pagerState.currentPage == index
                val alpha by animateFloatAsState(
                    targetValue = if (active) 0.45f else 0.18f,
                    animationSpec = tween(300),
                    label = "review_indicator_alpha"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(7.dp)
                        .background(color = colors.ink.copy(alpha = alpha), shape = CircleShape)
                )
            }
        }
    }
}

@Composable
private fun ReviewCard(review: Review, colors: PaywallColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.14f),
                clip = false
            )
            .background(color = colors.panel, shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Text(
            text = review.headline,
            style = MaterialTheme.typography.titleLarge,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(review.rating) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = StarGold,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = review.body,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = colors.ink
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = review.handle,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = colors.subtle
        )
    }
}

/** True when the product's default subscription option opens with a free phase. */
private fun Package.hasFreeTrial(): Boolean = product.defaultOption?.freePhase != null

/**
 * Plans are keyed off [PackageType] when the dashboard uses RevenueCat's
 * predefined identifiers, and off the identifier text when it uses custom ones
 * (our dashboard's offering uses plain `weekly` / `monthly` / `yearly`, which
 * arrive as [PackageType.CUSTOM]).
 *
 * Multi-month variants are matched before plain monthly so "two_month" doesn't
 * get swallowed by the "month" test.
 */
private fun Package.planKey(): PlanKey {
    val id = identifier.lowercase()
    return when {
        packageType == PackageType.LIFETIME || id.contains("lifetime") -> PlanKey.LIFETIME
        packageType == PackageType.WEEKLY || id.contains("week") -> PlanKey.WEEKLY
        packageType == PackageType.TWO_MONTH || id.contains("two_month") || id.contains("2month") ->
            PlanKey.TWO_MONTH
        packageType == PackageType.THREE_MONTH || id.contains("three_month") || id.contains("3month") ->
            PlanKey.THREE_MONTH
        packageType == PackageType.SIX_MONTH || id.contains("six_month") || id.contains("6month") ->
            PlanKey.SIX_MONTH
        packageType == PackageType.ANNUAL || id.contains("year") || id.contains("annual") ->
            PlanKey.ANNUAL
        packageType == PackageType.MONTHLY || id.contains("month") -> PlanKey.MONTHLY
        else -> PlanKey.OTHER
    }
}

private fun Package.planRank(): Int = planKey().rank

private fun Package.planTitle(): String = planKey().title

/**
 * Shortest period first, so the list reads weekly → yearly.
 *
 * [weeks] is how many weeks the plan bills for, used to put every plan on the
 * same per-week footing. Null where a weekly rate is meaningless (lifetime) or
 * unknown (a package shape we don't recognise).
 */
private enum class PlanKey(
    val rank: Int,
    val title: String,
    val periodLabel: String?,
    val weeks: Double?
) {
    WEEKLY(0, "Weekly", "week", 1.0),
    MONTHLY(1, "Monthly", "month", WEEKS_PER_MONTH),
    TWO_MONTH(2, "2 Months", "2 months", 2 * WEEKS_PER_MONTH),
    THREE_MONTH(3, "3 Months", "3 months", 3 * WEEKS_PER_MONTH),
    SIX_MONTH(4, "6 Months", "6 months", 6 * WEEKS_PER_MONTH),
    ANNUAL(5, "Yearly", "year", 12 * WEEKS_PER_MONTH),
    LIFETIME(6, "Lifetime", null, null),
    OTHER(7, BillingManager.PRO_DISPLAY_NAME, "billing period", null)
}

/** 365.25 / 12 / 7 — the average month, so a year lands on 52.18 weeks. */
private const val WEEKS_PER_MONTH = 4.348214285714286

/** The plan's price divided down to what it costs per week, in micros. */
private fun Package.weeklyMicros(): Double? {
    val weeks = planKey().weeks ?: return null
    if (weeks <= 0.0) return null
    return product.price.amountMicros / weeks
}

/**
 * "₹189.86/wk" — every plan expressed in the same unit so they can be compared
 * at a glance, which is the whole point of the two-card layout. Falls back to
 * the store's own formatted price for anything without a weekly rate (lifetime,
 * unrecognised packages) or when the currency can't be formatted locally.
 */
private fun Package.weeklyRateLabel(): String {
    val price = product.price
    // The weekly plan's own formatted price is already the weekly rate, and the
    // store's string beats anything we'd reconstruct (it drops empty decimals).
    if (planKey() == PlanKey.WEEKLY) return "${price.formatted}/wk"

    val weekly = weeklyMicros() ?: return price.formatted
    val formatted = formatMoney(weekly, price.currencyCode) ?: return price.formatted
    return "$formatted/wk"
}

/**
 * How much cheaper each plan is per week than the most expensive one, keyed by
 * package identifier.
 *
 * Comparing normalised weekly rates rather than a fixed yearly-vs-monthly pair
 * means the badge is right whatever the dashboard's offering contains. Anything
 * under 5% off isn't worth a badge.
 */
private fun discountPercents(packages: List<Package>): Map<String, Int> {
    val weeklyRates = packages.mapNotNull { pkg -> pkg.weeklyMicros()?.let { pkg.identifier to it } }
    val baseline = weeklyRates.maxOfOrNull { it.second } ?: return emptyMap()
    if (baseline <= 0.0) return emptyMap()

    return weeklyRates.mapNotNull { (identifier, rate) ->
        val percent = ((baseline - rate) / baseline * 100).roundToInt()
        if (percent >= 5) identifier to percent else null
    }.toMap()
}

/**
 * Format an amount in micros as money in [currencyCode], dropping the decimals
 * when they'd all be zeroes (₹9,900 rather than ₹9,900.00). Returns null when
 * the currency isn't one the platform knows, so callers can fall back to the
 * store's own string.
 */
private fun formatMoney(amountMicros: Double, currencyCode: String): String? {
    val amount = amountMicros / 1_000_000.0
    val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull() ?: return null
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
    format.currency = currency

    val isWhole = abs(amount - amount.roundToLong()) < 0.005
    format.minimumFractionDigits = if (isWhole) 0 else 2
    format.maximumFractionDigits = if (isWhole) 0 else 2
    return runCatching { format.format(amount) }.getOrNull()
}

/**
 * The one-line price summary under the CTA: what actually gets charged, and the
 * weekly rate again in brackets when it isn't the same number.
 */
private fun headlinePrice(selected: Package?): String {
    if (selected == null) return " "
    val price = selected.product.price.formatted
    val key = selected.planKey()
    if (key == PlanKey.LIFETIME) return "One-time payment of $price"

    val period = key.periodLabel ?: "billing period"
    val weekly = selected.weeklyRateLabel()
    return if (key == PlanKey.WEEKLY) {
        "Just $price/$period"
    } else {
        "Just $price/$period ($weekly)"
    }
}

/**
 * The Play-policy disclosure: what it costs, how often it bills, that it keeps
 * renewing, and how to stop it.
 */
private fun billingDisclosure(selected: Package?): String {
    val pro = BillingManager.PRO_DISPLAY_NAME
    if (selected == null) {
        return "$pro is an auto-renewing subscription billed through Google Play. " +
            "It renews automatically until you cancel it in Google Play › Subscriptions."
    }

    val price = selected.product.price.formatted
    val key = selected.planKey()
    if (key == PlanKey.LIFETIME) {
        return "$pro Lifetime is a one-time purchase of $price through Google Play. " +
            "It does not renew."
    }

    val period = key.periodLabel ?: "billing period"
    val trialSentence = if (selected.hasFreeTrial()) {
        "Your free trial converts to a paid subscription unless you cancel before it ends. "
    } else {
        ""
    }

    return "$pro is an auto-renewing subscription. $trialSentence" +
        "You'll be charged $price per $period through Google Play. It renews automatically " +
        "for the same price and period until you cancel, at least 24 hours before the current " +
        "period ends, in Google Play › Subscriptions."
}

/** Walk the Compose context chain to the hosting Activity — RevenueCat needs one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
