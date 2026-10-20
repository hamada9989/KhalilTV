Package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.ui.theme.decorSecondary
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.R
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard

/**
 * Cold-start welcome surface.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
) {
    // فرض الاتجاه الأفقي للواجهة برمجياً
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    val config = LocalConfiguration.current
    val isTv = com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor().isTv
    val twoColumn = !isTv && config.screenWidthDp >= 720 && config.screenHeightDp < 720

    if (twoColumn) WelcomeTwoColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress)
    else WelcomeSingleColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress)
}

@Composable
private fun WelcomeSingleColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandBlock()
            Spacer(Modifier.height(22.dp))
            SupportedTypesGroup(alignStart = false)
            Spacer(Modifier.height(22.dp))
            SyncCard(inProgress = googleSignInInProgress, onClick = onSignInWithGoogle)
            if (onSignInWithGoogle != null) Spacer(Modifier.height(12.dp))
            ConnectServerRow(onClick = onConnectServer)
            Spacer(Modifier.height(4.dp))
            SkipRow(onSkip = onSkip)
        }
    }
}

@Composable
private fun WelcomeTwoColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        WelcomeTwoColumnRow(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress)
    }
}

@Composable
private fun WelcomeTwoColumnRow(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            BrandBlock(alignStart = true)
            Spacer(Modifier.height(20.dp))
            SupportedTypesGroup(alignStart = true)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .widthIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            SyncCard(inProgress = googleSignInInProgress, onClick = onSignInWithGoogle)
            if (onSignInWithGoogle != null) Spacer(Modifier.height(12.dp))
            ConnectServerRow(onClick = onConnectServer)
            Spacer(Modifier.height(4.dp))
            SkipRow(onSkip = onSkip)
        }
    }
}

@Composable
private fun BrandBlock(alignStart: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        BrandLogo()
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Khalil TV",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Your IPTV & Media Hub",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.textAccent,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Android TV · Phone · Tablet",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupportedTypesGroup(alignStart: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SupportedTypeRow(icon = Icons.Filled.Key, label = "Dispatcharr Direct Connect", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Tv, label = "Xtream Codes", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Description, label = "M3U + EPG", alignStart = alignStart)
    }
}

@Composable
private fun SyncCard(inProgress: Boolean, onClick: (() -> Unit)?) {
    if (onClick == null) return
    SourceTypeCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !inProgress, onClick = onClick),
        icon = Icons.Filled.CloudOff,
        title = "Sync via Google Account",
        subtitle = "Sign in to mirror playlists, watch progress, reminders, and preferences across your devices.",
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.decorSecondary),
                )
                Text(
                    text = if (inProgress) "..." else "Off",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
    )
}

@Composable
private fun ConnectServerRow(onClick: () -> Unit) {
    SourceTypeCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        icon = Icons.Outlined.Hub,
        title = "Connect a Server",
        subtitle = null,
        trailing = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SkipRow(onSkip: () -> Unit) {
    TextButton(onClick = onSkip) {
        Text(
            text = "Skip for now",
            color = MaterialTheme.colorScheme.textAccent,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BrandLogo() {
    val accent = MaterialTheme.colorScheme.primary
    val haloSize = 84.dp
    val imageSize = 56.dp
    Box(
        modifier = Modifier.size(haloSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(haloSize)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accent.copy(alpha = 0.55f),
                        0.35f to accent.copy(alpha = 0.28f),
                        0.7f to accent.copy(alpha = 0.06f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                    radius = size.minDimension / 2f,
                ),
                center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                radius = size.minDimension / 2f,
            )
        }
        Image(
            painter = painterResource(id = R.drawable.aerio_logo),
            contentDescription = null,
            modifier = Modifier.size(imageSize),
        )
    }
}

@Composable
private fun WelcomeAmbientOrbs(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        val tlCenter = Offset(-50.dp.toPx(), -30.dp.toPx())
        val tlRadius = 360.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to primary.copy(alpha = 0.18f),
                    0.4f to primary.copy(alpha = 0.10f),
                    0.75f to primary.copy(alpha = 0.03f),
                    1.0f to Color.Transparent,
                ),
                center = tlCenter,
                radius = tlRadius,
            ),
            center = tlCenter,
            radius = tlRadius,
        )
        val brCenter = Offset(size.width + 50.dp.toPx(), size.height - 80.dp.toPx())
        val brRadius = 320.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to secondary.copy(alpha = 0.16f),
                    0.4f to secondary.copy(alpha = 0.08f),
                    0.75f to secondary.copy(alpha = 0.02f),
                    1.0f to Color.Transparent,
                ),
                center = brCenter,
                radius = brRadius,
            ),
            center = brCenter,
            radius = brRadius,
        )
    }
}

@Composable
private fun accentBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
    ),
)

@Composable
private fun GradientIcon(
    imageVector: ImageVector,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                },
        )
    }
}

@Composable
private fun SupportedTypeRow(icon: ImageVector, label: String, alignStart: Boolean = false) {
    val gradient = accentBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            10.dp,
            if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        ),
    ) {
        GradientIcon(
            imageVector = icon,
            brush = gradient,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
