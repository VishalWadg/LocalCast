package com.localcast.poc.signaling

/**
 * Listener callbacks for signaling server events on Phone A (Host/Publisher).
 */
interface SignalingServerListener {
    fun onServerStarted(bindIp: String, port: Int, endpointUrl: String)
    fun onClientSocketConnected(remoteAddress: String)
    fun onMessageReceived(message: SignalMessage)
    fun onProtocolError(errorCode: String, reason: String)
    fun onClientSocketDisconnected(reason: String)
    fun onServerError(reason: String)
}

/**
 * Listener callbacks for signaling client events on Phone B (Client/Receiver).
 */
interface SignalingClientListener {
    fun onConnectedToHost(endpointUrl: String)
    fun onMessageReceived(message: SignalMessage)
    fun onProtocolError(errorCode: String, reason: String)
    fun onDisconnected(reason: String)
    fun onConnectionFailed(reason: String)
}

/**
 * Abstraction for the embedded local signaling server (Product Scope Section 9.3).
 */
interface SignalingServerTransport {
    suspend fun start(bindIp: String, port: Int, expectedSessionId: String)
    suspend fun send(message: SignalMessage): Boolean
    suspend fun stop()
}

/**
 * Abstraction for the local signaling client.
 */
interface SignalingClientTransport {
    suspend fun connect(hostIp: String, port: Int, onReadyToHandshake: suspend () -> Unit)
    suspend fun send(message: SignalMessage): Boolean
    suspend fun disconnect()
}
