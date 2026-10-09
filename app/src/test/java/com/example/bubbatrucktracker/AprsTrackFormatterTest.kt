package com.bubbaman.bubbatrucktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AprsTrackFormatterTest {

    @Test
    fun normalizeCallsign_trimsAndUppercasesInput() {
        assertEquals("KB5TSI-5", AprsTrackFormatter.normalizeCallsign("  kb5tsi-5  "))
    }

    @Test
    fun buildLocationRequestUrl_encodesRequestParameters() {
        val url = AprsTrackFormatter.buildLocationRequestUrl("kb5tsi-5", "key with spaces")

        assertEquals(
            "https://api.aprs.fi/api/get?name=kb5tsi-5&what=loc&apikey=key+with+spaces&format=json",
            url
        )
    }

    @Test
    fun buildMarkerSnippet_joinsAvailableFields() {
        val snippet = AprsTrackFormatter.buildMarkerSnippet(
            BeaconLocation(
                name = "KB5TSI-5",
                lat = 32.12345,
                lng = -96.54321,
                lastTime = 1710000000,
                speed = "55",
                altitude = "240",
                comment = "On the move"
            )
        )

        assertEquals("Speed: 55 km/h  Alt: 240m\nOn the move", snippet)
    }

    @Test
    fun buildStatusText_formatsLocationData() {
        val text = AprsTrackFormatter.buildStatusText(
            BeaconLocation(
                name = "KB5TSI-5",
                lat = 32.12345,
                lng = -96.54321,
                lastTime = 1710000000,
                speed = "55",
                altitude = "240",
                comment = "On the move"
            ),
            Locale.US
        )

        assertTrue(text.contains("KB5TSI-5"))
        assertTrue(text.contains("Lat: 32.12345"))
        assertTrue(text.contains("Lng: -96.54321"))
        assertTrue(text.contains("Speed: 55 km/h"))
    }
}
