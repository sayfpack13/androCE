package com.androce.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.androce.model.ProcessInfo
import com.androce.ui.theme.Accent
import com.androce.ui.theme.Background
import com.androce.ui.theme.Primary
import com.androce.ui.theme.PrimaryDim
import com.androce.ui.theme.Surface
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.viewmodel.ProcessListState
import com.androce.viewmodel.ProcessViewModel
import com.androce.viewmodel.SearchMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProcessListScreen(
    viewModel: ProcessViewModel,
    selectedProcess: ProcessInfo?,
    onProcessSelected: (ProcessInfo) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val filtered by viewModel.filteredProcesses.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    val isLoading = state is ProcessListState.Loading
    val context = LocalContext.current

    // Load processes only if not already loaded
    val currentState = state
    if (currentState is ProcessListState.Idle || (currentState is ProcessListState.Success && currentState.processes.isEmpty())) {
        LaunchedEffect(Unit) { viewModel.loadProcesses(context.packageManager) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(Primary, Accent))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "androCE",
                                color = OnBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "Memory Scanner",
                                color = Primary,
                                fontSize = 10.sp,
                                letterSpacing = 1.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
                actions = {
                    if (!isLoading) {
                        IconButton(onClick = { viewModel.loadProcesses(context.packageManager) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Primary)
                        }
                    }
                }
            )
        },
        containerColor = Background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            
            // Selected Process Card - Always visible when process selected
            if (selectedProcess != null) {
                SelectedProcessCard(selectedProcess)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = {
                    Text(
                        "Search app or PID…",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = OnSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceHigh,
                    focusedContainerColor = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                    focusedTextColor = OnBackground,
                    unfocusedTextColor = OnBackground,
                    cursorColor = Primary
                ),
                singleLine = true
            )

            // Search mode chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                val modes = listOf(
                    SearchMode.ALL     to "All",
                    SearchMode.TITLE   to "App Title",
                    SearchMode.PACKAGE to "Package"
                )
                items(modes.size) { i ->
                    val (mode, label) = modes[i]
                    val selected = searchMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Primary else SurfaceVariant)
                            .border(1.dp, if (selected) Primary else SurfaceHigh, RoundedCornerShape(20.dp))
                            .clickable { viewModel.searchMode.value = mode }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.White else OnSurface,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            when (state) {
                is ProcessListState.Error -> ErrorView((state as ProcessListState.Error).message)
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh = { viewModel.loadProcesses(context.packageManager) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isLoading && filtered.isEmpty()) {
                            LoadingView()
                        } else if (filtered.isEmpty() && query.isNotBlank()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = OnSurface.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
                                    Text("No matches for \"$query\"", color = OnSurface, fontSize = 14.sp)
                                }
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (filtered.isNotEmpty()) {
                                    item {
                                        Text(
                                            "${filtered.size} app${if (filtered.size != 1) "s" else ""}",
                                            color = OnSurface,
                                            fontSize = 11.sp,
                                            modifier = Modifier
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                .background(Background)
                                        )
                                    }
                                }
                                items(
                                    items = filtered,
                                    key = { it.pid }
                                ) { process ->
                                    val isSelected = selectedProcess?.pid == process.pid
                                    ProcessRow(
                                        process = process,
                                        isSelected = isSelected,
                                        onClick = { onProcessSelected(process) },
                                        modifier = Modifier.animateItemPlacement()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Primary,
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 3.dp
                )
                CircularProgressIndicator(
                    color = Accent.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 2.dp
                )
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                "Scanning processes…",
                color = OnSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ErrorView(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceVariant)
                .padding(24.dp)
        ) {
            Text("⚠", fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text("Failed to load processes", color = OnBackground, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun rememberAppIconPainter(packageName: String): Painter? {
    val context = LocalContext.current
    return remember(packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            BitmapPainter(drawable.toBitmap().asImageBitmap())
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun ProcessRow(
    process: ProcessInfo, 
    isSelected: Boolean, 
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Cache expensive calculations
    val iconPainter = rememberAppIconPainter(process.packageName)
    val displayName = remember(process.appName, process.name) {
        process.appName ?: process.name
    }
    val subtitle = remember(process.appName, process.name, process.packageName) {
        if (process.appName != null) process.name else process.packageName
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp) // Fixed height for better recycling
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Primary.copy(alpha = 0.15f) else SurfaceVariant)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Primary else SurfaceHigh.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconPainter != null) {
            Image(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(PrimaryDim.copy(alpha = 0.4f), Accent.copy(alpha = 0.15f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = process.name.take(1).uppercase(),
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = if (isSelected) Primary else OnBackground,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = if (isSelected) Accent else OnSurface,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Selected indicator or PID badge
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${process.pid}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${process.pid}",
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SelectedProcessCard(process: ProcessInfo) {
    val iconPainter = rememberAppIconPainter(process.packageName)
    val displayName = process.appName ?: process.name
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Primary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Selected Process",
                    color = Primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$displayName (PID: ${process.pid})",
                    color = OnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val OnBackground = Color(0xFFF0EEFF)
private val OnSurface = Color(0xFFB0AACC)
