package com.example.gudumap.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileOutputStream

enum class OfflineMapStatus {
    AVAILABLE,
    LOADING,
    ERROR,
    NOT_AVAILABLE
}

data class OfflineMapMetadata(
    val regionId: String = "coimbatore",
    val regionName: String = "Coimbatore Metropolitan Area",
    val bounds: BoundingBox = OfflineMapManager.COIMBATORE_BOUNDS,
    val minZoom: Int = OfflineMapManager.MIN_ZOOM,
    val maxZoom: Int = OfflineMapManager.MAX_ZOOM,
    val tileCount: Int = 915,
    val storageSizeBytes: Long = 0L,
    val format: String = "png",
    val isAvailable: Boolean = true
)

class OfflineMapManager(private val context: Context) {

    companion object {
        private const val TAG = "Gudumap:OfflineMap"
        const val COIMBATORE_DEFAULT_LAT = 11.0168
        const val COIMBATORE_DEFAULT_LON = 76.9558

        // §22: MUST match coimbatore.mbtiles' own `metadata` table (minzoom/maxzoom rows) exactly.
        // Verified directly against the bundled file via sqlite3 -- real data covers zoom 11-16
        // only (915 tiles: 11=4, 12=12, 13=30, 14=110, 15=399, 16=360; metadata row maxzoom=16).
        // This constant was previously 17 -- one level past the last real tile -- which let the
        // map UI zoom into a range with zero data (see MapView.kt's zoom bounds, now derived from
        // this same constant instead of an independently hardcoded 18.0).
        const val MIN_ZOOM = 11
        const val MAX_ZOOM = 16

        // Bounding box for Coimbatore metropolitan area (23.3 km x 20.7 km, ~482 sq km)
        val COIMBATORE_BOUNDS = BoundingBox(11.125, 77.070, 10.915, 76.880)
        val COIMBATORE_CENTER = GeoPoint(COIMBATORE_DEFAULT_LAT, COIMBATORE_DEFAULT_LON)
    }

    var status: OfflineMapStatus = OfflineMapStatus.LOADING
        private set

    private var localMapFile: File? = null
    private var tileCount: Int = 0

    init {
        configureOsmdroid()
        initializeOfflineMap()
    }

