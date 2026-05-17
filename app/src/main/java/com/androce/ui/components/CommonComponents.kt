package com.androce.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.ui.theme.Accent
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Background
import com.androce.ui.theme.Error
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.Surface
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.ui.theme.Warning
import com.androce.model.ProcessInfo

// ==================== STANDARDIZED DIMENSIONS ====================
object AppDimensions {
    val cardCornerRadius = 12.dp
    val itemCornerRadius = 10.dp
    val chipCornerRadius = 8.dp
    val buttonCornerRadius = 10.dp
    val inputCornerRadius = 14.dp
    
    val paddingSmall = 4.dp
    val paddingMedium = 8.dp
    val paddingLarge = 12.dp
    val paddingXLarge = 16.dp
    
    val iconSizeSmall = 16.dp
    val iconSizeMedium = 20.dp
    val iconSizeLarge = 22.dp
    val iconSizeXLarge = 24.dp
    
    val cardElevation = 0.dp // Flat design
}

// ==================== STANDARDIZED COLORS ====================
object AppColors {
    val cardBackground = SurfaceVariant
    val cardSelected = Primary.copy(alpha = 0.12f)
    val cardBorderSelected = Primary.copy(alpha = 0.5f)
    val cardBorderFrozen = Accent.copy(alpha = 0.6f)
    val chipBackground = SurfaceHigh
}

// ==================== SCREEN SCAFFOLD ====================

/** Page body below a tab [TopAppBar]; uses scaffold [padding] only (no extra system-bar inset). */
@Composable
fun TabScreenBody(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = AppDimensions.paddingXLarge)
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    selectedProcess: ProcessInfo? = null,
    showProcessContext: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    containerColor: Color = Background,
    content: @Composable () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.heightIn(max = 52.dp),
                windowInsets = WindowInsets(0),
                title = {
                    Column {
                        Text(
                            title,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        when {
                            showProcessContext -> ProcessTopBarSubtitle(selectedProcess)
                            subtitle != null -> Text(
                                subtitle,
                                color = Accent,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Primary,
                                modifier = Modifier.size(AppDimensions.iconSizeLarge)
                            )
                        }
                    }
                },
                actions = { actions?.invoke() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor)
            )
        },
        containerColor = containerColor
    ) { padding ->
        TabScreenBody(padding = padding, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffoldWithLogo(
    actions: @Composable (() -> Unit)? = null,
    containerColor: Color = Background,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Primary, Accent))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
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
                actions = { actions?.invoke() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        },
        containerColor = containerColor
    ) { padding ->
        content(padding)
    }
}

// ==================== STANDARDIZED CARDS ====================
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    frozen: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val backgroundColor = when {
        selected -> AppColors.cardSelected
        else -> AppColors.cardBackground
    }
    val borderColor = when {
        frozen -> AppColors.cardBorderFrozen
        selected -> AppColors.cardBorderSelected
        else -> Color.Transparent
    }
    val borderWidth = if (frozen || selected) 1.5.dp else 0.dp
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.cardCornerRadius))
            .background(backgroundColor)
            .border(borderWidth, borderColor, RoundedCornerShape(AppDimensions.cardCornerRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(AppDimensions.paddingLarge),
        content = { content() }
    )
}

@Composable
fun AppCardWithIcon(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    iconTint: Color = Accent,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    frozen: Boolean = false,
    onClick: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null
) {
    AppCard(
        modifier = modifier,
        selected = selected,
        frozen = frozen,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(AppDimensions.iconSizeMedium)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = OnBackground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    subtitle?.let {
                        Text(
                            it,
                            color = OnSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            actions?.invoke()
        }
    }
}

// ==================== EMPTY STATES ====================
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = SurfaceHigh,
                    modifier = Modifier.size(36.dp)
                )
            }
            Text(
                title,
                color = OnBackground,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                subtitle,
                color = OnSurface,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun EmptyStateWithAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EmptyStateIcon(icon)
            Text(
                title,
                color = OnBackground,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                subtitle,
                color = OnSurface,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                label = actionLabel,
                onClick = onAction,
                variant = ButtonVariant.Primary
            )
        }
    }
}

