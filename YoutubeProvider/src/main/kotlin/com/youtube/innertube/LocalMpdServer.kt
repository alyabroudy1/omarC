package com.youtube.innertube

import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * Minimal local HTTP server that serves a single DASH MPD manifest.
 *
 * CloudStream's player uses HttpDataSource which only supports http/https,
 * so we can't use data: URIs. This server runs on localhost with a random
 * port and serves the generated MPD XML whenever ExoPlayer requests it.
 *
 * Usage:
 *   val url = LocalMpdServer.serve(mpdXml)
 *   // url = "http://127.0.0.1:{port}/manifest.mpd"
 *
 * The server stays alive until [stop] is called or a new [serve] replaces it.
 */
object LocalMpdServer {

    private const val TAG = "LocalMpdServer"

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var mpdContent: ByteArray = ByteArray(0)

    /**
     * Start (or restart) the local server with the given MPD XML content.
     *
     * @return A localhost HTTP URL pointing to the manifest.
     */
    @Synchronized
    fun serve(mpd: String): String {
        stop()

        mpdContent = mpd.toByteArray(Charsets.UTF_8)
        val socket = ServerSocket(0) // OS picks a free port
        serverSocket = socket
        val port = socket.localPort

        serverThread = Thread({
            Log.d(TAG, "Server started on port $port")
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    try {
                        // Read the HTTP request line (we ignore it — only one resource)
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        reader.readLine() // e.g. "GET /manifest.mpd HTTP/1.1"
                        // Drain remaining headers
                        while (true) {
                            val headerLine = reader.readLine()
                            if (headerLine.isNullOrBlank()) break
                        }

                        // Respond with the MPD
                        val body = mpdContent
                        val header = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/dash+xml\r\n")
                            append("Content-Length: ${body.size}\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }
                        val out = client.getOutputStream()
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(body)
                        out.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling request: ${e.message}")
                    } finally {
                        try { client.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    // Socket closed — server is shutting down
                    if (!socket.isClosed) {
                        Log.w(TAG, "Accept error: ${e.message}")
                    }
                    break
                }
            }
            Log.d(TAG, "Server stopped")
        }, "LocalMpdServer").apply {
            isDaemon = true
            start()
        }

        val url = "http://127.0.0.1:$port/manifest.mpd"
        Log.d(TAG, "Serving MPD at $url (${mpdContent.size} bytes)")
        return url
    }

    /** Stop the server and release resources. */
    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        mpdContent = ByteArray(0)
    }
}