    private fun configureOsmdroid() {
        try {
            val osmdroidDir = File(context.filesDir, "osmdroid")
            if (!osmdroidDir.exists()) osmdroidDir.mkdirs()
            val tileCacheDir = File(context.filesDir, "osmdroid/tiles")
            if (!tileCacheDir.exists()) tileCacheDir.mkdirs()

            val config = Configuration.getInstance()
            config.osmdroidBasePath = osmdroidDir
            config.osmdroidTileCache = tileCacheDir
            config.tileFileSystemCacheMaxBytes = 500L * 1024L * 1024L // 500 MB disk cache
            config.tileFileSystemCacheTrimBytes = 400L * 1024L * 1024L
            config.cacheMapTileCount = 120 // 120 tiles in RAM cache for instant zero-latency rendering
            config.tileDownloadThreads = 8 // 8 parallel network download threads
            config.tileDownloadMaxQueueSize = 120 // Max 120 queued requests
            config.expirationExtendedDuration = 30L * 24L * 3600L * 1000L // 30 days extended cache
            config.expirationOverrideDuration = 30L * 24L * 3600L * 1000L

            config.load(
                context,
                context.getSharedPreferences("naviator_osmdroid_prefs", Context.MODE_PRIVATE)
            )
            config.userAgentValue = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 Gudumap/1.0"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure osmdroid: ${e.message}")
        }
    }

    /**
     * Initializes the offline map dataset:
     * 1. Locates or creates the internal storage directory: context.filesDir/maps/coimbatore/
     * 2. Copies coimbatore.mbtiles from APK assets on first run or when updated
     * 3. Verifies SQLite integrity and tile counts
     * 4. Updates status to AVAILABLE, ERROR, or NOT_AVAILABLE
     */
    @Synchronized
    fun initializeOfflineMap() {
        status = OfflineMapStatus.LOADING
        try {
            val mapsDir = File(context.filesDir, "maps/coimbatore")
            if (!mapsDir.exists()) mapsDir.mkdirs()

            val targetFile = File(mapsDir, "coimbatore.mbtiles")

            // If targetFile already exists and is a valid SQLite DB, use it directly!
            if (targetFile.exists() && targetFile.length() > 100_000L && verifyDatabase(targetFile)) {
                localMapFile = targetFile
                status = OfflineMapStatus.AVAILABLE
                Log.i(TAG, "Existing offline Coimbatore map is valid (${targetFile.length() / (1024 * 1024)} MB, tile count=$tileCount). Skipping copy.")
                return
            }

            // Determine if asset exists
            val assetPath = try {
                context.assets.open("maps/coimbatore/coimbatore.mbtiles").close()
                "maps/coimbatore/coimbatore.mbtiles"
            } catch (e: Exception) {
                try {
                    context.assets.open("maps/coimbatore.mbtiles").close()
                    "maps/coimbatore.mbtiles"
                } catch (e2: Exception) {
                    null
                }
            }

            if (assetPath == null && (!targetFile.exists() || targetFile.length() == 0L)) {
                Log.e(TAG, "Offline map asset not found in assets/maps/")
                status = OfflineMapStatus.NOT_AVAILABLE
                return
            }

            if (assetPath != null) {
                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                Log.i(TAG, "Copying offline Coimbatore map package from $assetPath to ${targetFile.absolutePath}...")
                try {
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        throw IllegalStateException("Could not move extracted map into place")
                    }
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
                Log.i(TAG, "Extracted offline map successfully (${targetFile.length() / (1024 * 1024)} MB).")
            }

            localMapFile = targetFile

            // Verify SQLite integrity
            if (targetFile.exists() && targetFile.length() > 0L && verifyDatabase(targetFile)) {
                status = OfflineMapStatus.AVAILABLE
                Log.i(TAG, "Offline Coimbatore map is AVAILABLE. Tile count = $tileCount, size = ${targetFile.length() / (1024 * 1024)} MB.")
            } else {
                status = OfflineMapStatus.ERROR
                Log.e(TAG, "Offline map file exists but verification failed.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing offline map: ${e.message}", e)
            status = OfflineMapStatus.ERROR
        }
    }

    private fun verifyDatabase(file: File): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.execSQL("PRAGMA cache_size=-8000;") // 8 MB SQLite RAM page cache
            } catch (e: Exception) {
                Log.w(TAG, "SQLite optimization notice: ${e.message}")
            }
            val cursor = db.rawQuery("SELECT COUNT(*) FROM tiles", null)
            var count = 0
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0)
            }
            cursor.close()
            tileCount = count
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "SQLite validation error: ${e.message}", e)
            false
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    fun getLocalMapFile(): File? = localMapFile

    fun getTileCount(): Int = tileCount

    fun isOfflineMapAvailable(): Boolean = status == OfflineMapStatus.AVAILABLE

    fun getMapMetadata(): OfflineMapMetadata {
        val file = localMapFile
        val size = if (file != null && file.exists()) file.length() else 0L
        return OfflineMapMetadata(
            regionId = "coimbatore",
            regionName = "Coimbatore Metropolitan Area",
            bounds = COIMBATORE_BOUNDS,
            minZoom = MIN_ZOOM,
            maxZoom = MAX_ZOOM,
            tileCount = tileCount,
            storageSizeBytes = size,
            format = "png",
            isAvailable = status == OfflineMapStatus.AVAILABLE
        )
    }

    fun isInsideOfflineBounds(latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite()) return false
        return latitude >= COIMBATORE_BOUNDS.latSouth && latitude <= COIMBATORE_BOUNDS.latNorth &&
            longitude >= COIMBATORE_BOUNDS.lonWest && longitude <= COIMBATORE_BOUNDS.lonEast
    }

    /** Creates a strictly offline tile provider backed by the bundled MBTiles archive. */
    fun createOfflineTileProvider(): MapTileProviderArray? {
        val file = localMapFile ?: run {
            Log.e(TAG, "createOfflineTileProvider: no local map file recorded")
            return null
        }
        if (!file.exists()) {
            Log.e(TAG, "createOfflineTileProvider: local map file does not exist at ${file.absolutePath}")
            return null
        }
        Log.i(TAG, "createOfflineTileProvider: mbtiles file found at ${file.absolutePath} (${file.length() / 1024}KB)")

        return try {
            val archive: IArchiveFile? = try {
                MBTilesFileArchive.getDatabaseFileArchive(file)
            } catch (e: Exception) {
                Log.w(TAG, "MBTilesFileArchive.getDatabaseFileArchive failed (${e.message}), trying ArchiveFileFactory fallback")
                ArchiveFileFactory.getArchiveFile(file)
            }

            if (archive == null) {
                Log.e(TAG, "createOfflineTileProvider: could not open ${file.absolutePath} as an MBTiles archive")
                return null
            }
            Log.i(TAG, "createOfflineTileProvider: MBTiles archive opened successfully ($archive)")

            archive.setIgnoreTileSource(true)

            // The URL is metadata used by osmdroid; this provider contains no downloader.
            val fastTileSource: ITileSource = XYTileSource(
                "FastMapnik",
                MIN_ZOOM,
                MAX_ZOOM,
                256,
                ".png",
                arrayOf(
                    "https://a.tile.openstreetmap.org/",
                    "https://b.tile.openstreetmap.org/",
                    "https://c.tile.openstreetmap.org/"
                )
            )

            val receiver = SimpleRegisterReceiver(context)
            val archiveProvider = org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider(
                receiver,
                fastTileSource,
                arrayOf(archive),
                true
            )

            Log.i(TAG, "createOfflineTileProvider: ready -- offline MBTiles provider, tileCount=$tileCount")

            MapTileProviderArray(
                fastTileSource,
                receiver,
                arrayOf<MapTileModuleProviderBase>(archiveProvider)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating offline tile provider: ${e.message}", e)
            null
        }
    }

    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.w(TAG, "isOnline check exception: ${e.message}")
            false
        }
    }

    fun getMapStatus(blackoutMode: Boolean = true): String {
        return if (blackoutMode || !isOnline()) "OFFLINE" else "ONLINE"
    }

    fun getOfflineMapStatusString(): String {
        return when (status) {
            OfflineMapStatus.AVAILABLE -> "AVAILABLE"
            OfflineMapStatus.LOADING -> "LOADING"
            OfflineMapStatus.ERROR -> "ERROR"
            OfflineMapStatus.NOT_AVAILABLE -> "NOT_AVAILABLE"
        }
    }
}
