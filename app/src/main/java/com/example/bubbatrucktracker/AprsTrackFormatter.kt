package com.bubbaman.bubbatrucktracker

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URLEncoder

object AprsTrackFormatter {
    fun normalizeCallsign(input: String): String = input.trim().uppercase(Locale.US)

    fun buildLocationRequestUrl(callsign: String, apiKey: String): String {
        val encodedCallsign = URLEncoder.encode(callsign, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        return "https://api.aprs.fi/api/get?name=$encodedCallsign&what=loc&apikey=$encodedKey&format=json"
    }

    fun buildTrackRequestUrl(callsign: String, apiKey: String): String {
        val encodedCallsign = URLEncoder.encode(callsign, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        return "https://api.aprs.fi/api/get?name=$encodedCallsign&what=track&apikey=$encodedKey&format=json&limit=100"
    }

    fun parseLocationResponse(json: String): AprsLocationResult {
        return try {
            val root = JSONObject(json)

            when {
                root.optString("result") != "ok" -> AprsLocationResult.ApiError(
                    root.optString("description", "Unknown error")
                )

                root.optInt("found", 0) == 0 -> AprsLocationResult.NotFound

                else -> {
                    val entry = root.getJSONArray("entries").getJSONObject(0)
                    val location = BeaconLocation(
                        name = entry.getString("name"),
                        lat = entry.getDouble("lat"),
                        lng = entry.getDouble("lng"),
                        lastTime = entry.optLong("lasttime", 0L),
                        speed = entry.optString("speed", ""),
                        altitude = entry.optString("altitude", ""),
                        comment = entry.optString("comment", "").trim()
                    )
                    
                    // Try to extract track if available
                    val trace = mutableListOf<TracePoint>()
                    val trackArray = root.optJSONArray("track")
                    if (trackArray != null) {
                        for (i in 0 until trackArray.length()) {
                            val point = trackArray.getJSONObject(i)
                            trace.add(
                                TracePoint(
                                    lat = point.getDouble("lat"),
                                    lng = point.getDouble("lng"),
                                    time = point.optLong("time", 0L),
                                    speed = point.optString("speed", ""),
                                    altitude = point.optString("altitude", "")
                                )
                            )
                        }
                    }
                    
                    AprsLocationResult.Success(
                        BeaconTrack(
                            location = location,
                            trace = trace.sortedBy { it.time }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AprsLocationResult.ParseError(e.message ?: "Unknown parse error")
        }
    }

    fun buildMarkerSnippet(location: BeaconLocation): String = buildString {
        if (location.speed.isNotEmpty()) append("Speed: ${location.speed} km/h  ")
        if (location.altitude.isNotEmpty()) append("Alt: ${location.altitude}m")
        if (location.comment.isNotEmpty()) append("\n${location.comment}")
    }.trim()

    fun buildStatusText(location: BeaconLocation, locale: Locale = Locale.getDefault()): String {
        val timeText = if (location.lastTime > 0) {
            SimpleDateFormat("MMM dd, HH:mm:ss z", locale).format(Date(location.lastTime * 1000))
        } else {
            "unknown time"
        }

        return buildString {
            append("${location.name} — last seen $timeText")
            append("\nLat: ${"%.5f".format(location.lat)}  Lng: ${"%.5f".format(location.lng)}")
            if (location.speed.isNotEmpty()) append("  Speed: ${location.speed} km/h")
        }
    }
}
