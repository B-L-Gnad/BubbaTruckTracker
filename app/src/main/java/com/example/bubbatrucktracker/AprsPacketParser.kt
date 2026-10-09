package com.bubbaman.bubbatrucktracker

import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/**
 * APRS 1.01 position decoding, independent of Android and network state.
 * [parse] preserves the supplied receive time (epoch seconds), rather than guessing
 * the date/time zone of an incomplete on-air timestamp.
 */
object AprsPacketParser {
    private val normalPosition = Regex(
        "^([0-9]{2})([0-9 ]{2}\\.[0-9 ]{2})([NSns])([/\\\\0-9A-Z])" +
            "([0-9]{3})([0-9 ]{2}\\.[0-9 ]{2})([EWew])([!-~])(.*)$"
    )
    private val courseSpeed = Regex("^([0-9]{3})/([0-9]{3})")
    private val altitudeFeet = Regex("/A=(-[0-9]{5}|[0-9]{6})(?![0-9])")
    private val micEAltitude = Regex("([!-{]{3})}")

    fun parse(line: String, callsign: String, receivedTime: Long): BeaconLocation? {
        val packet = line.trimEnd('\r', '\n')
        if (packet.isEmpty() || packet.startsWith('#')) return null
        val separator = packet.indexOf('>')
        val colon = packet.indexOf(':', separator + 1)
        if (separator <= 0 || colon <= separator + 1) return null
        val source = packet.substring(0, separator)
        if (!source.equals(callsign.trim(), ignoreCase = true)) return null
        val destination = packet.substring(separator + 1, colon).substringBefore(',')
        if (destination.isEmpty()) return null
        val payload = packet.substring(colon + 1)
        if (payload.isEmpty()) return null
        val position = when (payload[0]) {
            '!', '=' -> parsePosition(payload.substring(1))
            '/', '@' -> timestampedPosition(payload.substring(1))
            '`', '\'' -> parseMicE(destination.substringBefore('-'), payload.substring(1))
            ';' -> {
                if (payload.length < 19 || payload[10] != '*') return null
                timestampedPosition(payload.substring(11))
            }
            ')' -> {
                val end = payload.indexOfFirst { it == '!' || it == '_' }
                if (end !in 4..10 || payload[end] != '!') return null
                parsePosition(payload.substring(end + 1))
            }
            else -> null
        } ?: return null
        if (!position.lat.isFinite() || !position.lng.isFinite() ||
            position.lat !in -90.0..90.0 || position.lng !in -180.0..180.0
        ) return null
        return BeaconLocation(
            name = source.uppercase(Locale.ROOT),
            lat = position.lat,
            lng = position.lng,
            lastTime = receivedTime,
            speed = position.speed?.let(::number).orEmpty(),
            altitude = position.altitude?.let(::number).orEmpty(),
            comment = position.comment.trim()
        )
    }

    private data class Position(
        val lat: Double,
        val lng: Double,
        val speed: Double? = null,
        val altitude: Double? = null,
        val comment: String = ""
    )

    private fun timestampedPosition(body: String): Position? {
        if (body.length < 7 || body.take(6).any { it !in '0'..'9' }) return null
        val a = body.substring(0, 2).toInt()
        val b = body.substring(2, 4).toInt()
        val c = body.substring(4, 6).toInt()
        val valid = when (body[6]) {
            'z', '/' -> a in 1..31 && b in 0..23 && c in 0..59
            'h' -> a in 0..23 && b in 0..59 && c in 0..59
            else -> false
        }
        return if (valid) parsePosition(body.substring(7)) else null
    }

    private fun parsePosition(body: String): Position? {
        val match = normalPosition.matchEntire(body)
        if (match == null) return parseCompressed(body)
        val g = match.groupValues
        if (g[2].count { it == ' ' } != g[6].count { it == ' ' }) return null
        val lat = coordinate(g[1], g[2], 90) ?: return null
        val lng = coordinate(g[5], g[6], 180) ?: return null
        val comment = g[9]
        val extension = courseSpeed.find(comment)
        // The same xxx/xxx layout means wind (mph), not vehicle speed, for weather.
        val speed = if (g[8] != "_" && extension != null &&
            extension.groupValues[1].toInt() in 0..360
        ) extension.groupValues[2].toInt() * 1.852 else null
        return Position(
            if (g[3].equals("S", true)) -lat else lat,
            if (g[7].equals("W", true)) -lng else lng,
            speed, feetAltitude(comment),
            if (speed != null) comment.substring(7) else comment
        )
    }

    private fun coordinate(degrees: String, minutes: String, maximum: Int): Double? {
        val digits = minutes.replace(".", "")
        val firstSpace = digits.indexOf(' ')
        if (firstSpace >= 0 && digits.substring(firstSpace).any { it != ' ' }) return null
        val centered = when (firstSpace) {
            -1 -> digits
            0 -> "3000"
            else -> digits.take(firstSpace) + "5" + "0".repeat(3 - firstSpace)
        }
        val minuteValue = centered.toInt() / 100.0
        val degreeValue = degrees.toInt()
        if (minuteValue >= 60 || degreeValue > maximum ||
            (degreeValue == maximum && minuteValue != 0.0)
        ) return null
        return degreeValue + minuteValue / 60
    }

