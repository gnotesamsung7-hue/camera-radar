package com.markdave.cameraradar

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs on the radar phone. Serves the screen page at http://<hotspot-ip>:<port>/
 * and streams radar data to every connected screen over a WebSocket.
 */
class RadarServer(port: Int, private val ctx: Context, private val engine: RadarEngine) : NanoWSD(port) {

    private val clients = CopyOnWriteArrayList<Client>()
    private val page: String by lazy {
        ctx.assets.open("screen.html").bufferedReader().use { it.readText() }
    }

    val clientCount: Int get() = clients.size

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: "/"
        return if (uri == "/" || uri == "/index.html") {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", page).apply {
                addHeader("Cache-Control", "no-store")
            }
        } else {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket = Client(handshake)

    fun broadcast(text: String) {
        for (c in clients) {
            try {
                c.send(text)
            } catch (e: Exception) {
                clients.remove(c)
            }
        }
    }

    inner class Client(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        override fun onOpen() {
            clients.add(this)
            try { send(engine.helloJson()) } catch (e: IOException) { clients.remove(this) }
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            try {
                engine.applySettings(JSONObject(message.textPayload))
            } catch (e: Exception) {
                // ignore malformed messages
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            clients.remove(this)
        }
    }
}
