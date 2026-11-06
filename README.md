# LocalCast — Complete POC v0.1 (Milestones M0 through M4)

**Android-first · Local Wi-Fi LAN · Offline-Capable · SDK 37 · AGP 9.1.1**

LocalCast is an offline-capable, peer-to-peer (P2P) real-time video and audio streaming Android application. It allows two Android devices connected to the same local Wi-Fi network to stream 720p/480p camera video and microphone audio with sub-100ms latency without requiring internet access, cloud databases, or third-party servers.

---

## 📚 Documentation & Learning Guides

Whether you're exploring the codebase, learning WebRTC, or preparing a technical presentation, check out these modular guides:

| Guide | Description |
|---|---|
| **[Architecture & Mental Model Guide](docs/ARCHITECTURE.md)** | Beginner-friendly explanation of WebRTC, the Hotel Intercom analogy, milestone breakdowns (M0–M4), and interactive Mermaid sequence diagrams. |
| **[Tech Stack & Architectural Decisions (ADRs)](docs/TECH_STACK_AND_DECISIONS.md)** | Deep-dive into *why* each technology was chosen (WebRTC native, Ktor CIO, Camera2, zero-STUN design) with trade-off analyses. |
| **[Beginner's Cheat Sheet & Presentation Guide](docs/BEGINNER_CHEATSHEET.md)** | Plain-English glossary, answers to top 10 interview questions, and a step-by-step 3-minute live demonstration script. |
| **[ADR-0001 Architecture Specification](docs/ADR-0001-poc-m0-to-m4-architecture.md)** | Formal Architecture Decision Record for the POC v0.1 milestone specification. |

---

## ⚡ How It Works in 30 Seconds

```text
Phone A (Host / Publisher)                        Phone B (Receiver / Client)
┌────────────────────────┐                       ┌────────────────────────┐
│  Ktor WebSocket Server │ ◄── HELLO / JOIN ───  │  Ktor WebSocket Client │
│  (Port 8765 on Wi-Fi)  │ ─── JOIN_ACCEPT ────► │  (ws://192.168.x.x)    │
│                        │                       │                        │
│  Creates SDP Offer     │ ─── SDP OFFER ──────► │  Applies Remote Offer  │
│  Applies Remote Answer │ ◄── SDP ANSWER ────── │  Creates SDP Answer    │
│                        │                       │                        │
│  Local Host ICE        │ ◄── ICE CANDIDATE ──► │  Local Host ICE        │
└───────────┬────────────┘                       └───────────┬────────────┘
            │                                                ▲
            ▼                                                │
    ═════════════════════════════════════════════════════════════════
     DIRECT WebRTC RTP STREAM (UDP) — Camera2 720p@30 + Opus Audio
     * No Internet * No Cloud * No STUN/TURN * Latency < 100ms *
    ═════════════════════════════════════════════════════════════════
```

---

## 1. Milestones Implemented (M0 – M4)

| Milestone | Scope Implemented | Key Classes |
|---|---|---|
| **M0 — Foundation & WebRTC Native Init** | Single `:app` module, SDK 37 / AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.4.21, native `PeerConnectionFactory` + `EglBase` + `JavaAudioDeviceModule` behind `MediaEngine` interface | `MediaEngine.kt`, `WebRtcFactory.kt` |
| **M1 — Wi-Fi Binding & Local Signaling** | Conditional Android 17 (`API 37+`) `ACCESS_LOCAL_NETWORK` permission flow, `ConnectivityManager.bindProcessToNetwork`, Ktor CIO WebSocket server/client (`/signal` on port `8765`), join approval (`HELLO`, `JOIN_REQUEST`, `JOIN_ACCEPT`, `JOIN_REJECT`), and `PING`/`PONG` RTT telemetry | `LocalNetworkPermissionHelper.kt`, `WifiNetworkController.kt`, `SignalCodec.kt`, `LocalSignalingServer.kt`, `SignalingClient.kt` |
| **M2 — WebRTC Offer/Answer & Local ICE** | Direct peer-to-peer WebRTC connection using `PeerConnection.RTCConfiguration(emptyList())` (no STUN/TURN), SDP Offer/Answer exchange, and asynchronous FIFO queueing of remote ICE candidates arriving before `setRemoteDescription` | `PeerSession.kt`, `RtcObserver.kt` (`IceCandidateQueue`) |
| **M3 — Camera & Microphone Streaming** | Camera2 capture (`720p@30fps` with automatic `480p@30fps` fallback), front/rear camera switching, hardware AEC/NS microphone capture, speakerphone audio routing, `TRACK_STATE` mute sync, and Compose `AndroidView` hosting `SurfaceViewRenderer` | `CameraMicCapture.kt`, `PocScreen.kt` |
| **M4 — Foreground Service & Notification** | `StreamingForegroundService` (`camera\|microphone` FGS types) started from visible Activity after permission grant, ongoing notification with **Stop Sharing** action button, and full resource release on stop | `StreamingForegroundService.kt`, `StreamingNotification.kt`, `SessionRuntimeCoordinator.kt` |

---

## 2. Project Structure

```text
LocalCast/
├── .github/workflows/
│   └── android-build.yml                  # Automatic APK build + release on push to main
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/
│       │   │   ├── drawable/ic_launcher.xml
│       │   │   └── values/{strings.xml, themes.xml}
│       │   └── java/com/localcast/poc/
│       │       ├── MainActivity.kt
│       │       ├── ui/
│       │       │   ├── PocScreen.kt
│       │       │   └── PocViewModel.kt
│       │       ├── model/
│       │       │   ├── DeviceInfo.kt
│       │       │   └── SessionState.kt
│       │       ├── network/
│       │       │   ├── LocalNetworkPermissionHelper.kt
│       │       │   └── WifiNetworkController.kt
│       │       ├── signaling/
│       │       │   ├── SignalMessage.kt
│       │       │   ├── SignalCodec.kt
│       │       │   ├── SignalingTransport.kt
│       │       │   ├── LocalSignalingServer.kt
│       │       │   └── SignalingClient.kt
│       │       ├── rtc/
│       │       │   ├── MediaEngine.kt
│       │       │   ├── WebRtcFactory.kt
│       │       │   ├── PeerSession.kt
│       │       │   └── RtcObserver.kt
│       │       ├── capture/
│       │       │   └── CameraMicCapture.kt
│       │       └── service/
│       │           ├── SessionRuntimeCoordinator.kt
│       │           ├── StreamingForegroundService.kt
│       │           └── StreamingNotification.kt
│       └── test/java/com/localcast/poc/
│           ├── rtc/IceCandidateQueueTest.kt
│           └── signaling/SignalCodecTest.kt
├── docs/
│   └── ADR-0001-poc-m0-to-m4-architecture.md
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

---

## 3. Push to `main` for Automated APK Build (No Manual Tag Required)

```bash
unzip LocalCast-POC-M0-to-M4-Android-SDK37.zip -d LocalCast
cd LocalCast
git init -b main
git add .
git commit -m "feat: LocalCast POC v0.1 (M0-M4) on SDK 37 & AGP 9.1.1"
git remote add origin <YOUR_GITHUB_REPO_URL>
git push -u origin main
```

Every push to `main` triggers `.github/workflows/android-build.yml`, which:
1. Installs JDK 17 and the Android 17 (`platforms;android-37.0` / `platforms;android-37`) SDK.
2. Runs unit tests (`gradle :app:testDebugUnitTest`) covering `SignalCodec`, multiline SDP serialization, `IceCandidateQueue`, and the conditional API 37 `ACCESS_LOCAL_NETWORK` rule.
3. Builds `LocalCast-poc-v0.1.0-debug.apk` (`gradle :app:assembleDebug`).
4. Uploads the APK as a workflow artifact **and** publishes/updates the `main-latest` release under your repository's **Releases** tab automatically.

---

## 4. Two-Device End-to-End Testing Guide (M0 – M4)

Install `LocalCast-poc-v0.1.0-debug.apk` on **Phone A (Publisher / Host)** and **Phone B (Receiver / Client)** connected to the **same Wi-Fi network** (internet/WAN can be unplugged).

1. **Launch on Both Phones (M0 Verification):**
   - Confirm the **M0–M4 Infrastructure Telemetry** card shows `M0 WebRTC: OK (<abi>, LocalICE=true)`.
2. **Start Hosting & Share on Phone A (M1 + M3 + M4):**
   - On Phone A, tap **Start Hosting & Share Camera/Mic (Phone A)**.
   - Approve the Camera, Microphone, Notification (API 33+), and Local Network (API 37+) runtime permissions.
   - Phone A binds to Wi-Fi, starts `StreamingForegroundService` (with an ongoing notification), opens the local camera preview at `720p @ 30fps` (or `480p @ 30fps` fallback), and displays its **Wi-Fi IP address** (e.g., `192.168.1.10`) and 6-digit **Join Code**.
3. **Connect from Phone B (M1):**
   - On Phone B, enter Phone A's Wi-Fi IP address (and optional 6-digit Join Code) and tap **Connect to Host (Phone B)**.
4. **Approve & Negotiate Direct WebRTC Stream (M2 + M3):**
   - On Phone A, tap **Approve & Stream** on the incoming join request card.
   - Phone A sends `JOIN_ACCEPT` followed by a WebRTC SDP `OFFER`.
   - Phone B replies with an SDP `ANSWER`, both phones exchange local `host` ICE candidates, and **Phone B displays Phone A's live camera video and plays Phone A's microphone audio**.
5. **Verify Track Controls & Background Operation (M3 + M4):**
   - On Phone A, test **Mute Mic**, **Pause Cam**, and **Flip Cam** (Front/Rear); verify Phone B's viewport reflects the live stream and track mute state.
   - Press the Android **Home** button on Phone A to minimize LocalCast: verify the stream continues uninterrupted on Phone B while Phone A displays the ongoing LocalCast notification and Android camera/microphone privacy indicator.
6. **Stop Session (M4 Teardown):**
   - Pull down the notification shade on Phone A and tap **Stop Sharing** (or tap **Stop Session** inside the app) to cleanly release the camera, microphone, WebRTC peer connection, Ktor server, and Wi-Fi network binding.
