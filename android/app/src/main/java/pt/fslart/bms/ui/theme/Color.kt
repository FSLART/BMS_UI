package pt.fslart.bms.ui.theme

import androidx.compose.ui.graphics.Color

// Brand Colors
val LartYellow = Color(0xFFFFC300)
val LartYellowMuted = Color(0xFFCC9C00)
val LartBlueAccent = Color(0xFF9BB4D9)

// Dark Theme Colors
val DarkBackground = Color(0xFF0B0F14)
val DarkSurface = Color(0xFF151B22)
val DarkSurfaceElevated = Color(0xFF1C242F)
val DarkSurfaceCard = Color(0xFF161E28)
val DarkBorder = Color(0xFF263240)
val DarkTextPrimary = Color(0xFFF0F3F6)
val DarkTextSecondary = Color(0xFF8B949E)
val DarkTextMuted = Color(0xFF6E7681)

// Status Tokens
val StatusOk = Color(0xFF2EA043)
val StatusOkGlow = Color(0xFF3FB950)
val StatusWarn = Color(0xFFD29922)
val StatusWarnBg = Color(0xFF382A0A)
val StatusFault = Color(0xFFF85149)
val StatusFaultBg = Color(0xFF3E1416)
val StatusUnknown = Color(0xFF6E7681)

// Cell Ramp Gradient Colors
val CellGreenMin = Color(0xFF14382A)
val CellGreenMid = Color(0xFF18543C)
val CellGreenHigh = Color(0xFF207654)
val CellGreenPeak = Color(0xFF2E9E6E)

fun getCellColor(voltage: Float, status: pt.fslart.bms.model.Severity, vLow: Float = 2.80f, vHigh: Float = 4.15f): Color {
    when (status) {
        pt.fslart.bms.model.Severity.FAULT -> return StatusFault
        pt.fslart.bms.model.Severity.WARN -> return StatusWarn
        pt.fslart.bms.model.Severity.UNKNOWN -> return StatusUnknown
        pt.fslart.bms.model.Severity.OK -> {
            val fraction = ((voltage - vLow) / (vHigh - vLow).coerceAtLeast(0.01f)).coerceIn(0.0f, 1.0f)
            return when {
                fraction < 0.33f -> {
                    val f = fraction / 0.33f
                    lerpColor(CellGreenMin, CellGreenMid, f)
                }
                fraction < 0.66f -> {
                    val f = (fraction - 0.33f) / 0.33f
                    lerpColor(CellGreenMid, CellGreenHigh, f)
                }
                else -> {
                    val f = (fraction - 0.66f) / 0.34f
                    lerpColor(CellGreenHigh, CellGreenPeak, f)
                }
            }
        }
    }
}

private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
    return Color(
        red = c1.red + (c2.red - c1.red) * t,
        green = c1.green + (c2.green - c1.green) * t,
        blue = c1.blue + (c2.blue - c1.blue) * t,
        alpha = c1.alpha + (c2.alpha - c1.alpha) * t
    )
}
