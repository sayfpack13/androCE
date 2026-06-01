package com.androce.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.AppPrefs
import com.androce.core.virtual.VirtualSpaceManager
import com.androce.ui.theme.Background
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.Surface
import com.androce.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog that lists all installed device apps and allows cloning them into the virtual space.
 * Also shows already-cloned apps with launch/uninstall options.
 */
@Composable
fun VirtualAppInstallDialog(
    onDismiss: () -> Unit,
    onAppLaunched: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<VirtualSpaceManager.VirtualAppMetadata>>(emptyList()) }
    var availableApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Load installed virtual apps
            installedApps = VirtualSpaceManager.getInstalledVirtualApps(context)

            // Load all launchable apps (apps that appear in the launcher)
            val pm = context.packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(launchIntent, 0)
            val apps = resolveInfos
                .mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    // Exclude androCE itself
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = try {
                        resolveInfo.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }

            availableApps = apps
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("Virtual Space — Install App", color = OnBackground, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search apps...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* handled by filter */ })
                )

                Spacer(Modifier.height(12.dp))

                if (loading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary, modifier = Modifier.size(32.dp))
                    }
                } else {
                    // --- Already installed section ---
                    if (installedApps.isNotEmpty()) {
                        Text(
                            "Installed in Virtual Space",
                            color = Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.height((installedApps.size * 56).coerceAtMost(200).dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(installedApps) { app ->
                                VirtualAppRow(
                                    appName = app.appName,
                                    packageName = app.packageName,
                                    onLaunch = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                VirtualSpaceManager.launchApp(context, app.packageName, app.appName)
                                            }
                                            onAppLaunched()
                                        }
                                    },
                                    onUninstall = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                VirtualSpaceManager.uninstallApp(context, app.packageName)
                                                installedApps = VirtualSpaceManager.getInstalledVirtualApps(context)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // --- Available to install section ---
                    val filtered = if (searchQuery.isBlank()) availableApps
                        else availableApps.filter { (pkg, label) ->
                            label.contains(searchQuery, ignoreCase = true) ||
                                pkg.contains(searchQuery, ignoreCase = true)
                        }

                    val installedPkgSet = installedApps.map { it.packageName }.toSet()
                    val toInstall = filtered.filter { it.first !in installedPkgSet }

                    Text(
                        "Available to Install (${toInstall.size})",
                        color = OnSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.height((toInstall.size * 48).coerceAtMost(300).dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(toInstall) { (pkg, label) ->
                            AvailableAppRow(
                                appName = label,
                                packageName = pkg,
                                installing = installing,
                                onInstall = {
                                    scope.launch {
                                        installing = true
                                        val ok = withContext(Dispatchers.IO) {
                                            VirtualSpaceManager.installAppToVirtualSpace(context, pkg)
                                        }
                                        if (ok) {
                                            installedApps = withContext(Dispatchers.IO) {
                                                VirtualSpaceManager.getInstalledVirtualApps(context)
                                            }
                                        }
                                        installing = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Primary)
            }
        }
    )
}

@Composable
private fun VirtualAppRow(
    appName: String,
    packageName: String,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(appName, color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(packageName, color = OnSurface, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onLaunch, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Launch", tint = Primary)
        }
        IconButton(onClick = onUninstall, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = OnSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun AvailableAppRow(
    appName: String,
    packageName: String,
    installing: Boolean,
    onInstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Background)
            .clickable { if (!installing) onInstall() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(appName, color = OnBackground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(packageName, color = OnSurface, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (installing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Primary)
        } else {
            Icon(Icons.Default.Add, contentDescription = "Install", tint = Primary, modifier = Modifier.size(20.dp))
        }
    }
}
