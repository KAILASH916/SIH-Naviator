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
    var currentScreen by remember { mutableStateOf("MAP") } // "MAP" or "SYSTEM_ARCHITECTURE"
    var sosState by remember { mutableStateOf(com.example.gudumap.sos.SosState.IDLE) }

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
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 36.dp)
                ) {
                    // SIDEBAR HEADER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF2563EB)
                            ) {
                                Text(
                                    text = "🧭",
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "NAVIATOR",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black,
                                        color = textColorPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFFECFDF5)
                                    ) {
                                        Text(
                                            text = "v2.4",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF047857),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "On-Device AI Navigation Engine",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textColorSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = { scope.launch { drawerState.close() } },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Text("×", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textColorPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = dividerColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // EMERGENCY SOS BUTTON IN SIDEBAR
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDC2626)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (!com.example.gudumap.sos.SosConfig.hasValidContacts()) {
                                sosState = com.example.gudumap.sos.SosState.NO_CONTACTS
                            } else {
                                sosState = com.example.gudumap.sos.SosState.CONFIRMATION
                            }
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "🆘 EMERGENCY SOS",
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = dividerColor)
                    Spacer(modifier = Modifier.height(16.dp))

                    // SECTION 1: NAVIGATION & DEMO CONTROLS
                    Text(
                        text = "NAVIGATION & DEMO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2563EB),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // TOGGLE SWITCH 1: DEMO MODE (OFFLINE GPS)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (navState.blackoutMode) (if (isDarkMode) Color(0xFF3F1212) else Color(0xFFFEF2F2)) else drawerCardBg
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (navState.blackoutMode) Color(0xFFEF4444) else cardBorderColor
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kinematic Demo Controls",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColorPrimary
                                )
                                Text(
                                    text = if (navState.isDemoModeEnabled) {
                                        if (navViewModel.isKinematicDemoActive) "🚨 Demo Movement Active" else "🎮 Controls Panel Visible"
                                    } else {
                                        "📡 Live GNSS Navigation Active"
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

                    Spacer(modifier = Modifier.height(18.dp))

                    // SECTION 2: HUD & DISPLAY THEME
                    Text(
                        text = "HUD & DISPLAY THEME",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2563EB),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // TOGGLE SWITCH: THEME (LIGHT / DARK)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = drawerCardBg),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isDarkMode) "🌙 Dark Theme" else "☀️ Light Theme",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColorPrimary
                                )
                                Text(
                                    text = if (isDarkMode) "Dark Slate Colors Active" else "High-Contrast Light Active",
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

                    Spacer(modifier = Modifier.height(18.dp))

                    // SECTION 3: OFFLINE MAP COVERAGE
                    Text(
                        text = "MAP & COVERAGE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2563EB),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // OFFLINE MAP COVERAGE INFORMATION PANEL
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = drawerCardBg),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🗺️", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Offline Map Coverage",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColorPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Region: Coimbatore Metropolitan Area",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = textColorPrimary
                            )
                            Text(
                                text = "Zoom levels: 11 – 16  •  915 Tiles (~34 MB)",
                                fontSize = 10.5.sp,
                                color = textColorSecondary
                            )
                            Text(
                                text = "Bounds: 10.915° N – 11.125° N, 76.880° E – 77.070° E",
                                fontSize = 10.sp,
                                color = textColorSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (navState.offlineMapStatus == "AVAILABLE") "Status: Available (Bundled MBTiles)" else "Status: Unavailable",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (navState.offlineMapStatus == "AVAILABLE") Color(0xFF047857) else Color(0xFFB91C1C)
                            )
                        }
                    }



                    Spacer(modifier = Modifier.height(18.dp))

                    // SECTION 3: SYSTEM ARCHITECTURE
                    Text(
                        text = "DIAGNOSTICS & SYSTEM",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2563EB),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // SYSTEM ARCHITECTURE PAGE NAVIGATION BUTTON
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            currentScreen = "SYSTEM_ARCHITECTURE"
                            scope.launch { drawerState.close() }
                        }
                    ) {
                        Text(
                            text = "⚙️ SYSTEM ARCHITECTURE",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // APP CREDITS & VERSION FOOTER
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = drawerCardBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SIH 2026 • PS SIH26168",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF2563EB),
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ISRO IO-VNBD Model • On-Device AI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColorPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Dead Reckoning GNSS Blackout System",
                                fontSize = 9.5.sp,
                                color = textColorSecondary
                            )
                        }
                    }

                }
            }
        }
    ) {
        when {
            currentScreen == "SYSTEM_ARCHITECTURE" -> {
                SystemArchitecturePage(
                    navState = navState,
                    isDarkMode = isDarkMode,
                    onBackToMap = { currentScreen = "MAP" },
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            isMapExpanded -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(screenBgColor)
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
                        mapOrientationMode = navState.mapOrientationMode,
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
                        isExpanded = true,
                        isDarkMode = isDarkMode,
                        onToggleExpand = { isMapExpanded = false },
                        onMinimizeMap = { isMapExpanded = false },
                        onToggleMapOrientation = { navViewModel.toggleMapOrientationMode() },
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
            }
            else -> {
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
                                val mapSourceLabel = when {
                                    navState.offlineMapStatus == "AVAILABLE" -> "OFFLINE MAP"
                                    navState.isInternetAvailable -> "ONLINE MAP"
                                    else -> "MAP UNAVAILABLE"
                                }
                                val mapSourceIsAvailable = navState.offlineMapStatus == "AVAILABLE" || navState.isInternetAvailable

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
                                    navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive -> "🎮 DEMO RUNNING" to Color(0xFF047857)
                                    navState.isDemoModeEnabled -> "🎮 DEMO MODE" to Color(0xFF2563EB)
                                    navState.blackoutMode -> "PREDICTED LOCATION" to Color(0xFFB91C1C)
                                    else -> when (navState.gpsState) {
                                        GpsState.FIXED -> "📡 GPS FIXED" to Color(0xFF047857)
                                        GpsState.WEAK -> "📡 GPS WEAK" to Color(0xFFB45309)
                                        GpsState.STALE -> "📡 GPS STALE" to Color(0xFFB45309)
                                        GpsState.ACQUIRING -> "📡 ACQUIRING FIX" to Color(0xFFB45309)
                                        GpsState.SEARCHING -> "📡 GPS SEARCHING" to Color(0xFFB45309)
                                        GpsState.GPS_DISABLED -> "📡 LOCATION OFF" to Color(0xFFB91C1C)
                                        GpsState.PERMISSION_REQUIRED -> "📡 PERMISSION REQUIRED" to Color(0xFFB91C1C)
                                        GpsState.LOST -> "📡 GPS LOST" to Color(0xFFB91C1C)
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
                                    Text(if (navState.isSimulatedBlackout) "🎬" else "🚨", fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (navState.isSimulatedBlackout) "SIMULATED GNSS BLACKOUT" else "GNSS BLACKOUT ACTIVE",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Black,
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
                                        text = if (navState.motionMode == "VEHICLE_MODE") "🚗 VEHICLE" else "🚶 PEDESTRIAN",
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
                                Text("📡", fontSize = 16.sp)
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
                        colors = CardDefaults.cardColors(containerColor = cardBgColor),
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
                                mapOrientationMode = navState.mapOrientationMode,
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
                                onToggleMapOrientation = { navViewModel.toggleMapOrientationMode() },
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
                            Text(
                                text = if (navState.isRecordingGpx) "STOP REC" else "RECORD TRACK",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
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
                            Text("📤 SHARE LOCATION", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
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
                            val isSoftwareDemo = navState.isDemoModeEnabled && navViewModel.isKinematicDemoActive
                            val isPhysicalDemo = navState.isDemoModeEnabled && !navViewModel.isKinematicDemoActive

                            val speedLabel = when {
                                isSoftwareDemo -> "Speed (Simulated)"
                                isPhysicalDemo || navState.blackoutMode -> "Speed (Estimated)"
                                else -> "Speed"
                            }
                            val speedValue = when {
                                isSoftwareDemo -> String.format(Locale.US, "%.1f km/h", navState.selectedDemoSpeed)
                                isPhysicalDemo || navState.blackoutMode -> String.format(Locale.US, "Est. %.1f km/h", navState.speedKmh)
                                navState.isSpeedAvailable -> String.format(Locale.US, "%.1f km/h", navState.speedKmh)
                                else -> "Unavailable"
                            }
                            val displayHeading = navState.headingDeg

                            QuickMetricCard(
                                icon = "⚡",
                                label = speedLabel,
                                value = speedValue,
                                accentColor = Color(0xFF2563EB),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                            QuickMetricCard(
                                icon = "🧭",
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
                                icon = "📏",
                                label = if (navState.blackoutMode) "Estimated Distance" else "Distance",
                                value = formatDistance(navState.distanceMeters),
                                accentColor = Color(0xFF8B5CF6),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                            QuickMetricCard(
                                icon = "🎯",
                                label = if (navState.blackoutMode) "Estimated Uncertainty" else "Position Accuracy",
                                value = String.format(Locale.US, "±%.1f m", navState.uncertaintyRadiusMeters),
                                accentColor = Color(0xFFF59E0B),
                                isDarkMode = isDarkMode,
                                modifier = Modifier.weight(1f)
                            )
                        }
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
                                Text("📍", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                val positionTitle = when {
                                    navState.isDemoModeEnabled -> "🎮 DEMO LOCATION (SIMULATED)"
                                    navState.blackoutMode -> "PREDICTED LOCATION • ESTIMATED (DR)"
                                    navState.hasGpsFix -> "REAL-TIME GPS POSITION (LIVE)"
                                    else -> "NO GPS FIX • SEARCHING"
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

                                val hasValidDisplayPos = (navState.hasGpsFix || navState.blackoutMode || navState.isDemoModeEnabled) &&
                                    navState.latitude.isFinite() &&
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
                    sosState = com.example.gudumap.sos.SosState.PREPARING
                    val sosLoc = com.example.gudumap.sos.SosLocationResolver.resolve(navState)
                    val msg = com.example.gudumap.sos.SosMessageBuilder.buildMessage(sosLoc)
                    val contacts = com.example.gudumap.sos.SosConfig.getValidContacts()
                    val opened = com.example.gudumap.sos.SosLauncher.launchSmsComposer(context, contacts, msg)
                    sosState = if (opened) com.example.gudumap.sos.SosState.COMPOSER_OPENED else com.example.gudumap.sos.SosState.ERROR
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
}

@Composable
private fun SystemArchitecturePage(
    navState: NavigationState,
    isDarkMode: Boolean,
    onBackToMap: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val screenBgColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val cardBgColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val cardBorderColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textColorPrimary = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textColorSecondary = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBgColor)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 36.dp)
    ) {
        // TOP ARCHITECTURE HEADER
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = cardBgColor),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("≡", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = textColorPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SYSTEM ARCHITECTURE",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Black,
                            color = textColorPrimary,
                            letterSpacing = 0.3.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "Live Hardware Sensors & Filter Status",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColorSecondary,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onBackToMap,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("← MAP", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 1: SYSTEM ARCHITECTURE STATUS CARD (Except Motion Engine per user requirement)
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
                    navState.blackoutMode -> "BLACKOUT"
                    navState.gnssNavigationMode == "GNSS_RECOVERY" -> "RECOVERING"
                    else -> navState.gnssStatus
                }
                val navDisplay = if (navState.blackoutMode) "DEAD RECKONING" else "GNSS"

                StatusRow(label = "GNSS Fix", value = gnssDisplay, isGood = gnssDisplay == "AVAILABLE", textColor = textColorPrimary)
                StatusRow(label = "Nav Provider", value = navDisplay, isGood = navDisplay == "GNSS", textColor = textColorPrimary)
                // Note: Motion Engine omitted per user requirement ("except motiion")
                val gateDisplay = "${navState.latestGateAction} (A:${navState.acceptedCount} C:${navState.clampedCount} R:${navState.rejectedCount})"
                StatusRow(label = "ML Innovation Gate", value = gateDisplay, isGood = navState.latestGateAction == "ACCEPTED", textColor = textColorPrimary)
                StatusRow(label = "ML Residual Model", value = navState.mlStatus, isGood = navState.mlStatus == "ACTIVE", textColor = textColorPrimary)
                StatusRow(label = "EKF Filter State", value = navState.ekfStatus, isGood = navState.ekfStatus == "ACTIVE", textColor = textColorPrimary)
                StatusRow(label = "Offline Tile Archive", value = navState.offlineMapStatus, isGood = navState.offlineMapStatus == "AVAILABLE", textColor = textColorPrimary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 2: HARDWARE SENSORS HEALTH CARD
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
                    text = "HARDWARE SENSORS HEALTH",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF2563EB),
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                SensorStatusRow(name = "3-Axis Accelerometer", isActive = navState.accelerometerActive, textColor = textColorPrimary)
                SensorStatusRow(name = "3-Axis Gyroscope", isActive = navState.gyroscopeActive, textColor = textColorPrimary)
                SensorStatusRow(name = "Magnetometer / Rotation", isActive = navState.magnetometerActive, textColor = textColorPrimary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 3: LIVE TELEMETRY DETAILS
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
                Text(text = "• Heading Confidence: ${navState.headingConfidence}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "• Position Uncertainty: ±${String.format(Locale.US, "%.1f m", navState.uncertaintyRadiusMeters)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "• ML Inference Latency: ${if (navState.mlInferenceLatencyMs > 0) "${navState.mlInferenceLatencyMs} ms" else "--"}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textColorPrimary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 4: TECHNICAL EVALUATION / JUDGE PANEL
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
                    Text(
                        text = "🏆 TECHNICAL EVALUATION / JUDGE PANEL",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF2563EB),
                        letterSpacing = 0.5.sp
                    )
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
                StatusRow(label = "Blackout Duration", value = String.format(Locale.US, "%.1f s", navState.blackoutDurationSeconds), isGood = true, textColor = textColorPrimary)
                StatusRow(label = "DR Distance Travelled", value = String.format(Locale.US, "%.1f m", m.drDistance), isGood = true, textColor = textColorPrimary)
                StatusRow(label = "Max Position Error", value = String.format(Locale.US, "%.1f m", m.maximumPositionErrorMeters), isGood = m.maximumPositionErrorMeters < 15.0, textColor = textColorPrimary)
                StatusRow(label = "Final Position Error", value = String.format(Locale.US, "%.1f m", navState.positionErrorMeters), isGood = navState.positionErrorMeters < 10.0, textColor = textColorPrimary)
                StatusRow(label = "ML Gate Decisions", value = "A:${m.numberOfAcceptedMLPredictions} C:${m.numberOfClampedMLPredictions} R:${m.numberOfRejectedMLPredictions}", isGood = true, textColor = textColorPrimary)
                StatusRow(label = "Avg ML Latency", value = if (m.mlInferenceMs > 0) "${m.mlInferenceMs} ms" else "--", isGood = true, textColor = textColorPrimary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            onClick = onBackToMap,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("← RETURN TO MAP SCREEN", fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(30.dp))
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
    icon: String,
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
                    Text(
                        text = icon,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
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
private fun SensorStatusRow(name: String, isActive: Boolean, textColor: Color = Color(0xFF475569)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, fontSize = 13.sp, color = textColor, fontWeight = FontWeight.Medium)
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isActive) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
        ) {
            Text(
                text = if (isActive) "ONLINE" else "OFFLINE",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isActive) Color(0xFF047857) else Color(0xFFDC2626),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
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
                    Text("🎮", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Kinematic Demo Controls",
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
                            navViewModel.isKinematicDemoActive -> "DEMO ACTIVE"
                            navState.demoTrailPoints.isNotEmpty() -> "DEMO STOPPED"
                            else -> "DEMO READY"
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
                            navViewModel.isKinematicDemoActive -> "🛑 STOP DEMO"
                            navState.demoTrailPoints.isNotEmpty() -> "▶ RUN AGAIN"
                            else -> "▶ START DEMO"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
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
                    Text("📍 RESET ANCHOR", fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
