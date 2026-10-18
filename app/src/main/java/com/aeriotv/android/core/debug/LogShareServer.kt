package com.aeriotv.android.core.debug

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Ephemeral LAN HTTP server that serves the debug log file at /log.txt so a
 * phone can download it by scanning a QR code. Direct port of the tvOS
 * LogShareServer (Aerio DeveloperSettingsView.swift): tvOS has no share
 * sheet, and Google TV has the same gap (no useful ACTION_SEND targets).
 *
 * Security posture: binds all interfaces, but the served path carries an
 * unguessable per-session token (`/log-<random>.txt`) so a passive device on
 * the LAN cannot blind-fetch the log even during the brief share window -- it
 * has to know the exact URL, which only the on-screen QR code reveals. Layered
 * with: the server only lives while the share dialog is on screen, the file
 * content is already credential-sanitized by LogSanitizer on the write path,
 * and every other path (including a bare /log.txt) gets a 404. Each connection
 * is answered once and closed; the socket stays open for repeat downloads
 * until [stop].
 */
class LogShareServer(private val file: File) {

    @Volatile private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    // Unguessable per-instance path token (128-bit, SecureRandom-backed via
    // UUID). The QR-encoded share URL is the only place it appears, so knowing
    // the IP:port alone is not enough to pull the log.
    private val servePath: String = "/log-${java.util.UUID.randomUUID().toString().replace("-", "")}.txt"

    /** Binds a random high port on all interfaces and returns the share URL, or null on failure. */
    fun start(): String? {
        val ip = lanIpv4() ?: return null
        // Port 0 = OS-assigned ephemeral high port, bound on 0.0.0.0, the
        // same behavior as the iOS NWListener with no required port.
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        serverSocket = ss
        thread = thread(name = "LogShareServer", isDaemon = true) {
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching { client.use { handle(it) } }
            }
        }
        return "http://$ip:${ss.localPort}$servePath"
    }

    fun stop() {
        // Closing the socket pops the accept() loop; the daemon thread exits.
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        // Only the request line matters ("GET /log.txt HTTP/1.1"); the rest
        // of the headers are ignored, same as the iOS first-line parser.
        val reqLine = socket.getInputStream().bufferedReader().readLine() ?: return
        val path = reqLine.split(" ").getOrNull(1)?.substringBefore('?')
        val out = BufferedOutputStream(socket.getOutputStream())
        if (path == servePath && file.exists()) {
            // Snapshot the length up front: the log can keep growing during
            // the transfer while logging is enabled, and serving more bytes
            // than the promised Content-Length hangs browsers.
            val len = file.length()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: $len\r\n" +
                        "Content-Disposition: attachment; filename=\"aerio_debug_logs.txt\"\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            file.inputStream().use { ins -> copyExactly(ins, out, len) }
        } else {
            out.write(
                (
                    "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: 9\r\n" +
                        "Connection: close\r\n\r\nNot Found"
                    ).toByteArray(),
            )
        }
        out.flush()
    }

    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /**
     * Permissionless LAN IPv4 discovery, the analog of the iOS getifaddrs
     * walk. NetworkInterface needs no manifest permission and covers both
     * the Streamer's Ethernet port and Wi-Fi; the eth-before-wlan ordering
     * prefers the wired address when both are up. VPN tunnels are excluded
     * via isVirtual/isPointToPoint.
     */
    private fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter {
                runCatching {
                    it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint
                }.getOrDefault(false)
            }
            .sortedBy {
                when {
                    it.name.startsWith("eth") -> 0
                    it.name.startsWith("wlan") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
