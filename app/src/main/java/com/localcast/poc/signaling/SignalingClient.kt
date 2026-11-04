package com.localcast.poc.signaling

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Ktor CIO WebSocket client for Phone B.
 * Connects to Phone A's `ws://<hostIp>:8765/signal` endpoint over the bound Wi-Fi network.
 */
class SignalingClient(
    private val listener: SignalingClientListener
) : SignalingClientTransport {

    private var httpClient: HttpClient? = null
    private val activeSessionRef = AtomicReference<DefaultClientWebSocketSession?>(null)

    override suspend fun connect(
        hostIp: String,
        port: Int,
        onReadyToHandshake: suspend () -> Unit
    ) {
        disconnect()
        val client = HttpClient(CIO) {
            install(WebSockets) {
                maxFrameSize = SignalCodec.MAX_FRAME_BYTES.toLong()
            }
        }
        httpClient = client
        val endpointUrl = "ws://$hostIp:$port/signal"

        try {
            client.webSocket(
                method = HttpMethod.Get,
                host = hostIp,
                port = port,
                path = "/signal"
            ) {
                activeSessionRef.set(this)
                listener.onConnectedToHost(endpointUrl)
                onReadyToHandshake()

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val raw = frame.readText()
                        when (val decoded = SignalCodec.decodeAndValidate(raw)) {
                            is SignalDecodeResult.Success -> {
                                listener.onMessageReceived(decoded.message)
                            }
                            is SignalDecodeResult.Invalid -> {
                                listener.onProtocolError(decoded.errorCode, decoded.reason)
                            }
                        }
                    }
                }
            }
            listener.onDisconnected("WebSocket closed by host")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            listener.onConnectionFailed("Cannot connect to $endpointUrl — ${e.message}")
        } finally {
            activeSessionRef.set(null)
        }
    }

    override suspend fun send(message: SignalMessage): Boolean {
        val ws = activeSessionRef.get() ?: return false
        return try {
            val encoded = SignalCodec.encode(message)
            ws.send(Frame.Text(encoded))
            true
        } catch (e: Exception) {
            listener.onConnectionFailed("Send failed (${message.type}): ${e.message}")
            false
        }
    }

    override suspend fun disconnect() {
        val ws = activeSessionRef.getAndSet(null)
        runCatching {
            ws?.close(CloseReason(CloseReason.Codes.NORMAL, "Client leaving session"))
        }
        val client = httpClient
        httpClient = null
        runCatching {
            client?.close()
        }
    }
}
