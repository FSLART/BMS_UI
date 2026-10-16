package pt.fslart.bms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import pt.fslart.bms.ui.components.ChargingAssemblyModal
import pt.fslart.bms.ui.components.Fullscreen3DViewer
import pt.fslart.bms.ui.components.FullscreenCellChartOverlay
import pt.fslart.bms.ui.components.FullscreenThermChartOverlay
import pt.fslart.bms.ui.components.UpdateNotificationBanner
import pt.fslart.bms.ui.screens.*
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.util.AppUpdateManager
import pt.fslart.bms.viewmodel.BmsViewModel
import pt.fslart.bms.viewmodel.ScreenTab

class MainActivity : ComponentActivity() {

    private val viewModel: BmsViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestBlePermissions()

        setContent {
            BmsTheme {
                MainAppScaffold(viewModel = viewModel)
            }
        }
    }

    private fun checkAndRequestBlePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun MainAppScaffold(viewModel: BmsViewModel) {
    val currentTab by viewModel.currentTab.collectAsState()
    val state by viewModel.bmsState.collectAsState()
    val fullscreen3D by viewModel.fullscreen3D.collectAsState()
    val isCellChartFullscreen by viewModel.isCellChartFullscreen.collectAsState()
    val isThermChartFullscreen by viewModel.isThermChartFullscreen.collectAsState()
    val isThermalView3D by viewModel.isThermalView3D.collectAsState()
    val selectedSegmentId by viewModel.selectedSegmentId.collectAsState()
    val showChargingAnimation by viewModel.showChargingAnimation.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        AppUpdateManager.checkForUpdate(context, coroutineScope, silent = true)
    }

    val filteredCells = remember(state.cells, selectedSegmentId) {
        if (selectedSegmentId == null) {
            state.cells
        } else {
            state.cells.filter { it.segment == selectedSegmentId }
        }
    }

    val filteredThermistors = remember(state.thermistors, selectedSegmentId) {
        if (selectedSegmentId == null) {
            state.thermistors
        } else {
            state.thermistors.filter { it.segment == selectedSegmentId }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // Adaptive layout based on window width: phones get BottomBar, tablets get NavigationRail
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTablet = maxWidth >= 650.dp

            if (isTablet) {
                // TABLET / WIDE LAYOUT (NAVIGATION RAIL ON THE LEFT)
                Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = DarkSurface,
                    contentColor = DarkTextSecondary,
                    modifier = Modifier.width(80.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.CONNECT,
                        onClick = { viewModel.setTab(ScreenTab.CONNECT) },
                        icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Ligar") },
                        label = { Text("Ligar", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.DASHBOARD,
                        onClick = { viewModel.setTab(ScreenTab.DASHBOARD) },
                        icon = { Icon(Icons.Default.Speed, contentDescription = "Painel") },
                        label = { Text("Painel", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.CELLS,
                        onClick = { viewModel.setTab(ScreenTab.CELLS) },
                        icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = "Células") },
                        label = { Text("Células", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.TEMPS,
                        onClick = { viewModel.setTab(ScreenTab.TEMPS) },
                        icon = { Icon(Icons.Default.Thermostat, contentDescription = "NTCs") },
                        label = { Text("NTCs", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.CHARGER,
                        onClick = { viewModel.setTab(ScreenTab.CHARGER) },
                        icon = { Icon(Icons.Default.ElectricBolt, contentDescription = "Carga") },
                        label = { Text("Carga", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.MODEL_3D,
                        onClick = { viewModel.setTab(ScreenTab.MODEL_3D) },
                        icon = { Icon(Icons.Default.Layers, contentDescription = "3D CAD") },
                        label = { Text("3D", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.CHECKLIST,
                        onClick = { viewModel.setTab(ScreenTab.CHECKLIST) },
                        icon = { Icon(Icons.Default.FactCheck, contentDescription = "Inspeção") },
                        label = { Text("Check", fontSize = 10.sp) },
                        colors = railColors()
                    )

                    NavigationRailItem(
                        selected = currentTab == ScreenTab.CONSOLE,
                        onClick = { viewModel.setTab(ScreenTab.CONSOLE) },
                        icon = { Icon(Icons.Default.Terminal, contentDescription = "Consola") },
                        label = { Text("Consola", fontSize = 10.sp) },
                        colors = railColors()
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ScreenContent(currentTab, viewModel)
                }
            }
        } else {
            // PHONE / PORTRAIT LAYOUT (BOTTOM NAVIGATION BAR)
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = DarkSurface,
                        contentColor = DarkTextSecondary,
                        tonalElevation = 8.dp
                    ) {
                        NavigationBarItem(
                            selected = currentTab == ScreenTab.CONNECT,
                            onClick = { viewModel.setTab(ScreenTab.CONNECT) },
                            icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Ligar") },
                            label = { Text("Ligar", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.DASHBOARD,
                            onClick = { viewModel.setTab(ScreenTab.DASHBOARD) },
                            icon = { Icon(Icons.Default.Speed, contentDescription = "Painel") },
                            label = { Text("Painel", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.CELLS,
                            onClick = { viewModel.setTab(ScreenTab.CELLS) },
                            icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = "Células") },
                            label = { Text("Células", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.TEMPS,
                            onClick = { viewModel.setTab(ScreenTab.TEMPS) },
                            icon = { Icon(Icons.Default.Thermostat, contentDescription = "NTCs") },
                            label = { Text("NTCs", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.MODEL_3D,
                            onClick = { viewModel.setTab(ScreenTab.MODEL_3D) },
                            icon = { Icon(Icons.Default.Layers, contentDescription = "3D CAD") },
                            label = { Text("3D", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.CHARGER,
                            onClick = { viewModel.setTab(ScreenTab.CHARGER) },
                            icon = { Icon(Icons.Default.ElectricBolt, contentDescription = "Carga") },
                            label = { Text("Carga", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.CHECKLIST,
                            onClick = { viewModel.setTab(ScreenTab.CHECKLIST) },
                            icon = { Icon(Icons.Default.FactCheck, contentDescription = "Inspeção") },
                            label = { Text("Check", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )

                        NavigationBarItem(
                            selected = currentTab == ScreenTab.CONSOLE,
                            onClick = { viewModel.setTab(ScreenTab.CONSOLE) },
                            icon = { Icon(Icons.Default.Terminal, contentDescription = "Consola") },
                            label = { Text("Logs", fontSize = 8.sp, fontWeight = FontWeight.SemiBold) },
                            colors = navColors()
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        UpdateNotificationBanner(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ScreenContent(currentTab, viewModel)
                        }
                    }
                }
            }
        }
    }

        // FULLSCREEN 3D OVERLAY (DIRECT ON TOP OF MAIN WINDOW)
        fullscreen3D?.let { req ->
            BackHandler {
                viewModel.closeFullscreen3D()
            }
            Fullscreen3DViewer(
                request = req,
                bmsState = state,
                isThermalMode = isThermalView3D,
                onThermalModeToggle = { viewModel.toggleThermalView3D() },
                onClose = { viewModel.closeFullscreen3D() }
            )
        }

        // FULLSCREEN CELL CHART OVERLAY
        if (isCellChartFullscreen) {
            BackHandler {
                viewModel.closeCellChartFullscreen()
            }
            FullscreenCellChartOverlay(
                cells = filteredCells,
                vMinLimit = state.car.limits.vMin - 0.2f,
                vMaxLimit = state.car.limits.vMax + 0.1f,
                vWarnLow = state.car.limits.vWarnLow,
                vWarnHigh = state.car.limits.vWarnHigh,
                onClose = { viewModel.closeCellChartFullscreen() }
            )
        }

        // FULLSCREEN THERMISTOR CHART OVERLAY
        if (isThermChartFullscreen) {
            BackHandler {
                viewModel.closeThermChartFullscreen()
            }
            FullscreenThermChartOverlay(
                thermistors = filteredThermistors,
                tMinLimit = 0.0f,
                tMaxLimit = 70.0f,
                tWarn = state.car.limits.tempWarn,
                tFault = state.car.limits.tempFault,
                brokenThermistors = state.car.brokenThermistors,
                onClose = { viewModel.closeThermChartFullscreen() }
            )
        }

        // FULLSCREEN CHARGING ASSEMBLY & LIGHTNING ANIMATION OVERLAY
        if (showChargingAnimation) {
            BackHandler {
                viewModel.dismissChargingAnimation()
            }
            ChargingAssemblyModal(
                bmsState = state,
                onDismiss = { viewModel.dismissChargingAnimation() }
            )
        }
    }
}

@Composable
private fun ScreenContent(currentTab: ScreenTab, viewModel: BmsViewModel) {
    when (currentTab) {
        ScreenTab.CONNECT -> ConnectScreen(viewModel = viewModel)
        ScreenTab.DASHBOARD -> DashboardScreen(viewModel = viewModel)
        ScreenTab.CELLS -> CellsScreen(viewModel = viewModel)
        ScreenTab.TEMPS -> ThermistorsScreen(viewModel = viewModel)
        ScreenTab.CHARGER -> ChargerScreen(viewModel = viewModel)
        ScreenTab.MODEL_3D -> Model3DScreen(viewModel = viewModel)
        ScreenTab.CONSOLE -> ConsoleScreen(viewModel = viewModel)
        ScreenTab.CHECKLIST -> ScrutineeringScreen(viewModel = viewModel)
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.Black,
    selectedTextColor = LartYellow,
    indicatorColor = LartYellow,
    unselectedIconColor = DarkTextMuted,
    unselectedTextColor = DarkTextMuted
)

@Composable
private fun railColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = Color.Black,
    selectedTextColor = LartYellow,
    indicatorColor = LartYellow,
    unselectedIconColor = DarkTextMuted,
    unselectedTextColor = DarkTextMuted
)
