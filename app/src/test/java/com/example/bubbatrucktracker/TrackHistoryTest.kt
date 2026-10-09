package com.bubbaman.bubbatrucktracker

import org.junit.Assert.*
import org.junit.Test

class TrackHistoryTest {
    private fun point(time: Long, lat: Double = time / 1000.0) =
        BeaconLocation("KB5TSI-5", lat, -95.0, time, "10", "200", "test")

    @Test
    fun retainsExactlyTheLatest100PointsInOrder() {
        val history = TrackHistory()
        for (time in 1L..150L) history.add(point(time))
        assertEquals(100, history.locations.size)
        assertEquals(51L, history.locations.first().lastTime)
        assertEquals(150L, history.latest?.lastTime)
        assertEquals(100, history.track()?.trace?.size)
    }

    @Test
    fun initialApiResponseCannotOverwriteNewerLivePoint() {
        val history = TrackHistory()
        history.add(point(200))
        assertFalse(history.add(point(100)))
        assertEquals(point(200), history.latest)
        assertEquals(1, history.locations.size)
    }

    @Test
    fun stationaryUpdatesRefreshMetadataWithoutDuplicatingTrackPoints() {
        val history = TrackHistory()
        history.add(point(1, 36.0))
        history.add(point(2, 36.0).copy(comment = "updated"))
        assertEquals(1, history.locations.size)
        assertEquals(2L, history.latest?.lastTime)
        assertEquals("updated", history.latest?.comment)
        history.add(point(3, 37.0))
        history.add(point(4, 36.0))
        assertEquals(3, history.locations.size)
    }

    @Test
    fun restoredHistoryContinuesWithTheSameLimit() {
        val original = TrackHistory()
        for (time in 1L..100L) original.add(point(time))
        val restored = TrackHistory(original.locations)
        restored.add(point(101))
        assertEquals(100, restored.locations.size)
        assertEquals(2L, restored.locations.first().lastTime)
        assertEquals(101L, restored.latest?.lastTime)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCoordinates() {
        TrackHistory().add(point(1, Double.NaN))
    }
}
