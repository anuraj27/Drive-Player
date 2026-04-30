package com.driveplayer.player

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Tiny localhost HTTP proxy that injects an OAuth Bearer header on outbound requests.
 *
 * libVLC has no built-in way to send a custom Authorization header, and Google Drive
 * has stopped accepting `?access_token=` query params for media downloads (returns 403).
 * The proxy runs on 127.0.0.1:randomPort, accepts libVLC's GET (forwarding any Range
 * header), and pipes the Drive response back. One proxy per cloud playback session.
 *
 * @param tokenProvider returns the latest access token on every call. Re-evaluated per
 *                     request and again on retry, so a refresh elsewhere automatically
 *                     propagates here.
 * @param onRefreshNeeded invoked when Drive returns 401. Should perform a synchronous
 *                       token refresh; if it returns true the request is retried once.
 */
class DriveAuthProxy(
    private val targetUrl: String,
    private val tokenProvider: () -> String,
    private val onRefreshNeeded: () -> Boolean = { false },
) {
    private val server: ServerSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    @Volatile private var stopped = false

    fun start() {
        Thread({
            while (!stopped && !server.isClosed) {
                try {
                    val client = server.accept()
                    Thread({ handle(client) }, "DriveAuthProxy-conn").start()
                } catch (_: Exception) {
                    // Socket closed — exit accept loop.
                    return@Thread
                }
            }
        }, "DriveAuthProxy-accept").start()
    }

    fun stop() {
        stopped = true
        try { server.close() } catch (_: Exception) {}
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Read request line + headers (until CRLF CRLF).
            val reqHeaders = StringBuilder()
            val one = ByteArray(1)
            while (input.read(one) == 1) {
                reqHeaders.append(one[0].toInt().toChar())
                if (reqHeaders.length >= 4 &&
                    reqHeaders[reqHeaders.length - 4] == '\r' &&
                    reqHeaders[reqHeaders.length - 3] == '\n' &&
                    reqHeaders[reqHeaders.length - 2] == '\r' &&
                    reqHeaders[reqHeaders.length - 1] == '\n') break
                if (reqHeaders.length > 8192) break
            }

            // Forward Range header if present (so seeks work).
            val rangeHeader = Regex("(?im)^Range:\\s*(.+)$")
                .find(reqHeaders)?.groupValues?.get(1)?.trim()

            // Try the request; on 401 trigger a refresh and retry once.
            var conn = openUpstream(rangeHeader)
            var status = try { conn.responseCode } catch (_: Exception) { 502 }
            if (status == 401 && onRefreshNeeded()) {
                try { conn.disconnect() } catch (_: Exception) {}
                conn = openUpstream(rangeHeader)
                status = try { conn.responseCode } catch (_: Exception) { 502 }
            }
            val message = conn.responseMessage ?: "OK"

            val statusLine = "HTTP/1.1 $status $message\r\n"
            output.write(statusLine.toByteArray())
            for ((key, values) in conn.headerFields) {
                if (key.isNullOrEmpty()) continue
                // Skip hop-by-hop and chunking-related headers — we just stream raw bytes.
                if (key.equals("Transfer-Encoding", true)) continue
                if (key.equals("Connection", true)) continue
                for (v in values) output.write("$key: $v\r\n".toByteArray())
            }
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.flush()

            val body = if (status in 200..299) conn.inputStream else conn.errorStream
            body?.use { it.copyTo(output) }
            output.flush()
        } catch (_: Exception) {
            // Client likely disconnected mid-stream — just clean up.
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun openUpstream(rangeHeader: String?): HttpURLConnection =
        (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
            if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
            connectTimeout = 15_000
            readTimeout = 30_000
        }
}