    private fun parseCompressed(body: String): Position? {
        if (body.length < 13 || !compressedTable(body[0]) ||
            body[9] !in '!'..'~'
        ) return null
        val latitudeCode = base91(body.substring(1, 5)) ?: return null
        val longitudeCode = base91(body.substring(5, 9)) ?: return null
        val c = body[10].code - 33
        val s = body[11].code - 33
        val type = body[12].code - 33
        if (c !in -1..90 || s !in -1..90 || type !in 0..90) return null
        var speed: Double? = null
        var altitude: Double? = null
        if (c >= 0 && s >= 0) {
            if (type and 0x18 == 0x10) {
                altitude = 1.002.pow(c * 91 + s) * 0.3048
            } else if (c < 90) {
                speed = (1.08.pow(s) - 1) * 1.852
            }
        }
        val comment = body.substring(13)
        return Position(
            90 - latitudeCode / 380926.0,
            -180 + longitudeCode / 190463.0,
            speed, feetAltitude(comment) ?: altitude, comment
        )
    }

    private fun compressedTable(table: Char) =
        table == '/' || table == '\\' || table in 'A'..'Z' || table in 'a'..'j'

    private fun parseMicE(destination: String, body: String): Position? {
        if (destination.length != 6 || body.length < 8 ||
            body[0].code !in 38..127 || body[1].code !in 38..97 ||
            body[2].code !in 28..127 || body[3].code !in 28..127 ||
            body[4].code !in 28..125 || body[5].code !in 28..127 ||
            body[6] !in '!'..'~' ||
            !(body[7] == '/' || body[7] == '\\' || body[7] in '0'..'9' ||
                body[7] in 'A'..'Z')
        ) return null
        val latitudeDigits = StringBuilder()
        destination.forEachIndexed { index, ch ->
            val digit = when {
                ch in '0'..'9' -> ch
                ch in 'P'..'Y' -> (ch.code - 32).toChar()
                index < 3 && ch in 'A'..'J' -> (ch.code - 17).toChar()
                ch == 'L' || ch == 'Z' || (index < 3 && ch == 'K') -> ' '
                else -> return null
            }
            latitudeDigits.append(digit)
        }
        val digits = latitudeDigits.toString()
        val ambiguity = digits.count { it == ' ' }
        if (ambiguity > 4 || digits.take(2).any { it !in '0'..'9' }) return null
        val lat = coordinate(digits.take(2), digits.substring(2, 4) + "." + digits.takeLast(2), 90)
            ?: return null
        var longitudeDegrees = body[0].code - 28
        if (destination[4] >= 'P') longitudeDegrees += 100
        longitudeDegrees = when (longitudeDegrees) {
            in 180..189 -> longitudeDegrees - 80
            in 190..199 -> longitudeDegrees - 190
            else -> longitudeDegrees
        }
        var minutes = body[1].code - 28
        if (minutes >= 60) minutes -= 60
        val hundredths = body[2].code - 28
        if (minutes !in 0..59 || hundredths !in 0..99) return null
        var longitudeMinutes = minutes + hundredths / 100.0
        longitudeMinutes = when (ambiguity) {
            1 -> (floor(longitudeMinutes * 10) + 0.5) / 10
            2 -> floor(longitudeMinutes) + 0.5
            3 -> (floor(longitudeMinutes / 10) + 0.5) * 10
            4 -> 30.0
            else -> longitudeMinutes
        }
        var speed = (body[3].code - 28) * 10 + (body[4].code - 28) / 10
        var course = ((body[4].code - 28) % 10) * 100 + body[5].code - 28
        if (speed >= 800) speed -= 800
        if (course >= 400) course -= 400
        if (speed !in 0..799 || course !in 0..360) return null
        val comment = body.substring(8)
        val altitude = micEAltitude.find(comment)
        return Position(
            if (destination[3] >= 'P') lat else -lat,
            (longitudeDegrees + longitudeMinutes / 60) *
                if (destination[5] >= 'P') -1 else 1,
            speed * 1.852,
            altitude?.let { base91(it.groupValues[1])?.minus(10000)?.toDouble() }
                ?: feetAltitude(comment),
            if (altitude != null) comment.removeRange(altitude.range) else comment
        )
    }

    private fun base91(text: String): Int? {
        var value = 0
        for (ch in text) {
            if (ch !in '!'..'{') return null
            value = value * 91 + ch.code - 33
        }
        return value
    }

    private fun feetAltitude(comment: String): Double? =
        altitudeFeet.find(comment)?.groupValues?.get(1)?.toIntOrNull()?.times(0.3048)

    private fun number(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}
