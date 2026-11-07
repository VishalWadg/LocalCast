package com.localcast.poc.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.io.Serial
import java.io.Serializable

/**
 * Setup mode selecting between broadcasting (HOST) or receiving (JOIN).
 */
enum class UiMode : Serializable {
    HOST,
    JOIN;

    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Contract alias for Milestone 3 specification compatibility.
 */
typealias SetupTab = UiMode

/**
 * Screen-level state for [PocScreen] and [PocViewModel].
 *
 * Implements [Serializable] and provides an optimized Compose [Saver] companion
 * to guarantee complete lifecycle safety under [androidx.compose.runtime.saveable.rememberSaveable]
 * across configuration changes (e.g. screen rotation during active WebRTC video streaming)
 * and system process recreation.
 *
 * @param selectedMode Currently selected setup tab (Host vs Join).
 * @param hostIpInput Target Host IP address entered on Phone B (Join mode).
 * @param hostPortInput Target Host signaling port (default "8765").
 * @param joinCodeInput Optional 6-digit session join code.
 * @param isDiagnosticsExpanded Accordion expansion state for M0–M4 infrastructure telemetry.
 * @param inputError Validation error message displayed to user, or null if inputs are valid.
 * @param isIpCopied Visual feedback indicator when Host IP is copied to clipboard.
 */
data class PocUiState(
    val selectedMode: UiMode = UiMode.HOST,
    val hostIpInput: String = "",
    val hostPortInput: String = "8765",
    val joinCodeInput: String = "",
    val isDiagnosticsExpanded: Boolean = false,
    val inputError: String? = null,
    val isIpCopied: Boolean = false
) : Serializable {
    // Contract compatibility accessors
    val activeTab: UiMode get() = selectedMode
    val targetIp: String get() = hostIpInput
    val targetPort: String get() = hostPortInput
    val isCopiedNotification: Boolean get() = isIpCopied

    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L

        /**
         * Jetpack Compose [Saver] for [PocUiState] enabling reflection-free,
         * type-safe persistence in [androidx.compose.runtime.saveable.rememberSaveable].
         */
        val Saver: Saver<PocUiState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.selectedMode.name,
                    state.hostIpInput,
                    state.hostPortInput,
                    state.joinCodeInput,
                    state.isDiagnosticsExpanded,
                    state.inputError,
                    state.isIpCopied
                )
            },
            restore = { list ->
                PocUiState(
                    selectedMode = (list.getOrNull(0) as? String)?.let { name ->
                        runCatching { UiMode.valueOf(name) }.getOrDefault(UiMode.HOST)
                    } ?: UiMode.HOST,
                    hostIpInput = list.getOrNull(1) as? String ?: "",
                    hostPortInput = list.getOrNull(2) as? String ?: "8765",
                    joinCodeInput = list.getOrNull(3) as? String ?: "",
                    isDiagnosticsExpanded = list.getOrNull(4) as? Boolean ?: false,
                    inputError = list.getOrNull(5) as? String,
                    isIpCopied = list.getOrNull(6) as? Boolean ?: false
                )
            }
        )
    }
}

/**
 * Input validation logic for IP addresses, signaling ports, and join codes.
 */
object UiValidators {
    private val IPV4_REGEX = """^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$""".toRegex()

    fun validateHostIp(ip: String): Pair<Boolean, String?> {
        val clean = ip.trim()
        return when {
            clean.isBlank() -> Pair(false, "Enter Phone A's Wi-Fi IPv4 address (e.g. 192.168.1.10)")
            !clean.matches(IPV4_REGEX) -> Pair(false, "Invalid IPv4 address format: $clean")
            else -> Pair(true, null)
        }
    }

    fun validateSignalingPort(portStr: String): Pair<Boolean, String?> {
        val port = portStr.trim().toIntOrNull()
            ?: return Pair(false, "Port must be a valid integer")
        return when {
            port < 1024 -> Pair(false, "Port $port is reserved (ports 1024-65535 required)")
            port > 65535 -> Pair(false, "Port $port exceeds maximum port number (65535)")
            else -> Pair(true, null)
        }
    }

    fun validateJoinCode(code: String): Pair<Boolean, String?> {
        val clean = code.trim()
        if (clean.isEmpty()) return Pair(true, null) // Optional code
        return if (clean.length == 6 && clean.all { it.isDigit() }) {
            Pair(true, null)
        } else {
            Pair(false, "Join code must be 6 digits")
        }
    }
}
