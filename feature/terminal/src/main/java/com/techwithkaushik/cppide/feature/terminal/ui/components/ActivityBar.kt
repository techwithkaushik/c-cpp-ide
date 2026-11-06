package com.techwithkaushik.cppide.feature.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.feature.terminal.ui.theme.*

enum class ActivityDestination(val glyph: String, val label: String) {
    EXPLORER("▤", "Explorer"), SEARCH("⌕", "Search"), OUTLINE("☷", "Outline"), UBUNTU("◉", "Ubuntu")
}

@Composable
fun ActivityBar(
    selected: ActivityDestination,
    onSelect: (ActivityDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.width(42.dp).fillMaxHeight().background(VsCodeSurface),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActivityDestination.entries.forEach { destination ->
            val active = selected == destination
            Box(
                Modifier.width(42.dp).height(48.dp).clickable { onSelect(destination) },
                contentAlignment = Alignment.Center
            ) {
                if (active) Box(
                    Modifier.align(Alignment.CenterStart).width(3.dp).height(30.dp)
                        .background(VsCodeAccent)
                )
                Text(
                    destination.glyph,
                    color = if (active) Color.White else VsCodeMutedText,
                    fontSize = 22.sp
                )
            }
        }
    }
}
