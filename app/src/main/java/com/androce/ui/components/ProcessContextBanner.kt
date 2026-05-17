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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.model.ProcessInfo
import com.androce.ui.theme.Accent
import com.androce.ui.theme.Error
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Surface
import com.androce.ui.theme.Warning

@Composable
fun NoProcessSelectedBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("No process selected", color = Warning, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Open the Process tab and pick a target app first",
                    color = OnSurface,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun AttachedProcessBanner(process: ProcessInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Column {
                Text(process.displayName(), color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("PID ${process.pid}", color = OnSurface, fontSize = 11.sp)
            }
        }
    }
}
