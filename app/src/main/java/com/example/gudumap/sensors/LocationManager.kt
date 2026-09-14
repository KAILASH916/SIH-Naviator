package com.example.gudumap.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Authoritative central location provider client managing FusedLocationProviderClient
 * updates, initial fix acquisition via getCurrentLocation/lastLocation, and fallback to
 * system LocationManager when Google Play Services is unavailable.
 */
class LocationManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "Gudumap:LocationMgr"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MIN_UPDATE_INTERVAL_MS = 500L
    }

    private val systemLocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager

    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var legacyListener: LocationListener? = null
    private var isUsingFusedProvider: Boolean = false

    var isGnssAvailable: Boolean = false
        private set

    init {
        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(context)
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocationProviderClient initialization failed: ${e.message}. Using legacy LocationManager.")
        }
    }

    /**
     * Checks if location updates are currently registered and active.
     */
    fun isListening(): Boolean = fusedCallback != null || legacyListener != null

    /**
     * Returns true if either ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION is granted.
     */
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Returns true if FINE location permission is explicitly granted.
     */
    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if system location services (GPS or Network) are turned ON in Android settings.
     */
    fun isLocationEnabled(): Boolean {
        val sysMgr = systemLocationManager ?: return false
        return try {
            sysMgr.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER) ||
                sysMgr.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Starts continuous high-accuracy location updates. Also requests initial cached/current location
     * to transition UI from SEARCHING to FIXED immediately upon availability.
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        onLocationChanged: (Location) -> Unit,
        onStatusChanged: ((Boolean) -> Unit)? = null
    ) {
        if (isListening()) {
            Log.i(TAG, "startLocationUpdates: Already listening -- re-registering cleanly")
            stopLocationUpdates()
        }

        val finePerm = hasFineLocationPermission()
        val coarsePerm = hasLocationPermission()
        val locEnabled = isLocationEnabled()

        Log.d(TAG, "LOCATION PROVIDER AUDIT: finePerm=$finePerm, coarsePerm=$coarsePerm, locationServicesEnabled=$locEnabled, requestActive=true")

        if (!coarsePerm) {
            Log.w(TAG, "startLocationUpdates: Location permission NOT granted. Cannot register location updates.")
            isGnssAvailable = false
            onStatusChanged?.invoke(false)
            return
        }

        if (!locEnabled) {
            Log.w(TAG, "startLocationUpdates: System location services OFF in settings. Waiting for user to enable location.")
            isGnssAvailable = false
            onStatusChanged?.invoke(false)
            return
        }

        val priority = if (finePerm) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        // Try FusedLocationProviderClient first
        if (fusedClient != null) {
            try {
                val request = LocationRequest.Builder(priority, UPDATE_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                    .setMinUpdateDistanceMeters(0f) // Keep stationary updates active
                    .setGranularity(com.google.android.gms.location.Granularity.GRANULARITY_PERMISSION_LEVEL)
                    .setWaitForAccurateLocation(false)
                    .build()

                fusedCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val locations = result.locations
                        if (locations.isEmpty()) return
                        isGnssAvailable = true
                        onStatusChanged?.invoke(true)
                        for (location in locations) {
                            Log.d(TAG, "Fused Fix: provider=${location.provider} lat=${location.latitude} lon=${location.longitude} acc=${location.accuracy}m speed=${location.speed}m/s time=${location.time} elapsedNanos=${location.elapsedRealtimeNanos}")
                            onLocationChanged(location)
                        }
                    }

                    override fun onLocationAvailability(availability: LocationAvailability) {
                        val isAvailable = availability.isLocationAvailable
                        Log.i(TAG, "Fused LocationAvailability changed: isAvailable=$isAvailable")
                        isGnssAvailable = isAvailable
                        onStatusChanged?.invoke(isAvailable)
                    }
                }

                fusedClient?.requestLocationUpdates(request, fusedCallback!!, Looper.getMainLooper())
                isUsingFusedProvider = true
                Log.i(TAG, "Successfully registered FusedLocationProviderClient updates with priority=$priority")

                // Step 4 & 5: Request immediate lastLocation and fresh getCurrentLocation to prevent waiting
                fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.i(TAG, "Immediate lastLocation retrieved: lat=${loc.latitude} lon=${loc.longitude} acc=${loc.accuracy}m")
                        onLocationChanged(loc)
                    }
                }

                val cancelTokenSource = CancellationTokenSource()
                fusedClient?.getCurrentLocation(priority, cancelTokenSource.token)?.addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.i(TAG, "Immediate getCurrentLocation retrieved: lat=${loc.latitude} lon=${loc.longitude} acc=${loc.accuracy}m")
                        onLocationChanged(loc)
                    }
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start FusedLocationProviderClient: ${e.message}. Falling back to legacy LocationManager.")
                fusedCallback = null
                isUsingFusedProvider = false
            }
        }

        // Fallback: Legacy Android LocationManager
        val sysMgr = systemLocationManager ?: return
        try {
            legacyListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "Legacy Fix: provider=${location.provider} lat=${location.latitude} lon=${location.longitude} acc=${location.accuracy}m")
                    isGnssAvailable = true
                    onStatusChanged?.invoke(true)
                    onLocationChanged(location)
                }

                override fun onProviderEnabled(provider: String) {
                    Log.i(TAG, "Legacy Provider Enabled: $provider")
                    isGnssAvailable = true
                    onStatusChanged?.invoke(true)
                }

                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "Legacy Provider Disabled: $provider")
                    if (provider == AndroidLocationManager.GPS_PROVIDER) {
                        isGnssAvailable = false
                        onStatusChanged?.invoke(false)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            val gpsEnabled = sysMgr.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER)
            val networkEnabled = sysMgr.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)

            if (gpsEnabled) {
                sysMgr.requestLocationUpdates(AndroidLocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, legacyListener!!)
                isGnssAvailable = true
            }
            if (networkEnabled) {
                sysMgr.requestLocationUpdates(AndroidLocationManager.NETWORK_PROVIDER, UPDATE_INTERVAL_MS, 0f, legacyListener!!)
                isGnssAvailable = true
            }

            onStatusChanged?.invoke(isGnssAvailable)
            Log.i(TAG, "Registered legacy LocationManager (GPS=$gpsEnabled, Network=$networkEnabled)")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering legacy LocationManager: ${e.message}")
            isGnssAvailable = false
            onStatusChanged?.invoke(false)
        }
    }

    /**
     * Unregisters location updates and stops location listeners cleanly.
     */
    fun stopLocationUpdates() {
        fusedCallback?.let {
            try {
                fusedClient?.removeLocationUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing fused updates: ${e.message}")
            }
        }
        fusedCallback = null

        legacyListener?.let {
            try {
                systemLocationManager?.removeUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing legacy updates: ${e.message}")
            }
        }
        legacyListener = null

        isGnssAvailable = false
        isUsingFusedProvider = false
        Log.i(TAG, "Location updates stopped cleanly")
    }
}