package com.bubbaman.bubbatrucktracker

import timber.log.Timber
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class AprsIsClient(
    private val callsign: String,
    private val onStatus: (String) -> Unit,
    private val onLocation: (BeaconLocation) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var closed = false
    private var socket: Socket? = null

    fun start() {
        executor.execute {
            var retrySeconds = 5L
            while (!closed) {
                var connectedAt = 0L
                try {
                    val connection = Socket()
                    synchronized(lock) {
                        if (closed) {
                            connection.close()
                            return@execute
                        }
                        socket = connection
                    }
                    connection.use {
                        onStatus("Connecting to APRS-IS...")
                        Timber.tag("AprsTracking").d("Connecting to rotate.aprs2.net:14580")
                        it.connect(InetSocketAddress("rotate.aprs2.net", 14580), 15_000)
                        it.soTimeout = 90_000
                        val reader = it.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                        val banner = readLine(reader) ?: throw EOFException("APRS-IS closed before login")
                        if (!banner.startsWith("#")) throw IOException("Invalid APRS-IS greeting")
                        val login = "BTT" + UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
                        val writer = it.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII)
                        writer.write("user $login pass -1 vers BubbaTruckTracker ${BuildConfig.VERSION_NAME} filter b/$callsign\r\n")
                        writer.flush()
                        // Unverified is expected: pass -1 deliberately enables receiving only.
                        var loggedIn = false
                        while (!closed) {
                            val line = readLine(reader) ?: throw EOFException("APRS-IS connection closed")
                            if (line.startsWith("# logresp")) {
                                if (!line.startsWith("# logresp $login ") ||
                                    (!line.contains(" unverified") && !line.contains(" verified"))) {
                                    throw IOException("APRS-IS login rejected")
                                }
                                loggedIn = true
                                connectedAt = System.nanoTime()
                                onStatus("Live APRS-IS: waiting for $callsign's next position")
                                Timber.tag("AprsTracking").d("Receive-only APRS-IS subscription ready")
                            } else if (loggedIn && !line.startsWith("#")) {
                                AprsPacketParser.parse(line, callsign, System.currentTimeMillis() / 1000)
                                    ?.let(onLocation)
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (!closed) {
                        if (connectedAt != 0L && System.nanoTime() - connectedAt >= 60_000_000_000L) {
                            retrySeconds = 5L
                        }
                        Timber.tag("AprsTracking").w(e, "APRS-IS disconnected; retry in %d seconds", retrySeconds)
                        onStatus("APRS-IS disconnected: ${e.message}. Retrying in ${retrySeconds}s")
                    }
                } finally {
                    synchronized(lock) { socket = null }
                }
                if (!closed) {
                    try {
                        Thread.sleep(retrySeconds * 1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                    retrySeconds = (retrySeconds * 2).coerceAtMost(60L)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            try {
                socket?.close()
            } catch (e: IOException) {
                Timber.tag("AprsTracking").w(e, "Failed to close APRS-IS socket")
            }
        }
        executor.shutdownNow()
    }

    private fun readLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return if (line.isEmpty()) null else line.toString()
            if (ch == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= 512) throw IOException("APRS-IS line exceeds protocol limit")
            line.append(ch.toChar())
        }
    }
}
