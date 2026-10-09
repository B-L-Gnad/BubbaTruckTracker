package com.bubbaman.bubbatrucktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

class AprsPacketParserTest {
    private val receivedTime = 1710000000L

    private fun parse(payload: String, destination: String = "APRS"): BeaconLocation? =
        AprsPacketParser.parse(
            "KB5TSI-5>$destination,WIDE1-1,qAR,IGATE:$payload",
            "KB5TSI-5", receivedTime
        )

    @Test
    fun uncompressedNorthWestAndMotion() {
        val result = parse("!4903.50N/07201.75W>123/065/A=001000 Road trip")!!
        assertEquals(49.0583333333, result.lat, 0.0000001)
        assertEquals(-72.0291666667, result.lng, 0.0000001)
        assertEquals("120.38", result.speed)
        assertEquals("304.8", result.altitude)
        assertEquals("/A=001000 Road trip", result.comment)
        assertEquals("KB5TSI-5", result.name)
        assertEquals(receivedTime, result.lastTime)
    }

    @Test
    fun uncompressedSouthEastAndNoInventedMotion() {
        val result = parse("=3345.00S\\15112.00E>parked")!!
        assertEquals(-33.75, result.lat, 0.0000001)
        assertEquals(151.2, result.lng, 0.0000001)
        assertEquals("", result.speed)
        assertEquals("", result.altitude)
        assertEquals("parked", result.comment)
    }

    @Test
    fun timestampsAreValidatedButReceiveTimeIsPreserved() {
        for (prefix in listOf("/092345z", "@092345/", "@234559h")) {
            val result = parse("${prefix}4903.50N/07201.75W>")!!
            assertEquals(receivedTime, result.lastTime)
            assertEquals(49.0583333333, result.lat, 0.0000001)
        }
        for (stamp in listOf("002345z", "322345z", "092445z", "092360z", "240000h", "123456x")) {
            assertNull(stamp, parse("@${stamp}4903.50N/07201.75W>"))
        }
    }

    @Test
    fun ambiguityUsesCellMidpoint() {
        val cases = listOf(
            "4903.5 N/07201.7 W>" to (49 + 3.55 / 60 to -(72 + 1.75 / 60)),
            "4903.  N/07201.  W>" to (49 + 3.5 / 60 to -(72 + 1.5 / 60)),
            "490 .  N/0720 .  W>" to (49 + 5.0 / 60 to -(72 + 5.0 / 60)),
            "49  .  N/072  .  W>" to (49.5 to -72.5)
        )
        for ((position, expected) in cases) {
            val result = parse("!$position")!!
            assertEquals(expected.first, result.lat, 0.0000001)
            assertEquals(expected.second, result.lng, 0.0000001)
        }
        assertNull(parse("!4903.  N/07201.75W>"))
        assertNull(parse("!490 .50N/0720 .75W>"))
    }

    @Test
    fun coordinateBoundsAreCheckedIncludingPoles() {
        assertNotNull(parse("!9000.00N/18000.00E>"))
        for (position in listOf(
            "9100.00N/07201.75W>", "4903.50N/18100.00W>",
            "4960.00N/07201.75W>", "4903.50N/07260.00W>",
            "9000.01N/07201.75W>", "4903.50N/18000.01E>"
        )) assertNull(position, parse("!$position"))
    }

    @Test
    fun compressedAprs101ExampleDecodesCoordinatesAndSpeed() {
        // APRS 1.01 compressed example: y=15482880, x=20428137, c=22, s=47.
        val result = parse("!/5L!!<*e7>7P[compressed")!!
        assertEquals(49.5, result.lat, 0.00001)
        assertEquals(-72.75, result.lng, 0.00001)
        assertEquals((1.08.pow(47) - 1) * 1.852, result.speed.toDouble(), 0.005)
        assertEquals("compressed", result.comment)
    }

    @Test
    fun compressedSouthEastAndAltitude() {
        val latCode = ((90 - -33.75) * 380926).toInt()
        val lngCode = ((180 + 151.2) * 190463).toInt()
        val result = parse(
            "!/" + encode91(latCode, 4) + encode91(lngCode, 4) +
                ">" + encode91(2000, 2) + "1"
        )!!
        assertEquals(-33.75, result.lat, 0.00001)
        assertEquals(151.2, result.lng, 0.00001)
        assertEquals("", result.speed)
        assertEquals(1.002.pow(2000) * 0.3048, result.altitude.toDouble(), 0.005)
    }

