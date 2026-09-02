package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * A rule exactly one physical pixel tall.
 *
 * 1.dp is three pixels on a modern phone, which reads as a border. Dividing by
 * the display density gives the thinnest line the screen can draw — the weight a
 * drawn instrument uses, and the reason sections here can be separated by a line
 * instead of being boxed into cards.
 */
@Composable
fun HairlineRule(modifier: Modifier = Modifier, color: Color = WetterTheme.colors.hairline) {
    val onePixel = with(LocalDensity.current) { (1f / density).dp }
    Box(
        modifier
            .fillMaxWidth()
            .height(onePixel)
            .background(color),
    )
}
