package com.bubbaman.bubbatrucktracker

data class BeaconLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
    val lastTime: Long,
    val speed: String,
    val altitude: String,
    val comment: String
)

data class TracePoint(
    val lat: Double,
    val lng: Double,
    val time: Long,
    val speed: String = "",
    val altitude: String = ""
)

data class BeaconTrack(
    val location: BeaconLocation,
    val trace: List<TracePoint>
)

sealed class AprsLocationResult {
    data class Success(val track: BeaconTrack) : AprsLocationResult()
    data class ApiError(val description: String) : AprsLocationResult()
    data object NotFound : AprsLocationResult()
    data class ParseError(val description: String) : AprsLocationResult()
}
