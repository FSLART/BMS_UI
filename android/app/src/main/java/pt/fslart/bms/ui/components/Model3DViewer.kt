package pt.fslart.bms.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.webkit.WebViewAssetLoader
import pt.fslart.bms.ui.theme.*

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Model3DViewer(
    modelFile: String,
    modifier: Modifier = Modifier,
    bmsState: pt.fslart.bms.model.BmsState? = null,
    selectedSegment: Int = 1,
    height: Dp = 320.dp,
    showControls: Boolean = true,
    title: String? = null
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isAutoRotate by remember { mutableStateOf(false) }
    var isMaximized by remember { mutableStateOf(false) }

    LaunchedEffect(modelFile) {
        webViewRef?.evaluateJavascript("if (typeof loadModel === 'function') loadModel('$modelFile', $isAutoRotate);", null)
    }

    LaunchedEffect(bmsState, selectedSegment) {
        val json = if (bmsState != null) buildTelemetryJson(bmsState, selectedSegment) else buildDefaultTelemetryJson(selectedSegment)
        webViewRef?.evaluateJavascript("if (typeof updateTelemetry === 'function') updateTelemetry($json);", null)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF070A0F))
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Optional Card Title Bar
            if (title != null || showControls) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary,
                            letterSpacing = 0.5.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (showControls) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Auto-rotate toggle
                            IconButton(
                                onClick = {
                                    isAutoRotate = !isAutoRotate
                                    webViewRef?.evaluateJavascript("toggleAutoRotate($isAutoRotate);", null)
                                },
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isAutoRotate) LartYellow.copy(alpha = 0.2f) else DarkSurfaceElevated)
                                    .border(1.dp, if (isAutoRotate) LartYellow else DarkBorder, RoundedCornerShape(6.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RotateRight,
                                    contentDescription = "Auto-Rodar",
                                    tint = if (isAutoRotate) LartYellow else DarkTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Center / Reset Camera
                            IconButton(
                                onClick = {
                                    webViewRef?.evaluateJavascript("resetCamera();", null)
                                },
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DarkSurfaceElevated)
                                    .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CenterFocusStrong,
                                    contentDescription = "Centrar Câmara",
                                    tint = DarkTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Maximize to Fullscreen
                            IconButton(
                                onClick = { isMaximized = true },
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DarkSurfaceElevated)
                                    .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Ecrã Inteiro",
                                    tint = LartYellow,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // WebView 3D Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
            ) {
                AndroidView(
                    factory = { ctx ->
                        createConfiguredWebView(ctx, modelFile, isAutoRotate, bmsState, selectedSegment) { webViewRef = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // FULLSCREEN 3D DIALOG
    if (isMaximized) {
        Dialog(
            onDismissRequest = { isMaximized = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.let { window ->
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    window.setBackgroundDrawableResource(android.R.color.transparent)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF070A0F)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = title ?: "VISUALIZADOR 3D LART",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Arrasta para rodar · Pinça para zoom",
                                fontSize = 10.sp,
                                color = DarkTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = { isMaximized = false },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LartYellow),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Minimizar", modifier = Modifier.size(16.dp), tint = LartYellow)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Fechar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LartYellow)
                        }
                    }

                    // Fullscreen 3D View
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                createConfiguredWebView(ctx, modelFile, isAutoRotate, bmsState, selectedSegment)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

private fun createConfiguredWebView(
    context: android.content.Context,
    modelFile: String,
    isAutoRotate: Boolean,
    bmsState: pt.fslart.bms.model.BmsState?,
    selectedSegment: Int = 1,
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
        setBackgroundColor(AndroidColor.parseColor("#070A0F"))

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
                        android.util.Log.e("Model3DViewer", "Failed to open asset: $assetPath", e)
                    }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val teleJson = if (bmsState != null) buildTelemetryJson(bmsState, selectedSegment) else buildDefaultTelemetryJson(selectedSegment)
                view?.evaluateJavascript(
                    "if (typeof loadModel === 'function') { loadModel('$modelFile', $isAutoRotate); } if (typeof updateTelemetry === 'function') { updateTelemetry($teleJson); }",
                    null
                )
            }
        }

        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("Model3DViewer_JS", "${consoleMessage?.message()} [line ${consoleMessage?.lineNumber()}]")
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

        loadUrl("https://appassets.androidplatform.net/assets/model_viewer.html")
        onWebViewCreated?.invoke(this)
    }
}

private fun buildDefaultSegmentCellsJson(): String {
    val voltages = listOf(
        3.877f, 3.880f, 3.857f, 3.889f, 3.870f, 3.888f,
        3.885f, 3.875f, 3.874f, 3.889f, 3.862f, 3.900f,
        3.868f, 3.883f, 3.875f, 3.877f, 3.880f, 3.869f,
        3.852f, 3.855f, 3.864f, 3.871f, 3.885f, 3.857f
    )
    return voltages.mapIndexed { idx, v ->
        val cellIdx = idx + 1
        val isBalancing = cellIdx == 14 || cellIdx == 24
        """{"index": $cellIdx, "voltage": $v, "balancing": $isBalancing, "openWire": false, "status": "${if (isBalancing) "warn" else "ok"}"}"""
    }.joinToString(",")
}

private fun buildDefaultSegmentThermsJson(): String {
    val temps = listOf(
        47.2f, 48.6f, 47.7f,
        48.6f, 49.1f, 46.9f,
        50.5f, 56.8f, 47.1f,
        50.6f, 46.2f, 49.7f
    )
    return temps.mapIndexed { idx, t ->
        val ntcIdx = idx + 1
        val isHot = t >= 50f
        """{"index": $ntcIdx, "temperature": $t, "status": "${if (isHot) "warn" else "ok"}"}"""
    }.joinToString(",")
}

private fun buildTelemetryJson(state: pt.fslart.bms.model.BmsState, selectedSegment: Int = 1): String {
    val hottest = state.segments.maxByOrNull { it.tempMax }
    val segJson = state.segments.joinToString(",") { s ->
        """{"id": ${s.id}, "tempMax": ${s.tempMax}, "status": "${s.status.name.lowercase()}"}"""
    }
    val current = state.pack.current
    val fanPwm = state.ams.fanPwm ?: (if (current > 0.5f || state.isCharging) 59f else 0f)

    val segCells = state.cells.filter { it.segment == selectedSegment }
    val segCellsJson = if (segCells.isNotEmpty()) {
        segCells.joinToString(",") { c ->
            """{"index": ${c.index}, "voltage": ${c.voltage}, "balancing": ${c.balancing}, "openWire": ${c.openWire}, "status": "${c.status.name.lowercase()}"}"""
        }
    } else {
        buildDefaultSegmentCellsJson()
    }

    val segTherms = state.thermistors.filter { it.segment == selectedSegment }
    val segThermsJson = if (segTherms.isNotEmpty()) {
        segTherms.mapIndexed { idx, t ->
            val temp = t.temperature ?: 45.0f
            """{"index": ${idx + 1}, "temperature": $temp, "status": "${t.status.name.lowercase()}"}"""
        }.joinToString(",")
    } else {
        buildDefaultSegmentThermsJson()
    }

    return """
    {
      "pack": { "current": $current, "voltage": ${state.pack.voltage} },
      "safety": {
        "amsOk": ${state.safety.amsOk},
        "airPositive": ${state.safety.airPositive},
        "airNegative": ${state.safety.airNegative},
        "prechargeDone": ${state.safety.prechargeDone}
      },
      "ams": { "fanPwm": $fanPwm },
      "charger": { "charging": ${state.isCharging} },
      "hottestSegId": ${hottest?.id ?: 2},
      "selectedSegment": $selectedSegment,
      "segments": [$segJson],
      "segmentCells": [$segCellsJson],
      "segmentThermistors": [$segThermsJson]
    }
    """.trimIndent()
}

private fun buildDefaultTelemetryJson(selectedSegment: Int = 1): String {
    return """
    {
      "pack": { "current": 30.9, "voltage": 581.5 },
      "safety": {
        "amsOk": true,
        "airPositive": true,
        "airNegative": true,
        "prechargeDone": true
      },
      "ams": { "fanPwm": 59 },
      "charger": { "charging": false },
      "hottestSegId": 2,
      "selectedSegment": $selectedSegment,
      "segments": [
        {"id": 1, "tempMax": 41.9, "status": "ok"},
        {"id": 2, "tempMax": 44.8, "status": "warn"},
        {"id": 3, "tempMax": 41.8, "status": "ok"},
        {"id": 4, "tempMax": 40.9, "status": "ok"},
        {"id": 5, "tempMax": 41.1, "status": "ok"},
        {"id": 6, "tempMax": 40.4, "status": "ok"}
      ],
      "segmentCells": [${buildDefaultSegmentCellsJson()}],
      "segmentThermistors": [${buildDefaultSegmentThermsJson()}]
    }
    """.trimIndent()
}