@Composable
private fun EmptyStateIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = SurfaceHigh,
            modifier = Modifier.size(36.dp)
        )
    }
}

// ==================== BANNERS ====================
@Composable
fun InfoBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Info,
    iconTint: Color = Accent,
    backgroundColor: Color = Surface
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(AppDimensions.cardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppDimensions.paddingLarge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Column {
                Text(title, color = iconTint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(message, color = OnSurface, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun WarningBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(AppDimensions.cardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Warning, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(message, color = OnSurface, fontSize = 12.sp)
            }
        }
    }
}

// ==================== STANDARDIZED INPUTS ====================
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder?.let { { Text(it, color = OnSurface.copy(alpha = 0.4f), fontSize = 14.sp) } },
        label = label?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp)) }
        },
        trailingIcon = trailingIcon?.let {
            {
                IconButton(onClick = { onTrailingClick?.invoke() }) {
                    Icon(it, contentDescription = null, tint = OnSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                }
            }
        },
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppDimensions.inputCornerRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = SurfaceHigh,
            focusedContainerColor = SurfaceVariant,
            unfocusedContainerColor = SurfaceVariant,
            focusedTextColor = OnBackground,
            unfocusedTextColor = OnBackground,
            cursorColor = Primary,
            errorBorderColor = Error
        )
    )
}

@Composable
fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit = {},
    placeholder: String = "Search...",
    modifier: Modifier = Modifier
) {
    AppTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = placeholder,
        leadingIcon = Icons.Default.Search,
        trailingIcon = if (query.isNotEmpty()) Icons.Default.Clear else null,
        onTrailingClick = { onQueryChange("") },
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
    )
}

// ==================== STANDARDIZED BUTTONS ====================
enum class ButtonVariant { Primary, Secondary, Danger, Ghost }

@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val (containerColor, contentColor) = when (variant) {
        ButtonVariant.Primary -> Primary to Color.White
        ButtonVariant.Secondary -> SurfaceHigh to OnBackground
        ButtonVariant.Danger -> Error to Color.White
        ButtonVariant.Ghost -> Color.Transparent to Primary
    }
    
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(AppDimensions.buttonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = SurfaceHigh.copy(alpha = 0.5f),
            disabledContentColor = OnSurface.copy(alpha = 0.3f)
        )
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun AppIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Primary,
    containerColor: Color = Color.Transparent,
    contentDescription: String? = null
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(AppDimensions.iconSizeLarge)
        )
    }
}

@Composable
fun AppTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Primary
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = color, fontSize = 13.sp)
    }
}

// ==================== STANDARDIZED CHIPS ====================
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppDimensions.chipCornerRadius))
            .background(if (selected) Primary.copy(alpha = 0.2f) else SurfaceHigh)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Primary else Color.Transparent,
                shape = RoundedCornerShape(AppDimensions.chipCornerRadius)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            leadingIcon?.let {
                Icon(it, contentDescription = null, tint = if (selected) Primary else OnSurface, modifier = Modifier.size(14.dp))
            }
            Text(
                label,
                color = if (selected) Primary else OnSurface,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

// ==================== LIST COMPONENTS ====================
@Composable
fun <T> AppList(
    items: List<T>,
    key: ((T) -> Any)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AppDimensions.paddingXLarge),
    emptyContent: (@Composable () -> Unit)? = null,
    itemContent: @Composable (T) -> Unit
) {
    if (items.isEmpty() && emptyContent != null) {
        emptyContent()
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(AppDimensions.paddingMedium)
        ) {
            items(
                items = items,
                key = key
            ) { item ->
                itemContent(item)
            }
        }
    }
}

// ==================== LOADING STATES ====================
@Composable
fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    color: Color = Primary
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            color = color,
            strokeWidth = 4.dp
        )
    }
}

@Composable
fun AppLoadingOverlay(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Primary, modifier = Modifier.size(48.dp))
            }
        }
    }
}

// ==================== BADGES ====================
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = AccentGreen
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            "$count",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isActive) AccentGreen else Warning
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
            Text(
                text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
