package com.localcast.poc.signaling

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded Ktor CIO WebSocket signaling server for Phone A.
 *
 * Binds strictly to Phone A's resolved Wi-Fi IPv4 address (not 0.0.0.0) on `/signal`
 * and enforces the single-receiver POC admission rule.
 */
class LocalSignalingServer(
    private val listener: SignalingServerListener
) : SignalingServerTransport {

    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val activeSessionRef = AtomicReference<DefaultWebSocketServerSession?>(null)

    @Volatile
    private var currentSessionId: String? = null

    override suspend fun start(bindIp: String, port: Int, expectedSessionId: String) {
        stop()
        currentSessionId = expectedSessionId
        try {
            val engine = embeddedServer(
                factory = CIO,
                host = bindIp,
                port = port
            ) {
                install(WebSockets) {
                    maxFrameSize = SignalCodec.MAX_FRAME_BYTES.toLong()
                }
                routing {
                    webSocket("/signal") {
                        val existing = activeSessionRef.get()
                        if (existing != null && existing != this) {
                            close(
                                CloseReason(
                                    CloseReason.Codes.VIOLATED_POLICY,
                                    "POC v0.1 supports a single receiver connection"
                                )
                            )
                            return@webSocket
                        }
                        activeSessionRef.set(this)
                        val remoteHost = call.request.local.remoteHost
                        listener.onClientSocketConnected(remoteHost)

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val rawText = frame.readText()
                                    when (val result = SignalCodec.decodeAndValidate(rawText, currentSessionId)) {
                                        is SignalDecodeResult.Success -> {
                                            listener.onMessageReceived(result.message)
                                        }
                                        is SignalDecodeResult.Invalid -> {
                                            listener.onProtocolError(result.errorCode, result.reason)
                                        }
                                    }
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            listener.onClientSocketDisconnected(e.message ?: "WebSocket read error")
                        } finally {
                            activeSessionRef.compareAndSet(this, null)
                            listener.onClientSocketDisconnected("Remote peer closed WebSocket")
                        }
                    }
                }
            }
            engine.start(wait = false)
            serverEngine = engine
            listener.onServerStarted(bindIp, port, "ws://$bindIp:$port/signal")
        } catch (e: Exception) {
            listener.onServerError("Failed to start Ktor CIO server on $bindIp:$port — ${e.message}")
        }
    }

    override suspend fun send(message: SignalMessage): Boolean {
        val ws = activeSessionRef.get() ?: return false
        return try {
            val wire = SignalCodec.encode(message)
            ws.send(Frame.Text(wire))
            true
        } catch (e: Exception) {
            listener.onServerError("Failed to send ${message.type}: ${e.message}")
            false
        }
    }

    override suspend fun stop() {
        val ws = activeSessionRef.getAndSet(null)
        runCatching {
            ws?.close(CloseReason(CloseReason.Codes.NORMAL, "Host stopped session"))
        }
        val engine = serverEngine
        serverEngine = null
        runCatching {
            engine?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        }
    }
}
