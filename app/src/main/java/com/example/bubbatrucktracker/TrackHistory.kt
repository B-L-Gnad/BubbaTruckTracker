package com.bubbaman.bubbatrucktracker

class TrackHistory(points: List<BeaconLocation> = emptyList()) {
    private val history = points.sortedBy { it.lastTime }.takeLast(100).toMutableList()
    val locations: List<BeaconLocation> get() = history.toList()
    val latest: BeaconLocation? get() = history.lastOrNull()

    fun add(location: BeaconLocation): Boolean {
        require(location.lat.isFinite() && location.lat in -90.0..90.0)
        require(location.lng.isFinite() && location.lng in -180.0..180.0)
        val previous = latest
        if (previous != null && location.lastTime < previous.lastTime) return false
        if (previous != null && previous.lat == location.lat && previous.lng == location.lng) {
            history[history.lastIndex] = location
        } else {
            history.add(location)
            if (history.size > 100) history.removeAt(0)
        }
        return true
    }

    fun track(): BeaconTrack? = latest?.let { location ->
        BeaconTrack(location, history.map { TracePoint(it.lat, it.lng, it.lastTime, it.speed, it.altitude) })
    }
}
