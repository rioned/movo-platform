package com.movo.customer.parcel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movo.customer.R

val MovoGreen = Color(0xFF1FAE59)
val MovoGreenPressed = Color(0xFF16874A)
val MovoGreenLight = Color(0xFF2ED573)
val BackgroundDark = Color(0xFF0E1412)
val SurfaceDark = Color(0xFF182019)
val SurfaceElevated = Color(0xFF1E2A22)
val TextPrimary = Color(0xFFF5F7F6)
// Existing account/tracking screens consume this token directly. Keep their
// secondary copy readable when the system switches to the new light palette.
val TextSecondary: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9FB3A8) else Color(0xFF52685D)
val Divider = Color(0xFF26332C)
val DisabledBg = Color(0xFF2A332D)
val DisabledText = Color(0xFF6B7A72)
val ErrorRed = Color(0xFFE5484D)
val ButtonWhite = Color.White
private val Inter = FontFamily(Font(R.font.inter))

@Composable fun ParcelTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 56.sp),
        displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 36.sp),
        headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 32.sp),
        headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp),
        headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp),
        labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp),
        labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    )
    val darkColors = darkColorScheme(
        primary = MovoGreen, onPrimary = BackgroundDark,
        primaryContainer = SurfaceElevated, onPrimaryContainer = MovoGreenLight,
        secondary = MovoGreenLight, onSecondary = BackgroundDark,
        secondaryContainer = SurfaceElevated, onSecondaryContainer = TextPrimary,
        tertiary = MovoGreenLight, background = BackgroundDark, onBackground = TextPrimary,
        surface = SurfaceDark, onSurface = TextPrimary, surfaceVariant = SurfaceElevated,
        onSurfaceVariant = TextSecondary, outline = TextSecondary, outlineVariant = Divider,
        surfaceContainer = SurfaceDark, surfaceContainerHigh = SurfaceElevated,
        surfaceContainerHighest = SurfaceElevated, surfaceContainerLow = SurfaceDark,
        surfaceContainerLowest = BackgroundDark, surfaceBright = SurfaceElevated, surfaceDim = BackgroundDark,
        error = ErrorRed, onError = BackgroundDark,
        errorContainer = Color(0xFF442328), onErrorContainer = Color(0xFFFFDADD)
    )
    val lightColors = lightColorScheme(
        primary = MovoGreenPressed, onPrimary = ButtonWhite,
        primaryContainer = Color(0xFFD8F3E3), onPrimaryContainer = Color(0xFF084A30),
        secondary = Color(0xFF23674C), onSecondary = ButtonWhite,
        secondaryContainer = Color(0xFFE3EFE7), onSecondaryContainer = Color(0xFF163C2B),
        tertiary = Color(0xFF23674C), background = Color(0xFFF3F7F3), onBackground = Color(0xFF12271D),
        surface = Color(0xFFFCFEFC), onSurface = Color(0xFF12271D),
        surfaceVariant = Color(0xFFE4EEE6), onSurfaceVariant = Color(0xFF52685D),
        outline = Color(0xFF6E8476), outlineVariant = Color(0xFFCEDDD1),
        surfaceContainer = Color(0xFFEEF4ED), surfaceContainerHigh = Color(0xFFE7F0E7),
        surfaceContainerHighest = Color(0xFFE1EBE2), surfaceContainerLow = Color(0xFFF7FAF6),
        surfaceContainerLowest = Color.White, surfaceBright = Color.White, surfaceDim = Color(0xFFDDE8DD),
        error = Color(0xFFBA2633), onError = Color.White,
        errorContainer = Color(0xFFFFDADD), onErrorContainer = Color(0xFF650B1A)
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors,
        typography = typography, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = { TopAppBar(title = {
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = if (title == "MOVO") colors.primary else colors.onBackground)
            if (title == "MOVO") Text("KIGALI · PARCELS BY MOTO", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, letterSpacing = 1.sp)
        }
    }, navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp).background(colors.surfaceContainerHigh, CircleShape)) {
            Icon(if (title == "MOVO") Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack, if (title == "MOVO") "Open menu" else "Back")
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)) }, containerColor = colors.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = .22f), colors.background))).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** White on #1FAE59 is only 2.89:1. Its supplied pressed token gives 4.57:1. */
@Composable fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = CircleShape,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp, disabledElevation = 0.dp),
        border = if (enabled) BorderStroke(1.dp, Color.White.copy(alpha = .18f)) else null,
        colors = ButtonDefaults.buttonColors(containerColor = MovoGreenPressed, contentColor = ButtonWhite, disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))) {
        Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable fun MenuRow(title: String, subtitle: String = "", onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val icon = when {
        title.contains("Payment", true) -> Icons.Default.Payments
        title.contains("place", true) || title.contains("address", true) || title in listOf("Pickup", "Drop-off") -> Icons.Default.LocationOn
        title.contains("Safety", true) || title.contains("Support", true) -> Icons.Default.Shield
        title.contains("Recipient", true) || title.contains("Profile", true) -> Icons.Default.Person
        title.contains("Notification", true) -> Icons.Default.Notifications
        else -> Icons.Default.Inventory2
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(colors.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable fun EmptyState(text: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Inventory2, "Empty list", tint = MovoGreen, modifier = Modifier.size(36.dp))
            Text(text, color = TextSecondary)
        }
    }
}
