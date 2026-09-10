package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import lv.bolwarra.wetter.ui.screens.WeatherDomain
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Which page you are on: Today, Week, Month.
 *
 * A pill, centred, sized to its words rather than stretched across the screen —
 * a control this small has no business being as wide as the content it switches.
 *
 * Only the selected segment is filled, because the one thing this is here to say
 * is which page you are looking at; lighting all three would say nothing. The
 * fill is a raised ground rather than the accent, and that is deliberate:
 * precipitation owns the only saturated hue in this app, and a permanent
 * coloured block sitting directly above the rain chart would compete with the
 * thing it is there to make you look at.
 *
 * ### One fill that travels, rather than three that switch
 *
 * There used to be a background on every segment, lit for the selected one. That
 * made changing page a cut: one pill went out, another came on, and nothing said
 * the two were related. Meanwhile the page underneath slid sideways, so the
 * control and the content were describing the same movement in two different
 * languages.
 *
 * Now there is a single fill, and it moves. It is the same object arriving
 * somewhere else, which is what actually happened. It travels on the same spring
 * the pages do, so the pill and the page are one gesture rather than two events
 * that happen to coincide.
 *
 * It also carries the direction for free. Picking Week from Today sends the fill
 * right because Week is to the right, and the page arrives from the right for
 * the same reason.
 *
 * Nothing else lights up. The segments carry no ripple, because a flash under
 * the finger is a second animation arguing with the first — and it fires on a
 * tap whether or not the selection changed, which says something happened when
 * nothing did. The fill arriving is the feedback, and the label brightening
 * under it is the confirmation.
 *
 * ### It has to be measured, because the words are not the same length
 *
 * "Today", "Week" and "Month" are three different widths, so the fill cannot be
 * a third of the track. Each segment reports where it landed and how wide it is,
 * and the fill animates to that. The consequence is that the fill cannot be
 * drawn until the first measurement has happened — which is why it is composed
 * only once its target is known, so it starts in the right place instead of
 * flying in from the left edge on the first frame.
 */
@Composable
fun DomainSwitcher(
    selected: WeatherDomain,
    onSelect: (WeatherDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val pill = RoundedCornerShape(percent = 50)

    // Where each segment ended up, in pixels along the track. Absolute rather
    // than start-relative, so this holds in a right-to-left layout: the position
    // reported here and the offset applied below are measured the same way.
    var segments by remember { mutableStateOf(emptyMap<WeatherDomain, Segment>()) }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .height(TRACK_HEIGHT)
                .clip(pill)
                .background(colors.surfaceSunken)
                .border(width = 1.dp, color = colors.hairline, shape = pill)
                .padding(TRACK_INSET),
        ) {
            segments[selected]?.let { target -> TravellingFill(target, pill) }

            Row(
                modifier = Modifier.fillMaxHeight().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeatherDomain.entries.forEach { domain ->
                    val isSelected = domain == selected
                    // Faded rather than cut, so a label does not brighten before
                    // the fill has arrived under it.
                    val ink by animateColorAsState(
                        targetValue = if (isSelected) colors.textPrimary else colors.textTertiary,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "domain ink",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .onGloballyPositioned { coordinates ->
                                val here = Segment(
                                    x = coordinates.positionInParent().x.roundToInt(),
                                    width = coordinates.size.width,
                                )
                                if (segments[domain] != here) {
                                    segments = segments + (domain to here)
                                }
                            }
                            .clip(pill)
                            .selectable(
                                selected = isSelected,
                                // No ripple. The default indication flashes the
                                // segment under the finger, which is a second
                                // thing lighting up at the exact moment the fill
                                // is trying to be the only thing that moved - and
                                // it fires on the tapped segment whether or not
                                // the selection changed. The fill arriving is the
                                // feedback; a flash competing with it says the
                                // control has two ideas about what just happened.
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(domain) },
                            )
                            .padding(horizontal = SEGMENT_PADDING),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(domain.label),
                            style = WetterTheme.type.body,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = ink,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The fill, on its way to wherever it now belongs.
 *
 * Composed only once a target exists, so the first frame is a placement rather
 * than an animation. Both the position and the width are animated: the words are
 * different lengths, so a fill that only slid would be the wrong size for most
 * of every journey.
 */
@Composable
private fun TravellingFill(target: Segment, shape: RoundedCornerShape) {
    // The pages use slideInHorizontally, whose default is this spring. Matching
    // it is the whole point - the fill and the page are one movement.
    val motion = spring<Int>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val x by animateIntAsState(target.x, motion, label = "domain fill x")
    val width by animateIntAsState(target.width, motion, label = "domain fill width")

    Box(
        Modifier
            .offset { IntOffset(x, 0) }
            .width(with(LocalDensity.current) { width.toDp() })
            .fillMaxHeight()
            .clip(shape)
            .background(WetterTheme.colors.surfaceRaised),
    )
}

/** Where one segment sits along the track, and how much of it it takes. */
private data class Segment(val x: Int, val width: Int)

private val TRACK_HEIGHT = 38.dp
private val TRACK_INSET = 3.dp

/** Enough that a fully rounded end never crowds the word inside it. */
private val SEGMENT_PADDING = 20.dp