    @Test
    fun compressedWithoutMotionAndRadioRangeDoNotInventSpeed() {
        assertEquals("", parse("!/5L!!<*e7>  !")!!.speed)
        assertEquals("", parse("!/5L!!<*e7>{P!")!!.speed)
        assertNull(parse("!/{{{{{{{{>!!!"))
        assertNull(parse("!/5L!!<*e7>!!"))
    }

    @Test
    fun micECoordinatesAndSpeedAreDecodedFromDestinationAndData() {
        // APRS 1.01 encoding: SRQR3T = 32 12.34 N, no +100 offset, west.
        // | I _ = 96 degrees, 45 minutes, 67 hundredths; " O 3 = 65 kt, 123 deg.
        for (type in listOf('`', '\'')) {
            val result = parse("$type|I_\"O3>/Moving", "SRQR3T-0")!!
            assertEquals(32 + 12.34 / 60, result.lat, 0.0000001)
            assertEquals(-(96 + 45.67 / 60), result.lng, 0.0000001)
            assertEquals("120.38", result.speed)
            assertEquals("Moving", result.comment)
        }
    }

    @Test
    fun micEMatchesPublishedAprslibReferencePacket() {
        // https://aprs-python.readthedocs.io/en/stable/parse_formats.html#mic-e
        val result = parse("`CF\"l#![/`\"3z}_ ", "SUSUR1")!!
        assertEquals(35.58683333333333, result.lat, 0.0000001)
        assertEquals(139.701, result.lng, 0.0000001)
        assertEquals("0", result.speed)
        assertEquals("8", result.altitude)
        assertEquals("`_", result.comment)
    }

    @Test
    fun micETrackerTypeAndTinyTrackSuffixDoNotAffectMtRtgPosition() {
        // Protocol-encoded fixture, not a captured live station packet.
        // Byonics trackers use the '......|3 device identifier in the Mic-E comment.
        val line = "KB5TSI-5>SRQR3T,WIDE1-1,WIDE2-1,qAR,IGATE:" +
            "`|I_\"O3>/'" + encode91(10240, 3) + "}MT-RTG|3\r\n"
        val result = AprsPacketParser.parse(line, "KB5TSI-5", receivedTime)!!
        assertEquals(32.205666666666666, result.lat, 0.0000001)
        assertEquals(-96.76116666666667, result.lng, 0.0000001)
        assertEquals("120.38", result.speed)
        assertEquals("240", result.altitude)
        assertEquals("'MT-RTG|3", result.comment)
    }

    @Test
    fun micELongitudeMinutesBelowTenUseSixtyMinuteOffset() {
        // Mic-E encodes minutes 0..9 with a +60 offset to avoid control bytes.
        val result = parse("`|^_\"O3>/'MT-RTG|3", "SRQR3T")!!
        assertEquals(-(96 + 6.67 / 60), result.lng, 0.0000001)
        assertEquals("120.38", result.speed)
    }

    @Test
    fun micESouthEastAndCustomMessageBits() {
        val result = parse("`(I_\"O3>/", "DCB234")!!
        assertEquals(-(32 + 12.34 / 60), result.lat, 0.0000001)
        assertEquals(12 + 45.67 / 60, result.lng, 0.0000001)
        assertEquals("120.38", result.speed)
    }

    @Test
    fun micELongitudeOffsetAndWrapping() {
        val result = parse("`pI_\"O3>/", "SRQRST")!!
        assertEquals(-(104 + 45.67 / 60), result.lng, 0.0000001)
        val smallLongitude = parse("`zI_\"O3>/", "SRQRST")!!
        assertEquals(-(4 + 45.67 / 60), smallLongitude.lng, 0.0000001)
    }

