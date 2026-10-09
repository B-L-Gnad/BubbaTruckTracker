package com.bubbaman.bubbatrucktracker

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TrackHistoryStoreTest {
    @Test
    fun persists100PointsAcrossStoreInstancesAndSeparatesCallsigns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val callsign = "HISTORY-UT"
        val other = "OTHER-UT"
        val preferences = context.getSharedPreferences("TrackHistory", 0)
        preferences.edit().remove(callsign).remove(other).commit()
        try {
            val history = TrackHistory()
            for (time in 1L..120L) {
                history.add(BeaconLocation(callsign, time / 1000.0, -95.0, time, "10", "200", "test"))
            }
            TrackHistoryStore(context).save(callsign, history)
            val restored = TrackHistoryStore(context).load(callsign)
            assertEquals(history.locations, restored.locations)
            assertEquals(100, restored.locations.size)
            assertEquals(21L, restored.locations.first().lastTime)
            assertTrue(TrackHistoryStore(context).load(other).locations.isEmpty())
        } finally {
            preferences.edit().remove(callsign).remove(other).commit()
        }
    }
}
