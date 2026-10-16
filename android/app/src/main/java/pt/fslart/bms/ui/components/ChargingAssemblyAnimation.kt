package pt.fslart.bms.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.ui.theme.*
import kotlin.math.*
import kotlin.random.Random

/**
 * Dialog Modal showing the cinematic exploded battery assembly and lightning strike animation.
 */
@Composable
fun ChargingAssemblyModal(
    bmsState: BmsState,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF090D14))
                    .border(1.5.dp, LartYellow.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                ChargingAssemblyContent(
                    bmsState = bmsState,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

/**
 * Main Interactive Animated Component:
 * 1. Exploded view: Lid and screws floating high, 6 segments levitating.
 * 2. Assembly: Segments slide into place, lid lowers, screws spiral down and tighten.
 * 3. Lightning: Case seals, electric shockwave radiates, and a vivid lightning bolt strikes!
 */
@Composable
fun ChargingAssemblyContent(
    bmsState: BmsState,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Animation Progress: 0.0f (Exploded) -> 1.0f (Mounted & Lightning Strike)
    var isPlaying by remember { mutableStateOf(true) }
    var restartTrigger by remember { mutableStateOf(0) }
    var is3DMode by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(restartTrigger) {
        webViewRef?.evaluateJavascript("if (typeof restartAnimation === 'function') restartAnimation();", null)
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3600, easing = LinearEasing)
        )
    }

    // Secondary infinite pulse for continuous electric energy after strike
    val infiniteTransition = rememberInfiniteTransition(label = "sparks")
    val sparkPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkPulse"
    )

    val progress = animProgress.value

    // Status description based on animation phase
    val stageText = when {
        progress < 0.25f -> if (is3DMode) "3D · BATERIA ABERTA (SEM TAMPA)" else "VISTA EXPLODIDA · BATERIA ABERTA (SEM TAMPA)"
        progress < 0.75f -> "A DESCER TAMPA SUPERIOR DO ACUMULADOR..."
        progress < 0.88f -> "A FIXAR PARAFUSOS M6 · SDC FECHADO"
        else -> "ACUMULADOR SELADO · CARGA ATIVA (+30.0 A)"
    }

    val stageColor = when {
        progress < 0.25f -> Color(0xFF0A84FF)
        progress < 0.75f -> Color(0xFFFF9500)
        progress < 0.88f -> LartYellow
        else -> StatusOk
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // TOP HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(LartYellow.copy(alpha = 0.2f))
                        .border(1.dp, LartYellow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = "Carga",
                        tint = LartYellow,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "ANIMAÇÃO DE CARGA · MODELOS 3D CAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Montagem da Bateria & Tampa Superior",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = DarkTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // VIEW MODE TOGGLE PILL: [ 🧊 3D CAD Real | ⚡ 2.5D Esquemático ]
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (is3DMode) LartYellow else Color.Transparent)
                    .clickable { is3DMode = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = if (is3DMode) Color.Black else DarkTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "3D CAD SolidWorks",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (is3DMode) Color.Black else DarkTextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!is3DMode) LartYellow else Color.Transparent)
                    .clickable { is3DMode = false }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = if (!is3DMode) Color.Black else DarkTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "2.5D Esquemático",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!is3DMode) Color.Black else DarkTextSecondary
                    )
                }
            }
        }

        // STATUS BADGE PILL
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(stageColor.copy(alpha = 0.12f))
                .border(1.dp, stageColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stageText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = stageColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        // 3D MODEL-VIEWER / 2.5D EXPLODED BATTERY & LIGHTNING STRIKE
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF05080D))
                .border(1.dp, Color(0xFF1B2432), RoundedCornerShape(14.dp))
        ) {
            if (is3DMode) {
                AndroidView(
                    factory = { ctx ->
                        createCharging3DWebView(ctx) { webViewRef = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawBatteryAssemblyScene(
                        progress = progress,
                        sparkPulse = sparkPulse,
                        packVoltage = bmsState.pack.voltage,
                        tempMax = bmsState.pack.tempMax
                    )
                }
            }
        }

        // HUD TELEMETRY ROW
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TENSÃO TOTAL", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = "%.1f V".format(bmsState.pack.voltage),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = LartYellow,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CORRENTE CARGA", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = if (progress >= 0.85f) "+30.0 A" else "0.0 A",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (progress >= 0.85f) StatusOkGlow else DarkTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("POTÊNCIA", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = if (progress >= 0.85f) "+17.6 kW" else "0.0 kW",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (progress >= 0.85f) Color.White else DarkTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ESTADO TSAC", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = if (progress >= 0.85f) "CARGA ON ⚡" else if (progress >= 0.65f) "A SELAR" else "EXPLODIDO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (progress >= 0.85f) StatusOk else Color(0xFFFF9500),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // CONTROLS: REPLAY BUTTON & CLOSE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { restartTrigger++ },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurfaceElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, LartYellow),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.Replay, contentDescription = "Repetir", tint = LartYellow, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Repetir Montagem 🔄", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LartYellow)
            }

            if (onDismiss != null) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = LartYellow, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Continuar", tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Continuar ⚡", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

/**
 * Custom 2.5D Isometric Canvas Renderer for the Exploded & Assembled Battery
 */
private fun DrawScope.drawBatteryAssemblyScene(
    progress: Float,
    sparkPulse: Float,
    packVoltage: Float,
    tempMax: Float
) {
    val cx = size.width / 2f
    val cy = size.height * 0.65f

    // Grid Dimensions
    val trayW = 280f
    val trayH = 80f
    val trayDepth = 150f

    // Phase Calculations (0.0 -> 1.0)
    // 1. Segments Elevation (high when exploded, drops to 0 at progress 0.3 -> 0.6)
    val segAssemblyFactor = ((progress - 0.25f) / 0.32f).coerceIn(0f, 1f)
    val segElev = (1f - cubicEaseOut(segAssemblyFactor)) * 140f

    // 2. Lid Elevation (very high when exploded, drops to 0 at progress 0.55 -> 0.78)
    val lidAssemblyFactor = ((progress - 0.55f) / 0.22f).coerceIn(0f, 1f)
    val lidElev = (1f - bounceEaseOut(lidAssemblyFactor)) * 220f

    // 3. Screws Elevation & Spin (fly in at progress 0.70 -> 0.84)
    val screwFactor = ((progress - 0.70f) / 0.14f).coerceIn(0f, 1f)
    val screwElev = (1f - cubicEaseOut(screwFactor)) * 160f
    val screwRotation = screwFactor * 720f

    // -------------------------------------------------------------
    // A. DRAW BACKGROUND CYBERNETIC GRID
    // -------------------------------------------------------------
    val gridColor = Color(0xFF0F1722)
    for (i in -4..4) {
        val x = cx + i * 50f
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
    for (j in 0..6) {
        val y = j * 50f
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }

    // -------------------------------------------------------------
    // B. DRAW BATTERY LOWER TRAY / CHASSIS (Base Box)
    // -------------------------------------------------------------
    val baseColor = Color(0xFF131A24)
    val baseBorderColor = Color(0xFF283648)

    // Base Floor Plate
    drawIsometricBox(
        center = Offset(cx, cy),
        w = trayW,
        d = trayDepth,
        h = trayH,
        topColor = Color(0xFF17202C),
        leftColor = Color(0xFF0F161E),
        rightColor = Color(0xFF1C2735),
        strokeColor = baseBorderColor
    )

    // Mounting Feet / Aluminum Brackets on base
    val bracketColor = Color(0xFF7A8B9E)
    drawRect(bracketColor, Offset(cx - trayW * 0.55f, cy + 10f), Size(18f, 14f))
    drawRect(bracketColor, Offset(cx + trayW * 0.45f, cy + 10f), Size(18f, 14f))

    // -------------------------------------------------------------
    // C. DRAW 6 LITHIUM BATTERY SEGMENTS
    // -------------------------------------------------------------
    val segW = 68f
    val segD = 52f
    val segH = 46f

    val segOffsets = listOf(
        // Row 1 (Back row: segments 1, 2, 3)
        Triple(-75f, -32f, 1),
        Triple(0f, -32f, 2),
        Triple(75f, -32f, 3),
        // Row 2 (Front row: segments 4, 5, 6)
        Triple(-75f, 28f, 4),
        Triple(0f, 28f, 5),
        Triple(75f, 28f, 6)
    )

    segOffsets.forEachIndexed { index, (ox, oy, segId) ->
        // Individual staggered bounce
        val staggerOffset = (index * 0.03f)
        val segProgress = ((progress - 0.25f - staggerOffset) / 0.28f).coerceIn(0f, 1f)
        val thisSegElev = (1f - cubicEaseOut(segProgress)) * 140f

        val segCenter = Offset(cx + ox, cy + oy - thisSegElev - trayH * 0.3f)

        // Exploded guide line down to tray when floating
        if (thisSegElev > 15f) {
            drawLine(
                color = Color(0xFF00E5FF).copy(alpha = 0.35f),
                start = segCenter,
                end = Offset(cx + ox, cy + oy - trayH * 0.3f),
                strokeWidth = 1.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            )
        }

        // Battery Segment Body
        val isHottest = segId == 2
        val segColorTop = if (isHottest) Color(0xFF8B6B10) else Color(0xFF1B4028)
        val segColorSide = if (isHottest) Color(0xFF5E4809) else Color(0xFF102819)

        drawIsometricBox(
            center = segCenter,
            w = segW,
            d = segD,
            h = segH,
            topColor = segColorTop,
            leftColor = segColorSide,
            rightColor = segColorTop.copy(alpha = 0.85f),
            strokeColor = if (isHottest) LartYellow else StatusOkGlow
        )

        // Segment Cylindrical Cells terminals on top (4 miniature gold dots)
        val cellDotColor = if (progress >= 0.85f) Color(0xFFFFD60A) else Color(0xFF32D74B)
        drawCircle(cellDotColor, radius = 3.5f, center = Offset(segCenter.x - 14f, segCenter.y - 8f))
        drawCircle(cellDotColor, radius = 3.5f, center = Offset(segCenter.x + 14f, segCenter.y - 8f))
        drawCircle(cellDotColor, radius = 3.5f, center = Offset(segCenter.x - 14f, segCenter.y + 8f))
        drawCircle(cellDotColor, radius = 3.5f, center = Offset(segCenter.x + 14f, segCenter.y + 8f))

        // Segment ID label
        if (thisSegElev > 20f) {
            drawCircle(Color.Black.copy(alpha = 0.7f), radius = 9f, center = segCenter)
            drawCircle(LartYellow, radius = 9f, center = segCenter, style = Stroke(1.5f))
        }
    }

    // -------------------------------------------------------------
    // D. HIGH VOLTAGE BUSBARS / CABOS DE INTERLIGAÇÃO
    // -------------------------------------------------------------
    if (segElev < 30f) {
        val busbarColor = Color(0xFFFF9500)
        // Copper bars connecting the 6 modules in series
        drawLine(busbarColor, Offset(cx - 75f, cy - 25f), Offset(cx, cy - 25f), strokeWidth = 3f)
        drawLine(busbarColor, Offset(cx, cy - 25f), Offset(cx + 75f, cy - 25f), strokeWidth = 3f)
        drawLine(busbarColor, Offset(cx + 75f, cy - 20f), Offset(cx + 75f, cy + 25f), strokeWidth = 3f)
        drawLine(busbarColor, Offset(cx + 75f, cy + 30f), Offset(cx, cy + 30f), strokeWidth = 3f)
        drawLine(busbarColor, Offset(cx, cy + 30f), Offset(cx - 75f, cy + 30f), strokeWidth = 3f)
    }

    // -------------------------------------------------------------
    // E. BATTERY LID / TAMPA SUPERIOR (Descending and Sealing)
    // -------------------------------------------------------------
    val lidCenter = Offset(cx, cy - trayH - lidElev)

    // Draw the TSAC Lid Plate (Descends smoothly onto chassis)
    val lidColorTop = if (lidElev <= 5f) Color(0xFF2C394A) else Color(0xFF212B37)
    drawIsometricBox(
        center = lidCenter,
        w = trayW * 1.04f,
        d = trayDepth * 1.04f,
        h = 16f,
        topColor = lidColorTop,
        leftColor = Color(0xFF141B24),
        rightColor = Color(0xFF2A3748),
        strokeColor = if (lidElev <= 5f) LartYellow else Color(0xFF435870)
    )

    // Center MSD (Manual Service Disconnect) / Receptacle on the lid
    val msdCenter = Offset(lidCenter.x, lidCenter.y - 10f)
    drawCircle(Color(0xFFFF9500), radius = 11f, center = msdCenter)
    drawCircle(Color.Black, radius = 7f, center = msdCenter)
    drawCircle(Color(0xFFFFD60A), radius = 3.5f, center = msdCenter)

    // High Voltage Danger Symbol on lid (Warning Triangle)
    val triPath = Path().apply {
        moveTo(lidCenter.x - 45f, lidCenter.y - 20f)
        lineTo(lidCenter.x - 30f, lidCenter.y - 20f)
        lineTo(lidCenter.x - 37.5f, lidCenter.y - 35f)
        close()
    }
    drawPath(triPath, color = LartYellow)

    // -------------------------------------------------------------
    // F. 8 PERIMETER SCREWS / PARAFUSOS M6 (Hovering & Screwing in)
    // -------------------------------------------------------------
    val screwPositions = listOf(
        Offset(-trayW * 0.48f, -trayDepth * 0.35f),
        Offset(0f, -trayDepth * 0.45f),
        Offset(trayW * 0.48f, -trayDepth * 0.35f),
        Offset(trayW * 0.48f, 0f),
        Offset(trayW * 0.48f, trayDepth * 0.35f),
        Offset(0f, trayDepth * 0.45f),
        Offset(-trayW * 0.48f, trayDepth * 0.35f),
        Offset(-trayW * 0.48f, 0f)
    )

    val screwColor = Color(0xFFD0D7DE)
    screwPositions.forEach { pos ->
        val sX = lidCenter.x + pos.x
        val sY = lidCenter.y + pos.y - screwElev

        // Screw Head (Hexagonal / Round with cross slot)
        drawCircle(color = screwColor, radius = 4f, center = Offset(sX, sY))
        drawCircle(color = Color(0xFF161B22), radius = 2f, center = Offset(sX, sY))
        // Screw slot angled by rotation
        val rotRad = Math.toRadians(screwRotation.toDouble())
        val dx = (cos(rotRad) * 2.8).toFloat()
        val dy = (sin(rotRad) * 2.8).toFloat()
        drawLine(Color.Black, Offset(sX - dx, sY - dy), Offset(sX + dx, sY + dy), strokeWidth = 1f)
    }
}

/**
 * Helper to draw an isometric 3D rectangular box with shaded faces
 */
private fun DrawScope.drawIsometricBox(
    center: Offset,
    w: Float,
    d: Float,
    h: Float,
    topColor: Color,
    leftColor: Color,
    rightColor: Color,
    strokeColor: Color? = null
) {
    val hw = w / 2f
    val hd = d / 4f

    // 1. Top Face (Rhombus)
    val topPath = Path().apply {
        moveTo(center.x, center.y - hd)
        lineTo(center.x + hw, center.y)
        lineTo(center.x, center.y + hd)
        lineTo(center.x - hw, center.y)
        close()
    }
    drawPath(topPath, color = topColor)
    if (strokeColor != null) {
        drawPath(topPath, color = strokeColor, style = Stroke(1.2f))
    }

    // 2. Left Face
    val leftPath = Path().apply {
        moveTo(center.x - hw, center.y)
        lineTo(center.x, center.y + hd)
        lineTo(center.x, center.y + hd + h)
        lineTo(center.x - hw, center.y + h)
        close()
    }
    drawPath(leftPath, color = leftColor)
    if (strokeColor != null) {
        drawPath(leftPath, color = strokeColor.copy(alpha = 0.5f), style = Stroke(1.2f))
    }

    // 3. Right Face
    val rightPath = Path().apply {
        moveTo(center.x, center.y + hd)
        lineTo(center.x + hw, center.y)
        lineTo(center.x + hw, center.y + h)
        lineTo(center.x, center.y + hd + h)
        close()
    }
    drawPath(rightPath, color = rightColor)
    if (strokeColor != null) {
        drawPath(rightPath, color = strokeColor.copy(alpha = 0.5f), style = Stroke(1.2f))
    }
}

// Easing Functions
private fun cubicEaseOut(t: Float): Float {
    val f = t - 1f
    return f * f * f + 1f
}

private fun bounceEaseOut(t: Float): Float {
    return if (t < 1f / 2.75f) {
        7.5625f * t * t
    } else if (t < 2f / 2.75f) {
        val f = t - 1.5f / 2.75f
        7.5625f * f * f + 0.75f
    } else if (t < 2.5f / 2.75f) {
        val f = t - 2.25f / 2.75f
        7.5625f * f * f + 0.9375f
    } else {
        val f = t - 2.625f / 2.75f
        7.5625f * f * f + 0.984375f
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createCharging3DWebView(
    context: Context,
    onWebViewCreated: ((WebView) -> Unit)? = null
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(android.graphics.Color.parseColor("#05080D"))

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val path = request.url.path ?: ""
                if (path.endsWith(".glb", ignoreCase = true)) {
                    val assetPath = path.substringAfter("/assets/")
                    try {
                        val stream = context.assets.open(assetPath)
                        val headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, OPTIONS",
                            "Access-Control-Allow-Headers" to "*",
                            "Content-Type" to "model/gltf-binary"
                        )
                        return WebResourceResponse("model/gltf-binary", null, 200, "OK", headers, stream)
                    } catch (e: Exception) {
                        android.util.Log.e("Charging3DViewer", "Failed to open asset: $assetPath", e)
                    }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("Charging3D_JS", "${consoleMessage?.message()} [line ${consoleMessage?.lineNumber()}]")
                return true
            }
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        loadUrl("https://appassets.androidplatform.net/assets/charging_3d_viewer.html")
        onWebViewCreated?.invoke(this)
    }
}
