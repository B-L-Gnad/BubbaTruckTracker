package com.bubbaman.bubbatrucktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

class TrackHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("TrackHistory", Context.MODE_PRIVATE)

    fun load(callsign: String): TrackHistory {
        val json = preferences.getString(callsign, null) ?: return TrackHistory()
        try {
            val array = JSONArray(json)
            val history = TrackHistory()
            for (i in 0 until array.length()) {
                val point = array.getJSONObject(i)
                history.add(BeaconLocation(
                    callsign, point.getDouble("lat"), point.getDouble("lng"),
                    point.getLong("time"), point.optString("speed"),
                    point.optString("altitude"), point.optString("comment")
                ))
            }
            return history
        } catch (e: JSONException) {
            Timber.tag("AprsTracking").e(e, "Saved track could not be read")
            throw IllegalStateException("Saved track could not be read", e)
        } catch (e: IllegalArgumentException) {
            Timber.tag("AprsTracking").e(e, "Saved track contains invalid coordinates")
            throw IllegalStateException("Saved track contains invalid coordinates", e)
        }
    }

    fun save(callsign: String, history: TrackHistory) {
        val array = JSONArray()
        history.locations.forEach { point ->
            array.put(JSONObject()
                .put("lat", point.lat).put("lng", point.lng).put("time", point.lastTime)
                .put("speed", point.speed).put("altitude", point.altitude).put("comment", point.comment))
        }
        preferences.edit().putString(callsign, array.toString()).apply()
    }
}
