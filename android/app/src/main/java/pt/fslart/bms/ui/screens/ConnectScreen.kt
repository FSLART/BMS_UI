package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.ble.BleConnectionState
import pt.fslart.bms.model.CarProfile
import pt.fslart.bms.network.RelayConnectionState
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.util.AppUpdateManager
import pt.fslart.bms.util.UpdateState
import pt.fslart.bms.viewmodel.BmsViewModel
import pt.fslart.bms.viewmodel.DataSource

@Composable
fun ConnectScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val selectedCar by viewModel.selectedCar.collectAsState()
    val bleAddress by viewModel.bleAddress.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isDemoMode by viewModel.isDemoMode.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    val activeDataSource by viewModel.activeDataSource.collectAsState()
    val serverViewerState by viewModel.serverViewerState.collectAsState()
    val serverRelayState by viewModel.serverRelayState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val isRelayEnabled by viewModel.isRelayEnabled.collectAsState()
    val packetsSent by viewModel.packetsSent.collectAsState()
    val packetsReceived by viewModel.packetsReceived.collectAsState()
    val serverError by viewModel.serverError.collectAsState()

    var showAddressDialog by remember { mutableStateOf(false) }
    var tempAddress by remember { mutableStateOf(bleAddress) }
    var showServerDialog by remember { mutableStateOf(false) }
    var tempServerUrl by remember { mutableStateOf(serverUrl) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // App Header
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = pt.fslart.bms.R.drawable.lart_logo_symbol),
                        contentDescription = "LART",
                        modifier = Modifier.height(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "FORMULA STUDENT LART",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LartYellow,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "LART BMS",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DarkTextPrimary
                )
                Text(
                    text = "Monitorização de alta tensão por Bluetooth Low Energy",
                    fontSize = 13.sp,
                    color = DarkTextSecondary
                )
            }
        }

        // STEP 1: CAR SELECTION
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "1. ESCOLHE O CARRO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.8.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CarProfile.ALL_CARS.forEach { car ->
                        val isSelected = car.id == selectedCar.id
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) DarkSurfaceElevated else DarkSurface)
                                .border(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) LartYellow else DarkBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.selectCar(car) }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = car.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) LartYellow else DarkTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${car.seriesCount}s${car.parallelStrings}p",
                                    fontSize = 11.sp,
                                    color = DarkTextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${car.nominalPackV.toInt()} V nominal",
                                    fontSize = 10.sp,
                                    color = DarkTextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // STEP 2: BLUETOOTH LE CONNECTION (PRIORITIZED)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = "Bluetooth",
                            tint = LartYellow,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIGAÇÃO BLUETOOTH LE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary
                        )
                    }

                    // Pre-configured address pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                tempAddress = bleAddress
                                showAddressDialog = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = bleAddress,
                            color = LartYellow,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Módulo Microchip RN4871 no UART2 do STM32 master. Protocolo de telemetria serial a 115200 baud.",
                    fontSize = 12.sp,
                    color = DarkTextSecondary,
                    lineHeight = 16.sp
                )

                // PRIMARY ACTION BUTTON: CONNECT TO PRE-CONFIGURED DEVICE
                Button(
                    onClick = { viewModel.connectBle() },
                    enabled = connectionState != BleConnectionState.CONNECTING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LartYellow,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (connectionState == BleConnectionState.CONNECTING) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "A LIGAR AO BMS…",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIGAR AO ${selectedCar.name} ($bleAddress)",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }

                // SCAN BUTTON FOR DISCOVERY
                OutlinedButton(
                    onClick = {
                        if (isScanning) viewModel.stopBleScan() else viewModel.startBleScan()
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkTextPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            color = LartYellow,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "A PROCURAR DISPOSITIVOS…", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "PROCURAR DISPOSITIVOS BLE POR PERTO", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Connection status message banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotColor = when (connectionState) {
                        BleConnectionState.CONNECTED -> StatusOk
                        BleConnectionState.CONNECTING -> LartYellow
                        BleConnectionState.ERROR -> StatusFault
                        BleConnectionState.DISCONNECTED -> DarkTextMuted
                    }
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusMessage,
                        fontSize = 12.sp,
                        color = DarkTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                // Relay Switch (Broadcaster: BLE -> Server)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = if (isRelayEnabled) LartYellow else DarkTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Reencaminhar para a Nuvem",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkTextPrimary
                            )
                        }
                        Text(
                            text = "Envia a telemetria para bms.stocklart.pt para outros colegas verem.",
                            fontSize = 10.sp,
                            color = DarkTextMuted
                        )
                        if (isRelayEnabled) {
                            val relayStatusText = when (serverRelayState) {
                                RelayConnectionState.CONNECTED -> "🟢 A transmitir ($packetsSent pacotes enviados)"
                                RelayConnectionState.CONNECTING -> "🟡 A ligar ao servidor..."
                                RelayConnectionState.ERROR -> "🔴 Erro (a tentar religar...)"
                                RelayConnectionState.DISCONNECTED -> "⚪ Pronto para transmitir"
                            }
                            Text(
                                text = relayStatusText,
                                fontSize = 10.sp,
                                color = if (serverRelayState == RelayConnectionState.CONNECTED) StatusOk else LartYellow,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isRelayEnabled,
                        onCheckedChange = { viewModel.setRelayEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = LartYellow,
                            uncheckedThumbColor = DarkTextMuted,
                            uncheckedTrackColor = DarkSurfaceElevated
                        )
                    )
                }
            }
        }

        // STEP 3: CLOUD / WI-FI CONNECTION (FOR TEAMMATES VIEWING VIA SERVER)
        item {
            val isViewerConnecting = serverViewerState == RelayConnectionState.CONNECTING
            val isViewerConnected = serverViewerState == RelayConnectionState.CONNECTED && activeDataSource == DataSource.SERVER

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(
                        1.dp,
                        if (isViewerConnected) StatusOk else DarkBorder,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Wi-Fi",
                            tint = LartYellow,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIGAÇÃO WI-FI / 4G (PELA NUVEM)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary
                        )
                    }

                    // Pre-configured address pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                tempServerUrl = serverUrl
                                showServerDialog = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = serverUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/ws/telemetry"),
                            color = LartYellow,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Vê a telemetria em direto quando outro membro da equipa está ligado ao carro por Bluetooth.",
                    fontSize = 12.sp,
                    color = DarkTextSecondary,
                    lineHeight = 16.sp
                )

                // PRIMARY ACTION BUTTON: CONNECT TO SERVER
                Button(
                    onClick = {
                        if (isViewerConnected) {
                            viewModel.disconnectServer()
                        } else {
                            viewModel.connectServer()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isViewerConnected) StatusFault else LartYellow,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isViewerConnecting) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "A LIGAR AO SERVIDOR…",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    } else if (isViewerConnected) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DESLIGAR DA NUVEM",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIGAR AO SERVIDOR (bms.stocklart.pt)",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }

                // Server status message banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotColor = when {
                        isViewerConnected -> StatusOk
                        isViewerConnecting -> LartYellow
                        serverViewerState == RelayConnectionState.ERROR -> StatusFault
                        else -> DarkTextMuted
                    }
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                    Spacer(modifier = Modifier.width(8.dp))
                    val statusLabel = when {
                        isViewerConnected -> "Ligado à nuvem · A receber telemetria ($packetsReceived pacotes)"
                        isViewerConnecting -> "A contactar bms.stocklart.pt..."
                        serverViewerState == RelayConnectionState.ERROR -> serverError ?: "Erro de ligação"
                        else -> "Desligado da nuvem"
                    }
                    Text(
                        text = statusLabel,
                        fontSize = 12.sp,
                        color = DarkTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // DISCOVERED DEVICES LIST
        if (discoveredDevices.isNotEmpty()) {
            item {
                Text(
                    text = "DISPOSITIVOS BLE ENCONTRADOS (${discoveredDevices.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted
                )
            }

            items(discoveredDevices) { dev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .border(
                            1.dp,
                            if (dev.isBmsDevice) LartYellow.copy(alpha = 0.5f) else DarkBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            viewModel.setBleAddress(dev.address)
                            viewModel.connectBle(dev.address)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = dev.name,
                                fontWeight = FontWeight.Bold,
                                color = if (dev.isBmsDevice) LartYellow else DarkTextPrimary,
                                fontSize = 13.sp
                            )
                            if (dev.isBmsDevice) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(LartYellow.copy(alpha = 0.2f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("BMS", fontSize = 8.sp, color = LartYellow, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(
                            text = dev.address,
                            color = DarkTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${dev.rssi} dBm",
                            fontSize = 11.sp,
                            color = DarkTextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = DarkTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // DEMO MODE TOGGLE CARD (SIMULATOR)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modo Demo (Simulador)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Simula a bateria com física realista de condução, carga e espalhamento de células sem hardware ligado.",
                        fontSize = 12.sp,
                        color = DarkTextSecondary,
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isDemoMode,
                    onCheckedChange = { viewModel.setDemoMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = LartYellow,
                        uncheckedThumbColor = DarkTextMuted,
                        uncheckedTrackColor = DarkSurfaceElevated
                    )
                )
            }
        }

        // APP VERSION & GITHUB OTA UPDATE CARD
        item {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val updateState by AppUpdateManager.updateState.collectAsState()
            val currentVersion = remember(context) { AppUpdateManager.getCurrentVersionName(context) }
            val currentBuild = remember(context) { AppUpdateManager.getCurrentVersionCode(context) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ATUALIZAÇÕES AUTOMÁTICAS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextMuted,
                            letterSpacing = 0.6.sp
                        )
                        Text(
                            text = "v$currentVersion (Build $currentBuild)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            AppUpdateManager.checkForUpdate(context, coroutineScope, silent = false)
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LartYellow),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurfaceElevated),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        if (updateState is UpdateState.Checking) {
                            CircularProgressIndicator(
                                color = LartYellow,
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = LartYellow,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = "Verificar no GitHub",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LartYellow
                        )
                    }
                }

                if (updateState is UpdateState.UpToDate) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusOkGlow,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "A aplicação está sincronizada com a versão mais recente.",
                            fontSize = 11.sp,
                            color = StatusOkGlow
                        )
                    }
                }
            }
        }
    }

    // EDIT BLE ADDRESS DIALOG
    if (showAddressDialog) {
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text("Endereço Bluetooth LE", color = DarkTextPrimary) },
            text = {
                Column {
                    Text(
                        text = "Insere o endereço MAC do módulo RN4871 do acumulador:",
                        color = DarkTextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tempAddress,
                        onValueChange = { tempAddress = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LartYellow,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setBleAddress(tempAddress.trim().uppercase())
                        showAddressDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LartYellow, contentColor = Color.Black)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) {
                    Text("Cancelar", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }

    // EDIT SERVER URL DIALOG
    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("Endereço do Servidor", color = DarkTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Insere o endereço WebSocket do servidor na nuvem ou rede local:",
                        color = DarkTextSecondary,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = tempServerUrl,
                        onValueChange = { tempServerUrl = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LartYellow,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { tempServerUrl = "wss://bms.stocklart.pt/ws/telemetry" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text("stocklart.pt", fontSize = 11.sp, color = LartYellow)
                        }
                        OutlinedButton(
                            onClick = { tempServerUrl = "ws://169.58.112.236:8000/ws/telemetry" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text("IP direto", fontSize = 11.sp, color = DarkTextSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val normalized = viewModel.relayManager.normalizeWsUrl(tempServerUrl)
                        viewModel.setServerUrl(normalized)
                        showServerDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LartYellow, contentColor = Color.Black)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text("Cancelar", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}
