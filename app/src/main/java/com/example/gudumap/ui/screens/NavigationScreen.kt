package com.example.gudumap.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning

import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gudumap.navigation.GpsState
import com.example.gudumap.navigation.NavigationState
import com.example.gudumap.ui.components.MapView
import com.example.gudumap.viewmodel.NavigationViewModel
import kotlinx.coroutines.launch
import java.util.Locale

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun confidenceLevel(radiusMeters: Double): String = when {
    radiusMeters < 5.0 -> "High"
    radiusMeters <= 15.0 -> "Medium"
    else -> "Low"
}

private fun confidenceColor(level: String): Color = when (level) {
    "High" -> Color(0xFF10B981)
    "Medium" -> Color(0xFFF59E0B)
    else -> Color(0xFFEF4444)
}

private fun headingCardinal(headingDeg: Float): String {
    val normalized = (headingDeg % 360f + 360f) % 360f
    return when {
        normalized < 22.5f || normalized >= 337.5f -> "N"
        normalized < 67.5f -> "NE"
        normalized < 112.5f -> "E"
        normalized < 157.5f -> "SE"
        normalized < 202.5f -> "S"
        normalized < 247.5f -> "SW"
        normalized < 292.5f -> "W"
        else -> "NW"
    }
}

@Composable
fun NavigationScreen(
    navViewModel: NavigationViewModel = viewModel(factory = NavigationViewModel.Factory)
) {
    val context = LocalContext.current
    val navState by navViewModel.state.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isMapExpanded by remember { mutableStateOf(false) }
    var isMapMinimized by remember { mutableStateOf(false) }
    val preferences = remember {
        context.getSharedPreferences("naviator_preferences", Context.MODE_PRIVATE)
    }
    var isDarkMode by remember {
        mutableStateOf(preferences.getBoolean("dark_mode", false))
    }
    var showOnboarding by remember {
        mutableStateOf(!preferences.getBoolean("onboarding_seen", false))
    }
    var sosState by remember { mutableStateOf(com.example.gudumap.sos.SosState.IDLE) }
    val sosViewModel: com.example.gudumap.sos.SosViewModel = viewModel()
    val sosStatus by sosViewModel.sosStatus.collectAsState()
    val activeSosPacket by sosViewModel.activeSosPacket.collectAsState()
    val acknowledgedReceivers by sosViewModel.acknowledgedReceivers.collectAsState()
    val selectedReceivedSos by sosViewModel.selectedReceivedSos.collectAsState()
    val receivedSosList by sosViewModel.receivedSosList.collectAsState()

    var showSosConfirmationDialog by remember { mutableStateOf(false) }
    var showSosCountdownDialog by remember { mutableStateOf(false) }
    var showSosBluetoothDisabledDialog by remember { mutableStateOf(false) }
    var showSosCancelConfirmationDialog by remember { mutableStateOf(false) }
    var showActiveSosDialog by remember { mutableStateOf(false) }

    var isTechnicalDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var isCoverageDetailsExpanded by rememberSaveable { mutableStateOf(false) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            navViewModel.retryLocationUpdatesIfNeeded()
        }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        sosViewModel.retryScanning()
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = {
                showOnboarding = false
                preferences.edit().putBoolean("onboarding_seen", true).apply()
            },
            title = { Text("Welcome to Naviator") },
            text = {
                Text(
                    "Naviator provides live positioning and offline map support for the Coimbatore area. " +
                        "Allow location access to center the map and enable navigation."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOnboarding = false
                        preferences.edit().putBoolean("onboarding_seen", true).apply()
                        if (!permissionGranted) {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        val perms = mutableListOf<String>()
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_SCAN)
                            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (perms.isNotEmpty()) {
                            blePermissionLauncher.launch(perms.toTypedArray())
                        }
                    }
                ) {
                    Text(if (permissionGranted) "GET STARTED" else "ALLOW LOCATION")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showOnboarding = false
                        preferences.edit().putBoolean("onboarding_seen", true).apply()
                    }
                ) {
                    Text("LATER")
                }
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val latestViewModel by rememberUpdatedState(navViewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    latestViewModel.resumeNavigation()
                    permissionGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    latestViewModel.retryLocationUpdatesIfNeeded()
                    sosViewModel.retryScanning()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    latestViewModel.pauseNavigation()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val screenBgColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val cardBgColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textColorSecondary = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
    val drawerBgColor = if (isDarkMode) Color(0xFF0F172A) else Color.White
    val drawerCardBg = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF8FAFC)
    val dividerColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val activeRoadBg = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFEFF6FF)
    val activeRoadBorder = if (isDarkMode) Color(0xFF334155) else Color(0xFFDBEAFE)
    val activeRoadText = if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF1E40AF)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(316.dp)
                    .pointerInput(Unit) {
                        var closeTriggered = false
                        detectHorizontalDragGestures(
                            onDragStart = { closeTriggered = false },
                            onHorizontalDrag = { _, dragAmount ->
                                if (!closeTriggered && dragAmount < -20f) {
                                    closeTriggered = true
                                    scope.launch { drawerState.close() }
                                }
                            }
                        )
                    },
                drawerContainerColor = drawerBgColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 28.dp)
                ) {
                    // 1. SIDEBAR HEADER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF2563EB)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = "NAVIATOR App Icon",
                                    modifier = Modifier
                                        .padding(7.dp)
                                        .size(18.dp),
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "NAVIATOR",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textColorPrimary,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isDarkMode) Color(0xFF064E3B) else Color(0xFFECFDF5)
                                    ) {
                                        Text(
                                            text = "v2.4",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF059669),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "On-Device AI Navigation Engine",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textColorSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = { scope.launch { drawerState.close() } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Drawer",
                                modifier = Modifier.size(20.dp),
                                tint = textColorSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. SECTION: NAVIGATION
                    Text(
                        text = "Navigation",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (navState.blackoutMode) (if (isDarkMode) Color(0xFF3F1212) else Color(0xFFFEF2F2)) else drawerCardBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (navState.blackoutMode) Color(0xFFEF4444) else cardBorderColor
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Blackout Mode",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColorPrimary
                                )
                                Text(
                                    text = if (navState.isDemoModeEnabled) {
                                        if (navViewModel.isKinematicDemoActive) "Demo Movement Active" else "Controls Panel Visible"
                                    } else {
                                        "Live GNSS Navigation Active"
                                    },
                                    fontSize = 10.sp,
                                    color = if (navState.isDemoModeEnabled) Color(0xFFEF4444) else textColorSecondary
                                )
                            }
                            Switch(
                                checked = navState.isDemoModeEnabled,
                                onCheckedChange = { enabled ->
                                    navViewModel.setDemoModeEnabled(enabled)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFEF4444)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. SECTION: TOOLS & EMERGENCY
                    Text(
                        text = "Tools & Emergency",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val isSosActiveInDrawer = activeSosPacket != null || sosStatus == com.example.gudumap.sos.SosStatus.BROADCASTING || sosStatus == com.example.gudumap.sos.SosStatus.ACKNOWLEDGED

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { drawerState.close() }
                                if (isSosActiveInDrawer) {
                                    showActiveSosDialog = true
                                } else {
                                    if (!sosViewModel.isBluetoothEnabled()) {
                                        showSosBluetoothDisabledDialog = true
                                    } else {
                                        val perms = mutableListOf<String>()
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                            if (!sosViewModel.hasScanPermission()) perms.add(Manifest.permission.BLUETOOTH_SCAN)
                                            if (!sosViewModel.hasAdvertisePermission()) perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                                            if (!sosViewModel.hasConnectPermission()) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                                        }
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            val hasNotif = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                            if (!hasNotif) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        if (perms.isNotEmpty()) {
                                            blePermissionLauncher.launch(perms.toTypedArray())
                                        }
                                        showSosConfirmationDialog = true
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSosActiveInDrawer) (if (isDarkMode) Color(0xFF3F1212) else Color(0xFFFEF2F2)) else drawerCardBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSosActiveInDrawer) Color(0xFFDC2626) else cardBorderColor
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSosActiveInDrawer) Color(0xFFDC2626) else (if (isDarkMode) Color(0xFF451A1A) else Color(0xFFFEF2F2))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Emergency SOS Icon",
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(18.dp),
                                        tint = if (isSosActiveInDrawer) Color.White else Color(0xFFDC2626)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isSosActiveInDrawer) "Emergency SOS — Active" else "Emergency SOS",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSosActiveInDrawer) Color(0xFFDC2626) else textColorPrimary
                                        )
                                        if (isSosActiveInDrawer) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(Color(0xFFDC2626), CircleShape)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isSosActiveInDrawer) {
                                            if (acknowledgedReceivers.isNotEmpty()) "Acknowledged by ${acknowledgedReceivers.size} nearby user(s)" else "Broadcasting alert... Tap to view"
                                        } else {
                                            "Broadcast BLE alert to nearby app users"
                                        },
                                        fontSize = 10.sp,
                                        color = if (isSosActiveInDrawer) Color(0xFFDC2626) else textColorSecondary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. SECTION: APPEARANCE
                    Text(
                        text = "Appearance",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = drawerCardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dark Appearance",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColorPrimary
                                )
                                Text(
                                    text = "Use dark appearance",
                                    fontSize = 10.sp,
                                    color = textColorSecondary
                                )
                            }
                            Switch(
                                checked = isDarkMode,
                                onCheckedChange = { enabled ->
                                    isDarkMode = enabled
                                    preferences.edit()
                                        .putBoolean("dark_mode", enabled)
                                        .apply()
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. SECTION: OFFLINE MAPS
                    Text(
                        text = "Offline Maps",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColorSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = drawerCardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .animateContentSize()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Map,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color(0xFF2563EB)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Coimbatore",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textColorPrimary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (navState.offlineMapStatus == "AVAILABLE") (if (isDarkMode) Color(0xFF064E3B) else Color(0xFFECFDF5)) else (if (isDarkMode) Color(0xFF450A0A) else Color(0xFFFEF2F2))
                                ) {
                                    Text(
                                        text = if (navState.offlineMapStatus == "AVAILABLE") "Available" else "Unavailable",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (navState.offlineMapStatus == "AVAILABLE") Color(0xFF059669) else Color(0xFFDC2626),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Zoom 11 – 16  •  34 MB",
                                fontSize = 10.5.sp,
                                color = textColorSecondary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // EXPANDABLE COVERAGE DETAILS ROW
                            val chevronRotation by animateFloatAsState(
                                targetValue = if (isCoverageDetailsExpanded) 180f else 0f,
                                label = "coverageChevronRotation"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = if (isCoverageDetailsExpanded) "Hide coverage details" else "Show coverage details"
                                    ) {
                                        isCoverageDetailsExpanded = !isCoverageDetailsExpanded
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isCoverageDetailsExpanded) "Hide details" else "Coverage details",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF2563EB)
                                )
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer { rotationZ = chevronRotation },
                                    tint = Color(0xFF2563EB)
                                )
                            }

                            if (isCoverageDetailsExpanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider(color = dividerColor.copy(alpha = 0.5f), thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "915 Tiles (Bundled MBTiles)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textColorPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Bounds: 10.915°N–11.125°N, 76.880°E–77.070°E",
                                    fontSize = 9.5.sp,
                                    color = textColorSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 5. APP CREDITS & VERSION FOOTER
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = drawerCardBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SIH 2026 • PS SIH26168",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2563EB),
                                letterSpacing = 0.3.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "ISRO IO-VNBD Model • On-Device AI",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColorPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Dead Reckoning GNSS Blackout System",
                                fontSize = 9.sp,
                                color = textColorSecondary
                            )
                        }
                    }

                }
            }
        }
    ) {
        if (isMapExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
            ) {
                MapView(
                    latitude = navState.latitude,
                    longitude = navState.longitude,
                    headingDeg = navState.headingDeg,
                    deviceHeadingDeg = navState.deviceHeadingDeg,
                    mapStatus = navState.mapStatus,
                    offlineMapStatus = navState.offlineMapStatus,
                    roadName = navState.currentRoadName,
                    blackoutMode = navState.blackoutMode,
                    naiveLatitude = navState.naiveLatitude,
                    naiveLongitude = navState.naiveLongitude,
                    uncertaintyRadiusMeters = navState.uncertaintyRadiusMeters,
                    locationAccuracyMeters = navState.locationAccuracyMeters,
                    locationProvider = navState.locationProvider,
                    demoTrailPoints = navState.demoTrailPoints,
                    demoTrailSegments = navState.demoTrailSegments,
                    gpsTrailPoints = navState.gpsTrailPoints,
                    predictionTrailPoints = navState.predictionTrailPoints,
                    predictionTrailSegments = navState.predictionTrailSegments,
                    visualMode = navState.visualMode,
                    lastTrustedGpsAccuracyMeters = navState.lastTrustedGpsAccuracyMeters,
                    lastTrustedGpsTimestampNs = navState.lastTrustedGpsTimestampNs,
                    lastKnownLocationAgeSeconds = navState.lastKnownLocationAgeSeconds,
                    historicalLkMarkers = navState.historicalLkMarkers,
                    lastPredictedLat = navState.lastPredictedLat,
                    lastPredictedLon = navState.lastPredictedLon,
                    predictionUncertaintyMeters = navState.predictionUncertaintyMeters,
                    hasGpsFix = navState.hasGpsFix,
                    gpsState = navState.gpsState,
                    isDemoModeEnabled = navState.isDemoModeEnabled,
                    isKinematicDemoActive = navViewModel.isKinematicDemoActive,
                    isExpanded = true,
                    isDarkMode = isDarkMode,
                    onToggleExpand = { isMapExpanded = false },
                    onMinimizeMap = { isMapExpanded = false },
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 44.dp, start = 12.dp)
                        .size(42.dp)
                        .background(if (isDarkMode) Color(0xEE1E293B) else Color(0xEEFFFFFF), CircleShape)
                        .border(1.dp, if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0), CircleShape)
                ) {
                    Text("≡", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = textColorPrimary)
                }
            }
        } else {
            Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(screenBgColor)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 36.dp)
                ) {
                    // TOP BRAND HEADER
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = cardBgColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Text("≡", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = textColorPrimary)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "NAVIATOR",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Black,
                                            color = textColorPrimary,
                                            letterSpacing = 0.8.sp
                                        )
                                        if (navState.isRecordingGpx) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFFFEF2F2)
                                            ) {
                                                Text(
                                                    text = "REC ${navState.recordedPointCount}",
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color(0xFFDC2626),
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "On-Device AI Navigation Engine",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = textColorSecondary,
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val mapSourceLabel = if (navState.offlineMapStatus == "AVAILABLE") "OFFLINE MAP" else "MAP UNAVAILABLE"
                                val mapSourceIsAvailable = navState.offlineMapStatus == "AVAILABLE"

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (mapSourceIsAvailable) Color(0xFFECFDF5) else Color(0xFFFEF2F2),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (mapSourceIsAvailable) Color(0xFFA7F3D0) else Color(0xFFFCA5A5)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(
                                                    if (mapSourceIsAvailable) Color(0xFF10B981) else Color(0xFFEF4444),
                                                    CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = mapSourceLabel,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            softWrap = false,
                                            color = if (mapSourceIsAvailable) Color(0xFF047857) else Color(0xFFB91C1C)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, cardBorderColor, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        color = if (navState.blackoutMode) Color(0xFF2A1B24) else cardBgColor
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val (locationLabel, labelColor) = when {
                                    navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive -> "Demo Movement Active" to Color(0xFF047857)
                                    navState.isDemoModeEnabled -> "Demo Controls" to Color(0xFF2563EB)
                                    navState.blackoutMode -> "Predicted Location" to Color(0xFFB91C1C)
                                    else -> when (navState.gpsState) {
                                        GpsState.FIXED -> "GPS Fixed" to Color(0xFF047857)
                                        GpsState.WEAK -> "GPS Weak" to Color(0xFFB45309)
                                        GpsState.STALE -> "GPS Weak" to Color(0xFFB45309)
                                        GpsState.ACQUIRING -> "Searching for GPS" to Color(0xFFB45309)
                                        GpsState.SEARCHING -> "Searching for GPS" to Color(0xFFB45309)
                                        GpsState.GPS_DISABLED -> "Location Off" to Color(0xFFB91C1C)
                                        GpsState.PERMISSION_REQUIRED -> "Permission Required" to Color(0xFFB91C1C)
                                        GpsState.LOST -> "GNSS Blackout Active" to Color(0xFFB91C1C)
                                    }
                                }
                                Text(
                                    text = locationLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = labelColor
                                )
                                Text(
                                    text = when {
                                        navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive -> {
                                            val cardinal = headingCardinal(navState.selectedDemoHeading)
                                            "${navState.selectedDemoSpeed.toInt()} km/h  •  ${navState.selectedDemoHeading.toInt()}° $cardinal"
                                        }
                                        navState.isDemoModeEnabled -> "Ready — configure speed and heading"
                                        navState.blackoutMode -> "Dead Reckoning Active — GNSS unavailable"
                                        navState.hasGpsFix -> {
                                            "${navState.locationProvider.uppercase(Locale.US)}  |  " +
                                                "±${navState.locationAccuracyMeters.toInt()} m accuracy (${navState.gpsAccuracyLevel.label})"
                                        }
                                        navState.gpsState == GpsState.GPS_DISABLED -> "Enable device location in Android Settings"
                                        navState.gpsState == GpsState.PERMISSION_REQUIRED -> "Grant Location permission to center map"
                                        else -> "Acquiring precise satellite location..."
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textColorPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive -> "SIMULATING"
                                    navState.isDemoModeEnabled -> "READY"
                                    navState.blackoutMode -> "ESTIMATED"
                                    navState.hasGpsFix -> "${navState.locationAgeSeconds}s old"
                                    else -> "NO FIX"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive -> Color(0xFF047857)
                                    navState.isDemoModeEnabled -> Color(0xFF2563EB)
                                    navState.blackoutMode -> Color(0xFFB91C1C)
                                    navState.gpsState == GpsState.FIXED -> Color(0xFF047857)
                                    navState.hasGpsFix -> Color(0xFFB45309)
                                    else -> Color(0xFFB91C1C)
                                }
                            )
                        }
                    }

                    // GNSS BLACKOUT ALERT BANNER
                    if (navState.blackoutMode) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, Color(0xFFEF4444), RoundedCornerShape(14.dp)),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDarkMode) Color(0xFF3F1212) else Color(0xFFFEF2F2)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = Color(0xFFDC2626)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (navState.isSimulatedBlackout) "Simulated GNSS Blackout" else "GNSS Blackout Active",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626)
                                        )
                                        Text(
                                            text = if (navState.isSimulatedBlackout) "Demo Mode • EKF & Model Running" else "On-Device AI Dead Reckoning Engine",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFF991B1B),
                                            maxLines = 1
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFDC2626)
                                ) {
                                    Text(
                                        text = if (navState.motionMode == "VEHICLE_MODE") "Vehicle" else "Pedestrian",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    if (navState.isDemoModeEnabled && !navState.hasDemoStartAnchor) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDarkMode) Color(0xFF422006) else Color(0xFFFFFBEB),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDarkMode) Color(0xFF92400E) else Color(0xFFFDE68A)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GpsFixed,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isDarkMode) Color(0xFFFDE68A) else Color(0xFF92400E)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Waiting for GPS starting location. Turn on Location to anchor Demo Mode.",
                                    color = if (isDarkMode) Color(0xFFFDE68A) else Color(0xFF92400E),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if ((navState.hasGpsFix || navState.isDemoModeEnabled) &&
                        (!navState.accelerometerActive || !navState.gyroscopeActive || !navState.magnetometerActive)
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDarkMode) Color(0xFF422006) else Color(0xFFFFFBEB),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDarkMode) Color(0xFF92400E) else Color(0xFFFDE68A)
                            )
                        ) {
                            Text(
                                text = "Some motion sensors are unavailable. Dead-reckoning accuracy may be reduced.",
                                color = if (isDarkMode) Color(0xFFFDE68A) else Color(0xFF92400E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // MAP CONTAINER
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, cardBorderColor, RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            MapView(
                                latitude = navState.latitude,
                                longitude = navState.longitude,
                                headingDeg = navState.headingDeg,
                                deviceHeadingDeg = navState.deviceHeadingDeg,
                                mapStatus = navState.mapStatus,
                                offlineMapStatus = navState.offlineMapStatus,
                                roadName = navState.currentRoadName,
                                blackoutMode = navState.blackoutMode,
                                naiveLatitude = navState.naiveLatitude,
                                naiveLongitude = navState.naiveLongitude,
                                uncertaintyRadiusMeters = navState.uncertaintyRadiusMeters,
                                locationAccuracyMeters = navState.locationAccuracyMeters,
                                locationProvider = navState.locationProvider,
                                demoTrailPoints = navState.demoTrailPoints,
                                demoTrailSegments = navState.demoTrailSegments,
                                gpsTrailPoints = navState.gpsTrailPoints,
                                predictionTrailPoints = navState.predictionTrailPoints,
                                predictionTrailSegments = navState.predictionTrailSegments,
                                visualMode = navState.visualMode,
                                lastTrustedGpsLat = navState.lastTrustedGpsLat,
                                lastTrustedGpsLon = navState.lastTrustedGpsLon,
                                lastTrustedGpsAccuracyMeters = navState.lastTrustedGpsAccuracyMeters,
                                lastTrustedGpsTimestampNs = navState.lastTrustedGpsTimestampNs,
                                lastKnownLocationAgeSeconds = navState.lastKnownLocationAgeSeconds,
                                historicalLkMarkers = navState.historicalLkMarkers,
                                lastPredictedLat = navState.lastPredictedLat,
                                lastPredictedLon = navState.lastPredictedLon,
                                predictionUncertaintyMeters = navState.predictionUncertaintyMeters,
                                hasGpsFix = navState.hasGpsFix,
                                gpsState = navState.gpsState,
                                isDemoModeEnabled = navState.isDemoModeEnabled,
                                isKinematicDemoActive = navViewModel.isKinematicDemoActive,
                                isExpanded = isMapExpanded,
                                isMinimized = isMapMinimized,
                                isDarkMode = isDarkMode,
                                onToggleExpand = { isMapExpanded = !isMapExpanded },
                                onMinimizeMap = {
                                    if (isMapExpanded) {
                                        isMapExpanded = false
                                    } else {
                                        isMapMinimized = true
                                    }
                                },
                                onExpandMap = {
                                    if (isMapMinimized) {
                                        isMapMinimized = false
                                    } else {
                                        isMapExpanded = true
                                    }
                                },
                                onMyLocationClick = {
                                    val hasFine = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val hasCoarse = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (!hasFine && !hasCoarse) {
                                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        val sysLocMgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                                        val isGpsOn = try {
                                            sysLocMgr?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                                                    sysLocMgr?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                                        } catch (e: Exception) {
                                            false
                                        }

                                        if (!isGpsOn) {
                                            try {
                                                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Log.w("NavigationScreen", "Unable to launch Location Settings: ${e.message}")
                                            }
                                        } else {
                                            navViewModel.retryLocationUpdatesIfNeeded()
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            onClick = {
                                if (navState.isRecordingGpx) {
                                    navViewModel.stopGpxRecording()
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val saved = navViewModel.exportTrackImageToGallery(context)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            if (saved) {
                                                Toast.makeText(context, "Track saved to Gallery (Pictures/NAVIATOR)", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "No track recorded to save", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    navViewModel.startGpxRecording()
                                    Toast.makeText(context, "Track recording started", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (navState.isRecordingGpx) Color(0xFFB91C1C) else Color(0xFF2563EB)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (navState.isRecordingGpx) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (navState.isRecordingGpx) "Stop Recording" else "Record Track",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            onClick = {
                                shareActiveLocation(context, navState)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                                contentColor = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDarkMode) Color(0xFF334155) else Color(0xFFCBD5E1)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share Location", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (navState.isDemoModeEnabled) {
                        Spacer(modifier = Modifier.height(14.dp))
                        KinematicDemoControlCard(
                            navViewModel = navViewModel,
                            navState = navState,
                            isDarkMode = isDarkMode
                        )
                    }

                    if (navState.blackoutMode) {
                        Spacer(modifier = Modifier.height(14.dp))
                        PlainConfidenceCard(uncertaintyRadiusMeters = navState.uncertaintyRadiusMeters, isDarkMode = isDarkMode)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // TELEMETRY METRIC DASHBOARD GRID (2x2 Grid Layout)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val isDemoRunning = navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive

                            val speedLabel = when {
                                isDemoRunning -> "Speed (Simulated)"
                                navState.blackoutMode -> "Speed (Estimated)"
                                else -> "Speed"
                            }
                            val speedValue = when {
                                isDemoRunning -> String.format(Locale.US, "%.1f km/h", navState.selectedDemoSpeed)
                                navState.blackoutMode -> String.format(Locale.US, "Est. %.1f km/h", navState.displayedSpeedKmh)
                                else -> String.format(Locale.US, "%.1f km/h", navState.displayedSpeedKmh)
                            }
                            val displayHeading = navState.headingDeg

                            QuickMetricCard(
                                icon = Icons.Default.Speed,
                                label = speedLabel,
                                value = speedValue,
                                accentColor = Color(0xFF2563EB),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                            QuickMetricCard(
                                icon = Icons.Default.Explore,
                                label = "Direction",
                                value = String.format(Locale.US, "%.0f° %s", displayHeading, getCardinalDirection(displayHeading)),
                                accentColor = Color(0xFF10B981),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            QuickMetricCard(
                                icon = Icons.Default.Straighten,
                                label = if (navState.blackoutMode) "Estimated Distance" else "Distance",
                                value = formatDistance(navState.distanceMeters),
                                accentColor = Color(0xFF8B5CF6),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                            QuickMetricCard(
                                icon = Icons.Default.GpsFixed,
                                label = if (navState.blackoutMode) "Estimated Uncertainty" else "Position Accuracy",
                                value = String.format(Locale.US, "±%.1f m", navState.uncertaintyRadiusMeters),
                                accentColor = Color(0xFFF59E0B),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        MotionStatusCard(
                            navState = navState,
                            isKinematicDemoActive = navViewModel.isKinematicDemoActive,
                            isDarkMode = isDarkMode
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // POSITION BAR CARD (Latitude & Longitude)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = cardBgColor),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = textColorSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val positionTitle = when {
                                    navState.isDemoModeEnabled -> "Demo Location"
                                    navState.blackoutMode -> "Predicted Location"
                                    navState.hasGpsFix -> "GPS Fixed"
                                    else -> "Searching for GPS"
                                }
                                Text(
                                    text = positionTitle,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = textColorSecondary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val chipBg = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                                val chipBorder = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)

                                val hasValidDisplayPos = navState.latitude.isFinite() &&
                                    navState.longitude.isFinite() &&
                                    navState.latitude in -90.0..90.0 &&
                                    navState.longitude in -180.0..180.0 &&
                                    (navState.latitude != 0.0 || navState.longitude != 0.0)
                                val latStr = if (hasValidDisplayPos) String.format(Locale.US, "%.5f°", navState.latitude) else "--.-----°"
                                val lonStr = if (hasValidDisplayPos) String.format(Locale.US, "%.5f°", navState.longitude) else "--.-----°"

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, chipBorder, RoundedCornerShape(12.dp)),
                                    color = chipBg,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            text = "LATITUDE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = textColorSecondary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = latStr,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textColorPrimary
                                        )
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, chipBorder, RoundedCornerShape(12.dp)),
                                    color = chipBg,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            text = "LONGITUDE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = textColorSecondary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = lonStr,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textColorPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // EXPANDABLE TECHNICAL DETAILS CARD
                    val rotationDegrees by animateFloatAsState(
                        targetValue = if (isTechnicalDetailsExpanded) 180f else 0f,
                        label = "chevronRotation"
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                            .semantics {
                                stateDescription = if (isTechnicalDetailsExpanded) "Expanded" else "Collapsed"
                            },
                        colors = CardDefaults.cardColors(containerColor = cardBgColor),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = if (isTechnicalDetailsExpanded) "Collapse technical details" else "Expand technical details"
                                    ) {
                                        isTechnicalDetailsExpanded = !isTechnicalDetailsExpanded
                                    }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isTechnicalDetailsExpanded) "Hide Technical Details" else "View Technical Details",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2563EB)
                                )
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer { rotationZ = rotationDegrees },
                                    tint = Color(0xFF2563EB)
                                )
                            }

                            if (isTechnicalDetailsExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    HorizontalDivider(color = cardBorderColor)
                                    SystemArchitectureStatusCard(navState = navState, isDarkMode = isDarkMode)
                                    LiveTelemetryCard(navState = navState, isDarkMode = isDarkMode)
                                    TechnicalEvaluationCard(navState = navState, navViewModel = navViewModel, isDarkMode = isDarkMode)
                                    SessionManagementCard(navState = navState, navViewModel = navViewModel, isDarkMode = isDarkMode)
                                }
                            }
                        }
                    }

                    if (!permissionGranted) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(14.dp),
                            onClick = {
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        ) {
                            Text("GRANT LOCATION PERMISSION", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }

    when (sosState) {
        com.example.gudumap.sos.SosState.CONFIRMATION -> {
            com.example.gudumap.sos.SosConfirmationDialog(
                isDarkMode = isDarkMode,
                onConfirm = { sosState = com.example.gudumap.sos.SosState.COUNTDOWN },
                onDismiss = { sosState = com.example.gudumap.sos.SosState.IDLE }
            )
        }
        com.example.gudumap.sos.SosState.COUNTDOWN -> {
            com.example.gudumap.sos.SosCountdownDialog(
                isDarkMode = isDarkMode,
                initialSeconds = com.example.gudumap.sos.SosConfig.SOS_COUNTDOWN_SECONDS,
                onCountdownComplete = {
                    sosState = com.example.gudumap.sos.SosState.IDLE
                    sosViewModel.startSosBroadcast(navState)
                },
                onCancel = {
                    sosState = com.example.gudumap.sos.SosState.IDLE
                }
            )
        }
        com.example.gudumap.sos.SosState.NO_CONTACTS -> {
            com.example.gudumap.sos.SosNoContactsDialog(
                isDarkMode = isDarkMode,
                onDismiss = { sosState = com.example.gudumap.sos.SosState.IDLE }
            )
        }
        else -> {}
    }

    if (showSosConfirmationDialog) {
        com.example.gudumap.sos.SosConfirmationDialog(
            isDarkMode = isDarkMode,
            onConfirm = {
                showSosConfirmationDialog = false
                showSosCountdownDialog = true
            },
            onDismiss = { showSosConfirmationDialog = false }
        )
    }

    if (showSosCountdownDialog) {
        com.example.gudumap.sos.SosCountdownDialog(
            isDarkMode = isDarkMode,
            onCountdownComplete = {
                showSosCountdownDialog = false
                sosViewModel.startSosBroadcast(navState)
            },
            onCancel = { showSosCountdownDialog = false }
        )
    }

    if (showSosBluetoothDisabledDialog) {
        com.example.gudumap.sos.SosBluetoothDisabledDialog(
            isDarkMode = isDarkMode,
            onDismiss = { showSosBluetoothDisabledDialog = false }
        )
    }

    if (showSosCancelConfirmationDialog) {
        com.example.gudumap.sos.SosCancelConfirmationDialog(
            isDarkMode = isDarkMode,
            onConfirmCancel = {
                showSosCancelConfirmationDialog = false
                sosViewModel.cancelSos()
            },
            onDismiss = { showSosCancelConfirmationDialog = false }
        )
    }

    if (showActiveSosDialog) {
        com.example.gudumap.sos.SosActiveDialog(
            status = sosStatus,
            ackCount = acknowledgedReceivers.size,
            isDarkMode = isDarkMode,
            onDismiss = { showActiveSosDialog = false },
            onCancelSosClick = {
                showActiveSosDialog = false
                showSosCancelConfirmationDialog = true
            },
            onSendSmsClick = {
                val sosLoc = com.example.gudumap.sos.SosLocationResolver.resolve(navState)
                val msg = com.example.gudumap.sos.SosMessageBuilder.buildMessage(sosLoc)
                val contacts = com.example.gudumap.sos.SosConfig.getValidContacts()
                com.example.gudumap.sos.SosLauncher.launchSmsComposer(context, contacts, msg)
            }
        )
    }

    val currentReceivedItem = selectedReceivedSos ?: receivedSosList.firstOrNull { !it.isDismissed && !it.isAcknowledged }
    if (currentReceivedItem != null) {
        com.example.gudumap.ui.screens.ReceivedSosDialog(
            item = currentReceivedItem,
            myCurrentLat = navState.latitude,
            myCurrentLon = navState.longitude,
            isDarkMode = isDarkMode,
            onViewOnMap = { lat, lon ->
                sosViewModel.dismissReceivedSos(currentReceivedItem.packet.sosId)
            },
            onAcknowledge = { sosId ->
                sosViewModel.acknowledgeReceivedSos(sosId)
            },
            onDismiss = { sosId ->
                sosViewModel.dismissReceivedSos(sosId)
            }
        )
    }
}

@Composable
private fun SystemArchitectureStatusCard(
    navState: NavigationState,
    isDarkMode: Boolean
) {
    val cardBgColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SYSTEM ARCHITECTURE STATUS",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF2563EB),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            val gnssDisplay = when {
                navState.isDemoModeEnabled -> "SIMULATION"
                navState.blackoutMode -> "BLACKOUT"
                navState.gnssNavigationMode == "GNSS_RECOVERY" -> "RECOVERING"
                navState.hasGpsFix -> "AVAILABLE"
                else -> "SEARCHING"
            }
            val navDisplay = when {
                navState.isDemoModeEnabled && navState.blackoutMode -> "SIMULATED DR"
                navState.isDemoModeEnabled -> "DEMO SIMULATION"
                navState.blackoutMode -> "DEAD RECKONING"
                navState.hasGpsFix -> "GNSS"
                else -> "WAITING FOR GPS"
            }
            val gateDisplay = "${navState.latestGateAction} (A:${navState.acceptedCount} C:${navState.clampedCount} R:${navState.rejectedCount})"
            val mlDisplay = navState.mlStatus.replace("_", " ")
            val ekfDisplay = navState.ekfStatus.replace("_", " ")

            StatusRow(label = "GNSS Fix", value = gnssDisplay, isGood = gnssDisplay == "AVAILABLE", textColor = textColorPrimary)
            StatusRow(label = "Nav Provider", value = navDisplay, isGood = navDisplay == "GNSS" || navDisplay == "DEAD RECKONING", textColor = textColorPrimary)
            StatusRow(label = "Motion Data Source", value = if (navState.isDemoModeEnabled) "DEMO" else "REAL IMU", isGood = true, textColor = textColorPrimary)
            StatusRow(label = "ZUPT State", value = if (navState.zuptActive) "ACTIVE" else "INACTIVE", isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Position Source", value = navState.positionSource, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Speed Source", value = navState.speedSource, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Heading Source", value = navState.headingSource, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Accuracy Source", value = navState.accuracySource, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "ML Innovation Gate", value = gateDisplay, isGood = navState.latestGateAction == "ACCEPTED", textColor = textColorPrimary)
            StatusRow(label = "ML Residual Model", value = mlDisplay, isGood = navState.mlStatus == "INFERENCE_RUNNING" || navState.mlStatus == "MODEL_READY", textColor = textColorPrimary)
            StatusRow(label = "EKF Filter State", value = ekfDisplay, isGood = navState.ekfStatus != "UNINITIALIZED", textColor = textColorPrimary)
            StatusRow(label = "Offline Tile Archive", value = navState.offlineMapStatus, isGood = navState.offlineMapStatus == "AVAILABLE", textColor = textColorPrimary)
            StatusRow(label = "Internet Connection", value = if (navState.isInternetAvailable) "CONNECTED" else "OFFLINE", isGood = navState.isInternetAvailable, textColor = textColorPrimary)
        }
    }
}

@Composable
private fun LiveTelemetryCard(
    navState: NavigationState,
    isDarkMode: Boolean
) {
    val cardBgColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "LIVE FILTER & LATENCY TELEMETRY",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF2563EB),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            val anyFixAge = navState.lastAnyGnssFixAgeSeconds ?: navState.locationAgeSeconds
            val trustedFixAge = if (navState.hasGpsFix) "${navState.locationAgeSeconds}s" else "No fix"
            val rawGnssText = if (navState.rawLatitude != 0.0) String.format(Locale.US, "%.5f, %.5f", navState.rawLatitude, navState.rawLongitude) else "None"
            val fusedPosText = String.format(Locale.US, "%.5f, %.5f", navState.latitude, navState.longitude)
            val isMarkerVis = com.example.gudumap.ui.components.isValidMapCoordinate(navState.latitude, navState.longitude)

            Text(text = "• Location Callback: ${if (navState.locationCallbackActive) "ACTIVE" else "INACTIVE"}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "• Any Fix Age: ${anyFixAge}s | Trusted Fix Age: $trustedFixAge", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "• Raw GNSS: $rawGnssText", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "• Fused Position: $fusedPosText", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "• Marker Position: $fusedPosText | Visible: $isMarkerVis", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "• Speed Telemetry: Raw %.1f | Filtered %.1f | Displayed %.1f km/h",
                    navState.rawSpeedKmh,
                    navState.filteredSpeedKmh,
                    navState.displayedSpeedKmh
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColorPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "• Heading Confidence: ${navState.headingConfidence}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            val uncertaintyText = if (navState.uncertaintyRadiusMeters > 0.0) {
                String.format(Locale.US, "±%.1f m", navState.uncertaintyRadiusMeters)
            } else if (navState.hasGpsFix && navState.locationAccuracyMeters > 0f) {
                String.format(Locale.US, "±%.1f m", navState.locationAccuracyMeters)
            } else {
                "Unavailable"
            }
            Text(text = "• Position Uncertainty: $uncertaintyText", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            val fixAgeText = when {
                navState.hasGpsFix -> "${navState.locationAgeSeconds} s"
                navState.lastKnownLocationAgeSeconds != null -> "Stale (${navState.lastKnownLocationAgeSeconds} s)"
                else -> "Waiting for fix"
            }
            Text(text = "• GPS Fix Age: $fixAgeText", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            val mlLatencyText = if (navState.mlInferenceLatencyMs > 0) {
                "${navState.mlInferenceLatencyMs} ms"
            } else {
                "Idle"
            }
            Text(text = "• ML Inference Latency: $mlLatencyText", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
        }
    }
}

@Composable
private fun TechnicalEvaluationCard(
    navState: NavigationState,
    navViewModel: NavigationViewModel,
    isDarkMode: Boolean
) {
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val m = navState.blackoutMetrics

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2563EB), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFEFF6FF)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Assessment,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF2563EB)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Technical Evaluation",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2563EB)
                    )
                }
                if (navState.isSimulatedBlackout) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFDC2626)
                    ) {
                        Text(
                            text = "SIMULATED",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val blackoutDurText = if (navState.blackoutDurationSeconds > 0.0) {
                String.format(Locale.US, "%.1f s", navState.blackoutDurationSeconds)
            } else {
                "0.0 s"
            }
            val drDistText = if (m.drDistance > 0.0) {
                String.format(Locale.US, "%.1f m", m.drDistance)
            } else {
                "0.0 m"
            }
            val maxErrText = if (m.maximumPositionErrorMeters > 0.0) {
                String.format(Locale.US, "%.1f m", m.maximumPositionErrorMeters)
            } else {
                "Waiting for recovery"
            }
            val finalErrText = if (navState.positionErrorMeters > 0.0) {
                String.format(Locale.US, "%.1f m", navState.positionErrorMeters)
            } else {
                "Waiting for recovery"
            }
            val mlGateText = "A:${m.numberOfAcceptedMLPredictions} C:${m.numberOfClampedMLPredictions} R:${m.numberOfRejectedMLPredictions}"
            val avgLatencyText = if (m.mlInferenceMs > 0) "${m.mlInferenceMs} ms" else "Idle"

            StatusRow(label = "Blackout Duration", value = blackoutDurText, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "DR Distance Travelled", value = drDistText, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Max Position Error", value = maxErrText, isGood = m.maximumPositionErrorMeters in 0.01..15.0, textColor = textColorPrimary)
            StatusRow(label = "Final Position Error", value = finalErrText, isGood = navState.positionErrorMeters in 0.01..10.0, textColor = textColorPrimary)
            StatusRow(label = "ML Gate Decisions", value = mlGateText, isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Avg ML Latency", value = avgLatencyText, isGood = true, textColor = textColorPrimary)

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                onClick = { navViewModel.start10sEvaluationMode() },
                enabled = !navState.isEvaluationActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (navState.isEvaluationActive) Color(0xFFD97706) else Color(0xFF2563EB)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Assessment,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (navState.isEvaluationActive) {
                            "Testing... ${navState.evaluationTimeRemainingSec}s remaining"
                        } else {
                            "Run 10s Accuracy Test"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionManagementCard(
    navState: NavigationState,
    navViewModel: NavigationViewModel,
    isDarkMode: Boolean
) {
    val context = LocalContext.current
    val cardBgColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textColorSecondary = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SESSION SUMMARY & EXPORT TRACKS",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF2563EB),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            StatusRow(label = "Track Points Recorded", value = "${navState.recordedPointCount} pts", isGood = navState.recordedPointCount > 0, textColor = textColorPrimary)
            StatusRow(label = "Total Session Distance", value = formatDistance(navState.distanceMeters), isGood = true, textColor = textColorPrimary)
            StatusRow(label = "Navigation Source", value = navState.navigationSource, isGood = true, textColor = textColorPrimary)

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = cardBorderColor.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            val canExport = navState.recordedPointCount > 0

            Text(
                text = "Export Trajectory Data",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColorSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExportFormatButton(
                    label = "GPX",
                    canExport = canExport,
                    isDarkMode = isDarkMode,
                    onClick = {
                        if (canExport) {
                            navViewModel.exportGpxTrack(context)
                        } else {
                            Toast.makeText(context, "No trajectory points to export", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                ExportFormatButton(
                    label = "CSV",
                    canExport = canExport,
                    isDarkMode = isDarkMode,
                    onClick = {
                        if (canExport) {
                            navViewModel.exportCsvTrack(context)
                        } else {
                            Toast.makeText(context, "No trajectory points to export", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                ExportFormatButton(
                    label = "JSON",
                    canExport = canExport,
                    isDarkMode = isDarkMode,
                    onClick = {
                        if (canExport) {
                            navViewModel.exportJsonTrack(context)
                        } else {
                            Toast.makeText(context, "No trajectory points to export", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                onClick = {
                    navViewModel.clearSession()
                    Toast.makeText(context, "Session history cleared", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFDC2626)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFDC2626)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Session Data", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ExportFormatButton(
    label: String,
    canExport: Boolean,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (canExport) {
        if (isDarkMode) Color(0xFF0F172A) else Color(0xFFEFF6FF)
    } else {
        if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9)
    }
    val contentColor = if (canExport) {
        if (isDarkMode) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
    } else {
        if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
    }
    val borderColor = if (canExport) {
        if (isDarkMode) Color(0xFF3B82F6) else Color(0xFF2563EB)
    } else {
        if (isDarkMode) Color(0xFF334155) else Color(0xFFCBD5E1)
    }

    OutlinedButton(
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(0.dp),
        enabled = canExport,
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
private fun PlainConfidenceCard(
    uncertaintyRadiusMeters: Double,
    isDarkMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val level = confidenceLevel(uncertaintyRadiusMeters)
    val color = confidenceColor(level)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Position Confidence",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                )
                Text(
                    text = if (uncertaintyRadiusMeters > 0.0) {
                        String.format(Locale.US, "Estimated uncertainty: %.0f m", uncertaintyRadiusMeters)
                    } else {
                        "Deterministic kinematic track — no uncertainty model"
                    },
                    fontSize = 11.sp,
                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = color.copy(alpha = 0.12f)
            ) {
                Text(
                    text = level,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = color,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickMetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    accentColor: Color = Color(0xFF2563EB),
    isDarkMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textColorSecondary = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    Card(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = label.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = textColorSecondary,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp).size(16.dp),
                        tint = accentColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Black,
                color = textColorPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, isGood: Boolean, textColor: Color = Color(0xFF475569)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = textColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isGood) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
        ) {
            Text(
                text = value,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isGood) Color(0xFF047857) else Color(0xFFDC2626),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MotionStatusCard(
    navState: NavigationState,
    isKinematicDemoActive: Boolean,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textColorSecondary = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    val isDemoRunning = navState.isDemoModeEnabled && isKinematicDemoActive

    val cardLabel = if (isDemoRunning) {
        "Motion Status (Simulated)"
    } else {
        "Motion Status"
    }

    val (targetStatus, subtext) = when {
        isDemoRunning -> {
            if (navState.selectedDemoSpeed > 0f) {
                "Moving" to String.format(Locale.US, "Software simulation active (%.0f km/h)", navState.selectedDemoSpeed)
            } else {
                "Stationary" to "Software simulation active (0 km/h)"
            }
        }
        !navState.accelerometerActive || !navState.gyroscopeActive -> {
            "Unavailable" to "Required motion sensors unavailable"
        }
        navState.motionState == "UNKNOWN" || navState.motionState == "DETECTING" || navState.motionState == "UNCERTAIN" -> {
            "Detecting…" to "Analyzing sensor stream…"
        }
        navState.motionState == "MOVING" || navState.motionState == "WALKING" -> {
            "Moving" to "Physical movement detected"
        }
        else -> {
            val note = if (navState.motionState == "ROTATING_IN_PLACE") "Device still (in-place turn)" else "Device stationary"
            "Stationary" to note
        }
    }

    var currentStatus by remember { mutableStateOf(targetStatus) }
    var currentSubtext by remember { mutableStateOf(subtext) }

    androidx.compose.runtime.LaunchedEffect(targetStatus, subtext) {
        if (targetStatus != currentStatus) {
            kotlinx.coroutines.delay(150L)
            currentStatus = targetStatus
            currentSubtext = subtext
        } else {
            currentSubtext = subtext
        }
    }

    val (statusColor, badgeBg, iconVector) = when (currentStatus) {
        "Moving" -> Triple(
            Color(0xFF2563EB),
            if (isDarkMode) Color(0xFF1E3A8A) else Color(0xFFEFF6FF),
            Icons.AutoMirrored.Filled.DirectionsWalk
        )
        "Stationary" -> Triple(
            textColorSecondary,
            if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF1F5F9),
            Icons.AutoMirrored.Filled.DirectionsWalk
        )
        "Detecting…" -> Triple(
            Color(0xFFF59E0B),
            if (isDarkMode) Color(0xFF422006) else Color(0xFFFFFBEB),
            Icons.Default.Explore
        )
        else -> Triple(
            Color(0xFFDC2626),
            if (isDarkMode) Color(0xFF450A0A) else Color(0xFFFEF2F2),
            Icons.Default.Warning
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cardLabel.uppercase(Locale.US),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = textColorSecondary,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentStatus,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Black,
                    color = if (currentStatus == "Moving") Color(0xFF2563EB) else textColorPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentSubtext,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColorSecondary,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = badgeBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = currentStatus,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusColor
                    )
                }
            }
        }
    }
}

private fun getCardinalDirection(headingDeg: Float): String {
    val normalized = (headingDeg % 360 + 360) % 360
    return when {
        normalized >= 337.5 || normalized < 22.5 -> "N"
        normalized >= 22.5 && normalized < 67.5 -> "NE"
        normalized >= 67.5 && normalized < 112.5 -> "E"
        normalized >= 112.5 && normalized < 157.5 -> "SE"
        normalized >= 157.5 && normalized < 202.5 -> "S"
        normalized >= 202.5 && normalized < 247.5 -> "SW"
        normalized >= 247.5 && normalized < 292.5 -> "W"
        normalized >= 292.5 && normalized < 337.5 -> "NW"
        else -> "N"
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters < 1000.0) {
        String.format(Locale.US, "%.0f m", meters)
    } else {
        String.format(Locale.US, "%.2f km", meters / 1000.0)
    }
}

@Composable
private fun KinematicDemoControlCard(
    navViewModel: com.example.gudumap.viewmodel.NavigationViewModel,
    navState: com.example.gudumap.navigation.NavigationState,
    isDarkMode: Boolean
) {
    val speed = navState.selectedDemoSpeed
    val heading = navState.selectedDemoHeading

    val cardBg = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorder = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textPrimary = if (isDarkMode) Color.White else Color(0xFF0F172A)
    val textSecondary = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF64748B)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = textPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Blackout Mode",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        navViewModel.isKinematicDemoActive -> Color(0xFFDC2626)
                        navState.demoTrailPoints.isNotEmpty() -> Color(0xFFD97706)
                        else -> Color(0xFF2563EB)
                    }
                ) {
                    Text(
                        text = when {
                            navViewModel.isKinematicDemoActive -> "Demo Active"
                            navState.demoTrailPoints.isNotEmpty() -> "Demo Stopped"
                            else -> "Demo Ready"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Speed Slider & Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.US, "Speed: %.1f km/h", speed),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
                Text(
                    text = String.format(Locale.US, "(%.1f m/s)", speed / 3.6f),
                    fontSize = 11.sp,
                    color = textSecondary
                )
            }

            Slider(
                value = speed,
                onValueChange = { newSpeed ->
                    navViewModel.updateKinematicDemoControls(newSpeed, heading)
                },
                valueRange = 0f..120f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0f, 30f, 60f, 90f).forEach { preset ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f).height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                        onClick = {
                            navViewModel.updateKinematicDemoControls(preset, heading)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (speed == preset) Color(0xFF2563EB) else (if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF1F5F9)),
                            contentColor = if (speed == preset) Color.White else textPrimary
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("${preset.toInt()} km/h", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Heading Slider & Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.US, "Heading: %.0f° (%s)", heading, getCardinalDirection(heading)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
            }

            Slider(
                value = heading,
                onValueChange = { newHeading ->
                    navViewModel.updateKinematicDemoControls(speed, newHeading)
                },
                valueRange = 0f..360f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0f to "N (0°)", 90f to "E (90°)", 180f to "S (180°)", 270f to "W (270°)").forEach { (deg, label) ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f).height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                        onClick = {
                            navViewModel.updateKinematicDemoControls(speed, deg)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (heading == deg) Color(0xFF2563EB) else (if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF1F5F9)),
                            contentColor = if (heading == deg) Color.White else textPrimary
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(label, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Start/Stop & Reset Anchor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1.2f).height(40.dp),
                    onClick = {
                        if (navViewModel.isKinematicDemoActive) {
                            navViewModel.stopKinematicDemo()
                        } else {
                            navViewModel.startKinematicDemo(
                                initialSpeedKmh = speed,
                                initialHeadingDeg = heading
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (navViewModel.isKinematicDemoActive) Color(0xFFDC2626) else Color(0xFF10B981)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = when {
                            navViewModel.isKinematicDemoActive -> "Stop Demo"
                            navState.demoTrailPoints.isNotEmpty() -> "Run Again"
                            else -> "Start Demo"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    modifier = Modifier.weight(0.9f).height(40.dp),
                    onClick = { navViewModel.resetKinematicDemoAnchor() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                        contentColor = textPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Reset Anchor", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private enum class LocationShareSource {
    LIVE_GPS,
    DEMO,
    PREDICTED,
    LAST_KNOWN
}

private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
    return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
}

private fun shareActiveLocation(context: Context, navState: NavigationState) {
    val (shareLat, shareLon, source) = when {
        navState.isDemoModeEnabled && isValidCoordinate(navState.latitude, navState.longitude) -> {
            Triple(navState.latitude, navState.longitude, LocationShareSource.DEMO)
        }
        navState.visualMode == com.example.gudumap.navigation.NavigationVisualMode.LIVE && navState.hasGpsFix && isValidCoordinate(navState.latitude, navState.longitude) -> {
            Triple(navState.latitude, navState.longitude, LocationShareSource.LIVE_GPS)
        }
        navState.visualMode == com.example.gudumap.navigation.NavigationVisualMode.GPS_FALLBACK && isValidCoordinate(navState.latitude, navState.longitude) -> {
            Triple(navState.latitude, navState.longitude, LocationShareSource.PREDICTED)
        }
        navState.lastTrustedGpsLat != null && navState.lastTrustedGpsLon != null && isValidCoordinate(navState.lastTrustedGpsLat!!, navState.lastTrustedGpsLon!!) -> {
            Triple(navState.lastTrustedGpsLat!!, navState.lastTrustedGpsLon!!, LocationShareSource.LAST_KNOWN)
        }
        isValidCoordinate(navState.latitude, navState.longitude) -> {
            Triple(navState.latitude, navState.longitude, LocationShareSource.LAST_KNOWN)
        }
        else -> Triple(Double.NaN, Double.NaN, null)
    }

    if (source == null || !shareLat.isFinite() || !shareLon.isFinite()) {
        Toast.makeText(context, "Location not available yet", Toast.LENGTH_SHORT).show()
        return
    }

    val mapsUrl = "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f".format(Locale.US, shareLat, shareLon)

    val messageText = when (source) {
        LocationShareSource.LIVE_GPS -> {
            val accStr = if (navState.locationAccuracyMeters > 0f) "\nAccuracy: approximately ±${navState.locationAccuracyMeters.toInt()} m" else ""
            "My current location:\n$mapsUrl$accStr"
        }
        LocationShareSource.DEMO -> {
            "Simulated location (NAVIGATOR Demo Mode):\n$mapsUrl"
        }
        LocationShareSource.PREDICTED -> {
            val accStr = if (navState.predictionUncertaintyMeters > 0.0) "\nEstimated accuracy: ±${navState.predictionUncertaintyMeters.toInt()} m" else ""
            "Estimated location (GPS unavailable):\n$mapsUrl$accStr"
        }
        LocationShareSource.LAST_KNOWN -> {
            val ageStr = if (navState.gpsFixAgeMs > 0L) "\nLast GPS fix: ${navState.gpsFixAgeMs / 1000L} seconds ago" else ""
            "Last known location:\n$mapsUrl$ageStr"
        }
    }

    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, messageText)
            type = "text/plain"
        }
        val chooser = Intent.createChooser(sendIntent, "Share Location via")
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.w("NavigationScreen", "Could not launch Share Intent: ${e.message}")
        Toast.makeText(context, "Unable to launch Share sheet", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SosDiagnosticsCard(
    sosViewModel: com.example.gudumap.sos.SosViewModel,
    isDarkMode: Boolean
) {
    val cardBgColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)

    val isBtOn = sosViewModel.isBluetoothEnabled()
    val isScanPermission = sosViewModel.hasScanPermission()
    val isAdvPermission = sosViewModel.hasAdvertisePermission()
    val isConnectPermission = sosViewModel.hasConnectPermission()
    val isNearbyGranted = isScanPermission && isAdvPermission && isConnectPermission

    val isScannerActive by sosViewModel.isScannerActive.collectAsState()
    val lastAdvTimeMs by sosViewModel.lastAdvTimeMs.collectAsState()
    val totalPacketsReceived by sosViewModel.totalPacketsReceived.collectAsState()

    val context = LocalContext.current
    val isNotifPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    val lastAdvSecAgo = if (lastAdvTimeMs > 0L) {
        "${(System.currentTimeMillis() - lastAdvTimeMs) / 1000L}s ago"
    } else {
        "Never"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "BLE SOS DIAGNOSTICS & HARDWARE STATUS",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF2563EB),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            StatusRow(label = "Bluetooth Hardware", value = if (isBtOn) "ON" else "OFF", isGood = isBtOn, textColor = textColorPrimary)
            StatusRow(label = "Nearby Devices Permission", value = if (isNearbyGranted) "GRANTED" else "DENIED", isGood = isNearbyGranted, textColor = textColorPrimary)
            StatusRow(label = "SOS BLE Scanner", value = if (isScannerActive) "ACTIVE" else "INACTIVE", isGood = isScannerActive, textColor = textColorPrimary)
            StatusRow(label = "Last BLE Advertisement", value = lastAdvSecAgo, isGood = lastAdvTimeMs > 0L, textColor = textColorPrimary)
            StatusRow(label = "SOS Packets Received", value = "$totalPacketsReceived", isGood = totalPacketsReceived > 0, textColor = textColorPrimary)
            StatusRow(label = "Notification Permission", value = if (isNotifPermission) "GRANTED" else "DENIED", isGood = isNotifPermission, textColor = textColorPrimary)
        }
    }
}
