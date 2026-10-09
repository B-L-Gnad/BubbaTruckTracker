package com.bubbaman.bubbatrucktracker

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var callsignInput: TextInputEditText
    private lateinit var trackButton: Button
    private lateinit var statusText: TextView
    private var currentMarker: Marker? = null
    private var tracePolyline: Polyline? = null
    private var startMarker: Marker? = null
    private var showTrace = true
    private var stream: AprsIsClient? = null
    private var sessionId = 0
    private var resumed = false
    private var trackedCallsign = "KB5TSI-5"
    private var latestLocation: BeaconLocation? = null
    private var history = TrackHistory()
    private lateinit var historyStore: TrackHistoryStore
    private val apiClient = OkHttpClient()
    private var initialRequest: Call? = null
    private var connectionStatus = ""
    private var lookupStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = "BubbaTruckTracker/1.0 (+https://github.com/example/bubbatrucktracker)"
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        mapView = findViewById(R.id.mapView)
        callsignInput = findViewById(R.id.callsignInput)
        trackButton = findViewById(R.id.trackButton)
        statusText = findViewById(R.id.statusText)

        // Restore trace visibility preference
        val prefs = getSharedPreferences("BubbaTruckTracker", MODE_PRIVATE)
        showTrace = prefs.getBoolean("showTrace", true)

        val cartoTiles = XYTileSource(
            "CartoDB_Positron", 0, 19, 256, ".png?key=${BuildConfig.CARTO_API_KEY}",
            arrayOf("https://basemaps.cartocdn.com/rastertiles/voyager/")
        )
        mapView.setTileSource(cartoTiles)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(4.0)
        mapView.controller.setCenter(GeoPoint(39.5, -98.35))

        trackButton.setOnClickListener { trackCurrentCallsign() }

        callsignInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                trackCurrentCallsign()
                true
            } else false
        }

        trackedCallsign = prefs.getString("trackedCallsign", "KB5TSI-5") ?: "KB5TSI-5"
        callsignInput.setText(trackedCallsign)
        historyStore = TrackHistoryStore(this)
        restoreHistory()
    }

    private fun trackCurrentCallsign() {
        val callsign = AprsTrackFormatter.normalizeCallsign(callsignInput.text.toString())
        if (!Regex("[A-Z0-9]{3,6}(?:-[A-Z0-9]{1,2})?").matches(callsign)) {
            Timber.tag("AprsTracking").w("Tracking skipped: invalid callsign")
            statusText.text = "Enter a valid station callsign, such as KB5TSI-5"
            return
        }
        if (callsign != trackedCallsign) {
            trackedCallsign = callsign
            latestLocation = null
            clearTrackOverlays()
            restoreHistory()
            mapView.invalidate()
        }
        getSharedPreferences("BubbaTruckTracker", MODE_PRIVATE)
            .edit().putString("trackedCallsign", callsign).apply()
        if (resumed) startStream()
    }

    private fun startStream() {
        stopStream()
        val id = sessionId
        connectionStatus = "Connecting to APRS-IS..."
        lookupStatus = "Looking up last-known position..."
        updateStatus()
        fetchInitialPosition(id)
        stream = AprsIsClient(
            trackedCallsign,
            onStatus = { message ->
                runOnUiThread {
                    if (resumed && id == sessionId) {
                        connectionStatus = message
                        updateStatus()
                    }
                }
            },
            onLocation = { location ->
                runOnUiThread {
                    if (resumed && id == sessionId) {
                        connectionStatus = "Receiving live APRS-IS positions"
                        acceptLocation(location)
                    }
                }
            }
        ).also { it.start() }
    }

    private fun stopStream() {
        sessionId++
        initialRequest?.cancel()
        initialRequest = null
        stream?.close()
        stream = null
    }

    private fun restoreHistory() {
        try {
            history = historyStore.load(trackedCallsign)
        } catch (e: IllegalStateException) {
            history = TrackHistory()
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
        latestLocation = history.latest
        history.track()?.let { displayTrack(it) }
    }

    private fun acceptLocation(location: BeaconLocation) {
        if (!history.add(location)) {
            Timber.tag("AprsTracking").d("Ignoring position older than the latest saved point")
            updateStatus()
            return
        }
        historyStore.save(trackedCallsign, history)
        latestLocation = history.latest
        Timber.tag("AprsTracking").d("Track updated: %d saved points", history.locations.size)
        history.track()?.let { displayTrack(it) }
    }

    private fun updateStatus() {
        statusText.text = listOf(connectionStatus, lookupStatus).filter { it.isNotBlank() }.joinToString("\n") +
            "\n${history.locations.size}/100 saved points" +
            (latestLocation?.let { "\n" + AprsTrackFormatter.buildStatusText(it) } ?: "")
    }

    private fun fetchInitialPosition(id: Int) {
        fun report(message: String, location: BeaconLocation? = null) {
            runOnUiThread {
                if (resumed && id == sessionId) {
                    lookupStatus = message
                    if (location != null) acceptLocation(location) else updateStatus()
                }
            }
        }
        if (BuildConfig.APRS_API_KEY.isBlank()) {
            Timber.tag("AprsTracking").e("aprs.fi initial lookup unavailable: API key missing")
            report("aprs.fi lookup unavailable: API key missing")
            return
        }
        Timber.tag("AprsTracking").d("Fetching initial aprs.fi position (what=loc)")
        val request = Request.Builder()
            .url(AprsTrackFormatter.buildLocationRequestUrl(trackedCallsign, BuildConfig.APRS_API_KEY))
            .header("User-Agent", "BubbaTruckTracker/${BuildConfig.VERSION_NAME} (+https://github.com/example/bubbatrucktracker)")
            .build()
        initialRequest = apiClient.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    Timber.tag("AprsTracking").e("aprs.fi lookup failed (%s)", e.javaClass.simpleName)
                    report("aprs.fi lookup failed; live tracking continues")
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                Timber.tag("AprsTracking").e("aprs.fi HTTP error: %d", it.code)
                                report("aprs.fi HTTP error: ${it.code}; live tracking continues")
                                return
                            }
                            val body = it.body?.string()
                            if (body.isNullOrBlank()) {
                                Timber.tag("AprsTracking").e("aprs.fi returned an empty response")
                                report("aprs.fi returned an empty response")
                                return
                            }
                            when (val result = AprsTrackFormatter.parseLocationResponse(body)) {
                                is AprsLocationResult.Success -> {
                                    val location = result.track.location
                                    if (!location.lat.isFinite() || location.lat !in -90.0..90.0 ||
                                        !location.lng.isFinite() || location.lng !in -180.0..180.0) {
                                        Timber.tag("AprsTracking").e("aprs.fi returned invalid coordinates")
                                        report("aprs.fi returned invalid coordinates")
                                    } else report("", location)
                                }
                                is AprsLocationResult.ApiError -> {
                                    Timber.tag("AprsTracking").e("aprs.fi API error: %s", result.description)
                                    report("aprs.fi API error: ${result.description}")
                                }
                                is AprsLocationResult.ParseError -> {
                                    Timber.tag("AprsTracking").e("aprs.fi parse error: %s", result.description)
                                    report("aprs.fi response could not be parsed")
                                }
                                AprsLocationResult.NotFound -> {
                                    Timber.tag("AprsTracking").w("aprs.fi has no last-known position")
                                    report("No aprs.fi position; waiting for a live beacon")
                                }
                            }
                        }
                    } catch (e: IOException) {
                        if (!call.isCanceled()) {
                            Timber.tag("AprsTracking").e("aprs.fi response read failed (%s)", e.javaClass.simpleName)
                            report("aprs.fi response could not be read; live tracking continues")
                        }
                    }
                }
            })
        }
    }

    private fun clearTrackOverlays() {
        currentMarker?.let { mapView.overlays.remove(it) }
        tracePolyline?.let { mapView.overlays.remove(it) }
        startMarker?.let { mapView.overlays.remove(it) }
        currentMarker = null
        tracePolyline = null
        startMarker = null
    }

    private fun displayTrack(track: BeaconTrack) {
        val location = track.location
        val position = GeoPoint(location.lat, location.lng)
        val firstPosition = currentMarker == null
        clearTrackOverlays()

        if (showTrace && track.trace.size > 1) {
            val polyline = Polyline(mapView)
            polyline.setPoints(track.trace.map { GeoPoint(it.lat, it.lng) })
            polyline.outlinePaint.color = 0xFF0066CC.toInt()
            polyline.outlinePaint.strokeWidth = 4f
            mapView.overlays.add(polyline)
            tracePolyline = polyline

            val startPoint = track.trace.first()
            val marker = Marker(mapView)
            marker.position = GeoPoint(startPoint.lat, startPoint.lng)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "First observed point"
            mapView.overlays.add(marker)
            startMarker = marker
        }

        val marker = Marker(mapView)
        marker.position = position
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = location.name
        marker.snippet = AprsTrackFormatter.buildMarkerSnippet(location)
        mapView.overlays.add(marker)
        currentMarker = marker
        mapView.controller.animateTo(position)
        if (firstPosition) mapView.controller.setZoom(13.0)
        mapView.invalidate()

        updateStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val traceItem = menu.findItem(R.id.action_toggle_trace)
        traceItem.title = if (showTrace) "Hide Trace" else "Show Trace"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_trace -> {
                showTrace = !showTrace
                item.title = if (showTrace) "Hide Trace" else "Show Trace"
                
                // Save preference
                val prefs = getSharedPreferences("BubbaTruckTracker", MODE_PRIVATE)
                prefs.edit().putBoolean("showTrace", showTrace).apply()
                
                // Refresh display
                history.track()?.let { displayTrack(it) }
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Bubba Truck Tracker")
            .setMessage(
                "Author: The Bubba Man\n\n" +
                "APRS Data\n" +
                "Initial last-known position provided by aprs.fi.\n" +
                "https://aprs.fi\n\n" +
                "Live positions provided by APRS-IS (receive-only).\n" +
                "https://www.aprs-is.net\n" +
                "The latest 100 distinct positions per callsign are saved locally.\n" +
                "Live positions are received while this map is open; no historical lookup.\n\n" +
                "Map Tiles\n" +
                "© CartoDB  ·  © OpenStreetMap contributors\n" +
                "https://carto.com/attributions\n\n" +
                "Map Library\n" +
                "OSMDroid — open-source Android map SDK\n" +
                "https://github.com/osmdroid/osmdroid\n\n" +
                "Map data © OpenStreetMap contributors, ODbL."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        resumed = true
        startStream()
    }

    override fun onPause() {
        resumed = false
        stopStream()
        mapView.onPause()
        super.onPause()
    }
}