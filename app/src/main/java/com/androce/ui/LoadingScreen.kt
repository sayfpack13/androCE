package com.androce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.ui.components.SpinningLoader
import com.androce.ui.components.rememberPulsingAlpha
import com.androce.ui.theme.Background
import com.androce.ui.theme.Primary

@Composable
fun LoadingScreen(
    message: String = "Initializing..."
) {
    val alpha = rememberPulsingAlpha()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SpinningLoader(
                size = 48.dp,
                color = Primary,
                strokeWidth = 4.dp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "androCE",
                color = Primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.alpha(alpha)
            )
        }
    }
}