    @Test
    fun micESpeedAndCourseWrapAndZeroSpeed() {
        // 865 wraps to 65 knots; 523 wraps to course 123.
        assertEquals("120.38", parse("`|I_rS3>/", "SRQR3T")!!.speed)
        val stoppedBody = "|I_" + "\u001c\u001c\u001c" + ">/"
        assertEquals("0", parse("`$stoppedBody", "SRQR3T")!!.speed)
    }

    @Test
    fun micEAmbiguityAndAltitudeInMeters() {
        val result = parse("`|I_\"O3>/" + encode91(10321, 3) + "}Moving", "SRQR3Z")!!
        assertEquals(32 + 12.35 / 60, result.lat, 0.0000001)
        assertEquals(-(96 + 45.65 / 60), result.lng, 0.0000001)
        assertEquals("321", result.altitude)
        assertEquals("Moving", result.comment)
        assertNull(parse("`|I_\"O3>/", "SRQZ3T"))
    }

    @Test
    fun malformedMicEIsIgnored() {
        for (destination in listOf("APRS", "SRQM3T", "ZZZZZZ", "SRQRT!")) {
            assertNull(destination, parse("`|I_\"O3>/", destination))
        }
        for (body in listOf("", "|I_", "|I_\u0000O3>/", "|I\u0080\"O3>/", "|I_\"O3>!")) {
            assertNull(body, parse("`$body", "SRQR3T"))
        }
    }

    @Test
    fun liveObjectsAndItemsFromMatchingSourceAreAccepted() {
        assertNotNull(parse(";TRUCK    *092345z4903.50N/07201.75W>"))
        assertNotNull(parse(")TRUCK!4903.50N/07201.75W>"))
        assertNull(parse(";TRUCK    _092345z4903.50N/07201.75W>"))
        assertNull(parse(")TRUCK_4903.50N/07201.75W>"))
    }

    @Test
    fun weatherWindIsNotVehicleSpeed() {
        assertEquals("", parse("!4903.50N/07201.75W_090/065g100")!!.speed)
    }

    @Test
    fun altitudeFeetSupportsNegativeAltitude() {
        assertEquals("-30.48", parse("!4903.50N/07201.75W>/A=-00100")!!.altitude)
    }

    @Test
    fun sourceMustMatchExactlyButNotCase() {
        val payload = "!4903.50N/07201.75W>"
        assertNotNull(AprsPacketParser.parse("kb5tsi-5>APRS:$payload", " KB5TSI-5 ", receivedTime))
        for (source in listOf("KB5TSI", "KB5TSI-4", "OTHER", "KB5TSI-50")) {
            assertNull(AprsPacketParser.parse("$source>APRS:$payload", "KB5TSI-5", receivedTime))
        }
    }

    @Test
    fun irrelevantAndMalformedPacketsAreIgnored() {
        for (line in listOf(
            "", "# server comment", "KB5TSI-5>APRS", "KB5TSI-5>APRS:",
            "KB5TSI-5>:!4903.50N/07201.75W>", "KB5TSI-5>APRS:>Status",
            "KB5TSI-5>APRS::RECIPIENT:message", "KB5TSI-5>APRS:T#001,1,2",
            "KB5TSI-5>APRS:!garbage", "KB5TSI-5>APRS:@123",
            "KB5TSI-5>APRS:}OTHER>APRS:!4903.50N/07201.75W>"
        )) assertNull(line, AprsPacketParser.parse(line, "KB5TSI-5", receivedTime))
    }

    @Test
    fun randomAndTruncatedPacketsNeverThrow() {
        val random = Random(1234)
        repeat(3000) {
            val body = buildString {
                repeat(random.nextInt(0, 90)) { append(random.nextInt(0, 256).toChar()) }
            }
            parse(body)
            for (type in "!/=@`';)") parse("$type$body", "SRQR3T")
        }
        val packet = "KB5TSI-5>SRQR3T:`|I_\"O3>/Moving"
        for (length in 0 until packet.length) {
            AprsPacketParser.parse(packet.take(length), "KB5TSI-5", receivedTime)
        }
    }

    private fun encode91(number: Int, length: Int): String {
        var value = number
        val chars = CharArray(length)
        for (index in length - 1 downTo 0) {
            chars[index] = (value % 91 + 33).toChar()
            value /= 91
        }
        return String(chars)
    }
}
