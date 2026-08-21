package com.tensorix.antigravityplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable tooltip composable for Android Compose Material 3.
 * Wrap any UI element with this composable to display a helpful tooltip on long-press.
 * Uses the standard Material 3 TooltipBox API (available on Android).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTooltip(
    tooltipText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    text = tooltipText,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(4.dp)
                )
            }
        },
        state = tooltipState,
        modifier = modifier
    ) {
        content()
    }
}
