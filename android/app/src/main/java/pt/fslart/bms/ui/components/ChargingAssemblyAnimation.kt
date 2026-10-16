package pt.fslart.bms.ui.components

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(restartTrigger) {
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
        progress < 0.28f -> "VISTA EXPLODIDA · BATERIA ABERTA (SEM TAMPA NEM PARAFUSOS)"
        progress < 0.58f -> "A MONTAR 6 SEGMENTOS DE CÉLULAS NO CHASSI..."
        progress < 0.78f -> "A BAIXAR TAMPA SUPERIOR E APERTAR PARAFUSOS M6..."
        progress < 0.88f -> "ACUMULADOR SELADO · SDC FECHADO"
        else -> "⚡ RELÂMPAGO DE CARGA · 588 V ATIVO!"
    }

    val stageColor = when {
        progress < 0.28f -> Color(0xFF0A84FF)
        progress < 0.58f -> Color(0xFFFF9500)
        progress < 0.78f -> LartYellow
        progress < 0.88f -> StatusOk
        else -> Color(0xFFFFD60A)
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
                        text = "ANIMAÇÃO DE CARGA · VISTA EXPLODIDA",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Montagem Automática & Relâmpago",
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
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = stageColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        // CANVAS 2.5D EXPLODED BATTERY & LIGHTNING STRIKE
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF05080D))
                .border(1.dp, Color(0xFF1B2432), RoundedCornerShape(14.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawBatteryAssemblyScene(
                    progress = progress,
                    sparkPulse = sparkPulse,
                    packVoltage = bmsState.pack.voltage,
                    tempMax = bmsState.pack.tempMax
                )
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

    // 4. Lightning Bolt Trigger (strikes at progress >= 0.85)
    val lightningActive = progress >= 0.85f
    val lightningIntensity = if (lightningActive) ((progress - 0.85f) / 0.15f) else 0f

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

    // Exploded alignment laser guides when lid is high up
    if (lidElev > 20f) {
        drawLine(
            color = LartYellow.copy(alpha = 0.3f),
            start = Offset(cx - trayW * 0.45f, cy - trayH - lidElev),
            end = Offset(cx - trayW * 0.45f, cy - trayH),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        )
        drawLine(
            color = LartYellow.copy(alpha = 0.3f),
            start = Offset(cx + trayW * 0.45f, cy - trayH - lidElev),
            end = Offset(cx + trayW * 0.45f, cy - trayH),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        )
    }

    // Draw the TSAC Lid Plate
    val lidColorTop = if (lidElev <= 5f && lightningActive) Color(0xFF2C394A) else Color(0xFF212B37)
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

        // Dashed trajectory line
        if (screwElev > 10f) {
            drawLine(
                color = Color.White.copy(alpha = 0.25f),
                start = Offset(sX, sY),
                end = Offset(sX, lidCenter.y + pos.y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )
        }

        // Screw Head (Hexagonal / Round with cross slot)
        drawCircle(color = screwColor, radius = 4f, center = Offset(sX, sY))
        drawCircle(color = Color(0xFF161B22), radius = 2f, center = Offset(sX, sY))
        // Screw slot angled by rotation
        val rotRad = Math.toRadians(screwRotation.toDouble())
        val dx = (cos(rotRad) * 2.8).toFloat()
        val dy = (sin(rotRad) * 2.8).toFloat()
        drawLine(Color.Black, Offset(sX - dx, sY - dy), Offset(sX + dx, sY + dy), strokeWidth = 1f)
    }

    // -------------------------------------------------------------
    // G. THE LIGHTNING BOLT (O Relâmpagozinho & Onda de Choque!)
    // -------------------------------------------------------------
    if (lightningActive) {
        val targetPoint = Offset(lidCenter.x, lidCenter.y - 10f)

        // 1. Expanding Radial Shockwave Ring
        val shockRadius = 30f + lightningIntensity * 160f
        val shockAlpha = (1f - lightningIntensity).coerceIn(0f, 1f)
        drawCircle(
            color = LartYellow.copy(alpha = shockAlpha * 0.6f),
            radius = shockRadius,
            center = targetPoint,
            style = Stroke(width = 3f * (1f - lightningIntensity) + 1f)
        )
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = shockAlpha * 0.4f),
            radius = shockRadius * 0.75f,
            center = targetPoint,
            style = Stroke(width = 2f)
        )

        // 2. Main Zig-Zag Lightning Bolt Path
        val startY = -20f
        val startX = cx - 15f + (sin(sparkPulse * 20f) * 8f)

        val boltPath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX + 18f, startY + 50f)
            lineTo(startX - 12f, startY + 95f)
            lineTo(startX + 24f, startY + 140f)
            lineTo(startX - 8f, startY + 185f)
            lineTo(targetPoint.x + 6f, targetPoint.y - 30f)
            lineTo(targetPoint.x, targetPoint.y)
        }

        // Branching Lightning Arc
        val branchPath = Path().apply {
            moveTo(startX - 12f, startY + 95f)
            lineTo(startX - 40f, startY + 125f)
            lineTo(startX - 32f, startY + 155f)
        }

        // Layer 1: Outer Neon Aura (Golden Glow)
        drawPath(boltPath, color = Color(0xFFFFD60A).copy(alpha = 0.35f), style = Stroke(width = 16f, cap = StrokeCap.Round))
        drawPath(branchPath, color = Color(0xFFFFD60A).copy(alpha = 0.25f), style = Stroke(width = 8f, cap = StrokeCap.Round))

        // Layer 2: Electric Cyan / Blue Arc
        drawPath(boltPath, color = Color(0xFF00E5FF), style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Miter))
        drawPath(branchPath, color = Color(0xFF00E5FF), style = Stroke(width = 3f, cap = StrokeCap.Round))

        // Layer 3: Ultra Bright Pure White Core
        drawPath(boltPath, color = Color.White, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // 3. Electric Contact Flash Sparkle at impact
        val flashRadius = 16f + (sparkPulse * 12f)
        drawCircle(Color.White, radius = flashRadius * 0.7f, center = targetPoint)
        drawCircle(Color(0xFFFFD60A), radius = flashRadius, center = targetPoint, style = Stroke(3f))

        // 4. Particle Sparks (24 Sparks shooting outwards)
        val rand = Random(42)
        for (i in 0..24) {
            val angle = rand.nextFloat() * 2f * Math.PI
            val speed = 35f + rand.nextFloat() * 75f * lightningIntensity
            val px = targetPoint.x + (cos(angle) * speed).toFloat()
            val py = targetPoint.y + (sin(angle) * speed * 0.6f).toFloat() // flattened in isometric view
            val pColor = if (i % 2 == 0) Color(0xFFFFD60A) else Color(0xFF00E5FF)
            drawCircle(color = pColor, radius = (rand.nextFloat() * 3f + 1.5f), center = Offset(px, py))
        }
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
