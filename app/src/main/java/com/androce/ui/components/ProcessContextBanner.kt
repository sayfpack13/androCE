package com.androce.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.model.ProcessInfo
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Surface
import com.androce.ui.theme.Warning

/** Compact process line for page TopAppBar — matches [SelectedProcessBanner] content. */
@Composable
fun ProcessTopBarSubtitle(process: ProcessInfo?, modifier: Modifier = Modifier) {
    if (process != null) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(14.dp)
            )
            Column {
                Text(
                    process.displayName(),
                    color = AccentGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "PID ${process.pid} selected",
                    color = OnSurface,
                    fontSize = 11.sp
                )
            }
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(14.dp)
            )
            Text(
                "No process selected",
                color = Warning,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun NoProcessSelectedBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(AppDimensions.cardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppDimensions.paddingLarge),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("No process selected", color = Warning, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Open the Process tab and pick a target app first",
                    color = OnSurface,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun SelectedProcessBanner(process: ProcessInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(AppDimensions.cardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppDimensions.paddingLarge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
            Column {
                Text(process.displayName(), color = AccentGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("PID ${process.pid} selected", color = OnSurface, fontSize = 11.sp)
            }
        }
    }
}

/** @deprecated Use [SelectedProcessBanner] */
@Composable
fun AttachedProcessBanner(process: ProcessInfo, modifier: Modifier = Modifier) {
    SelectedProcessBanner(process, modifier)
}
