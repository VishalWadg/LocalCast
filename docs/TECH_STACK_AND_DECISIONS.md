# LocalCast Tech Stack & Architectural Decision Records (ADRs)

This document provides a comprehensive analysis of the technologies selected for **LocalCast** and the engineering trade-offs behind each architectural decision.

If you are explaining this project in a code review, technical presentation, or architecture interview, this document gives you the exact rationale and trade-off justification for every layer of the system.

---

## 1. Complete Tech Stack Overview

| Category | Technology | Version | Primary Purpose | Why Chosen |
|---|---|---|---|---|
| **Language** | Kotlin | `2.4.21` | Application language | Null-safety, coroutines, structured concurrency, and first-class Compose support |
| **UI Framework** | Jetpack Compose | `1.12.1` | Modern declarative UI | Reactive state management, seamless animations, zero XML boilerplate |
| **Design System** | Material 3 | `1.4.0` | Component styling | Modern Android design language, dark/light theme tokens |
| **Real-Time Media** | WebRTC Android Native | `150.7871.04` | Audio/video streaming | C++ hardware-accelerated H.264/VP8/Opus encoding with sub-100ms latency |
| **Signaling Engine** | Ktor Server & Client | `3.6.1` | Local WebSocket framing | Lightweight asynchronous CIO (Coroutine I/O) engine running on-device |
| **Camera Framework** | Android Camera2 API | Native | Camera sensor capture | Low-level control of 720p/480p streams and direct zero-copy GPU texture binding |
| **Target SDK** | Android 17 (Vanilla Ice Cream / Baklava) | `API 37` | Target platform | Access to latest Android network privacy APIs while supporting minSdk 29 |
| **Build Toolchain** | AGP & Gradle | AGP `9.1.1` / Gradle `9.3.1` | Build orchestration | Native JDK 17 support, fast incremental builds, modern dependency management |
| **Continuous Integration**| GitHub Actions | Ubuntu Latest | Automated CI/CD | Headless testing, APK compilation, artifact staging, and Telegram delivery |

---

## 2. Architectural Decision Records (ADRs)

### ADR-1: Native WebRTC C++ vs. Raw WebSocket / HTTP Streaming

* **Context:** We needed to stream 720p live camera video and microphone audio between two Android devices over local Wi-Fi.
* **Alternative Considered:** Streaming raw JPEG frames (MJPEG) or chunked MP4/WebM byte streams over HTTP or WebSockets.
* **The Decision:** Use the native WebRTC C++ SDK (`io.github.webrtc-sdk:android`).
* **Why This Matters:**
  - **Latency:** WebRTC uses UDP-based RTP/SRTP instead of TCP. If a Wi-Fi packet drops, WebRTC drops the late frame rather than stalling the stream with TCP retransmissions, guaranteeing **sub-100ms real-time latency**.
  - **Hardware Codecs:** WebRTC taps into Android's native `MediaCodec` hardware encoders (H.264, VP8) and Opus audio codec with zero CPU overhead.
  - **Adaptive Jitter Buffer:** WebRTC dynamically adjusts buffer sizes to compensate for local Wi-Fi packet jitter automatically.

---

### ADR-2: Embedded On-Device Ktor CIO vs. Cloud Signaling (Firebase / MQTT)

* **Context:** Before WebRTC can establish a direct P2P link, devices must exchange connection metadata (SDP Offer/Answer and ICE candidates).
* **Alternative Considered:** Cloud signaling services like Firebase Realtime Database, Firestore, Supabase, or AWS IoT MQTT.
* **The Decision:** Run an embedded Ktor CIO WebSocket server directly on Phone A (`ws://<Local_IP>:8765/signal`), with Phone B connecting as a Ktor WebSocket client.
* **Why This Matters:**
  - **100% Offline Capability:** Cloud signaling requires active internet connectivity. With an on-device Ktor server, LocalCast functions seamlessly on isolated portable travel routers, camping hotspots, or disaster relief mesh networks without any WAN/internet connection.
  - **Zero Cloud Costs:** No cloud database quotas, egress bandwidth charges, or authentication infrastructure needed.
  - **Privacy:** Signaling messages never leave the local physical Wi-Fi network.

---

### ADR-3: Strict Zero-STUN/TURN Design (`RTCConfiguration(emptyList())`)

* **Context:** Standard WebRTC implementations configure STUN (`stun:stun.l.google.com:19302`) and TURN relay servers to punch through NAT firewalls over the public internet.
* **Alternative Considered:** Leaving Google's public STUN servers in the `RTCConfiguration`.
* **The Decision:** Pass `RTCConfiguration(emptyList())`—explicitly zero STUN or TURN servers.
* **Why This Matters:**
  - **Fast Offline Convergence:** When an Android phone has no internet access, attempting to resolve external STUN domain names causes DNS lookup delays and socket timeouts that freeze WebRTC negotiation for 10–30 seconds.
  - **Deterministic Local ICE:** With no STUN/TURN, WebRTC gathers **only local `host` ICE candidates** (e.g., `192.168.1.10:50000`). Local connection negotiation completes in milliseconds.

---

### ADR-4: Android Camera2 directly vs. CameraX

* **Context:** LocalCast captures frames from the phone's camera sensor to supply to WebRTC's video track.
* **Alternative Considered:** Android Jetpack's high-level `CameraX` library.
* **The Decision:** Use WebRTC's native `Camera2Enumerator` with Android's low-level `Camera2` API and `SurfaceTextureHelper`.
* **Why This Matters:**
  - **Zero-Copy GPU Textures:** `Camera2Enumerator` pairs directly with WebRTC's C++ `SurfaceTextureHelper`. Camera frames stay in GPU texture memory and pass straight to the hardware H.264 encoder without copying pixel buffers through Java/Kotlin heap memory.
  - **Automatic Resolution Fallback:** Camera2 exposes hardware characteristics directly, allowing LocalCast to gracefully fall back from 720p@30fps to 480p@30fps if the physical sensor rejects the target resolution.

---

### ADR-5: Pure-Kotlin `SignalCodec` vs. Reflection JSON Libraries (Gson / Jackson)

* **Context:** Signaling messages must be serialized to JSON text to travel over WebSocket frames.
* **Alternative Considered:** Jackson, Gson, or Moshi.
* **The Decision:** Implement a handcrafted pure-Kotlin tokenizer and serializer in [`SignalCodec.kt`](file:///d:/LocalCast/app/src/main/java/com/localcast/poc/signaling/SignalCodec.kt).
* **Why This Matters:**
  - **Multiline SDP String Safety:** WebRTC SDP offers and answers are multiline strings containing literal carriage-return line feeds (`\r\n`). Standard reflection parsers frequently mangle newlines or strip whitespace.
  - **Zero Reflection Overhead:** Reflection inspects class metadata at runtime, causing Android classloader overhead and GC pressure. Handcrafted parsing executes in microseconds with predictable heap allocations.
  - **Zero Binary Footprint:** Eliminates 1–2 MB of external JSON parsing library dependencies.

---

### ADR-6: Foreground Service (`camera|microphone`) vs. Background Thread

* **Context:** When the publisher minimizes the app to look at notes or check another app, video streaming must continue uninterrupted.
* **Alternative Considered:** Relying on Kotlin Coroutines or background threads inside `MainActivity`.
* **The Decision:** Implement `StreamingForegroundService` with `android:foregroundServiceType="camera|microphone"` and an ongoing notification.
* **Why This Matters:**
  - **Android 14+ Privacy Compliance:** Starting in Android 14 (API 34), any app accessing the camera or microphone while not visible in the foreground is instantly killed by the OS unless running a declared Foreground Service with explicit `camera` and `microphone` types.
  - **User Transparency:** The ongoing notification displays real-time telemetry (frames streamed, RTT latency) and provides an immediate **Stop Sharing** button so the user is always in control of their camera.

---

### ADR-7: Conditional Android 17 (API 37) `ACCESS_LOCAL_NETWORK` Handling

* **Context:** Android 17 introduced a new runtime permission requirement: `android.permission.ACCESS_LOCAL_NETWORK`.
* **Alternative Considered:** Requesting the permission unconditionally on all Android versions.
* **The Decision:** Use `LocalNetworkPermissionHelper` to check `Build.VERSION.SDK_INT >= 37` before requesting `ACCESS_LOCAL_NETWORK`.
* **Why This Matters:**
  - On Android 10 through 16 (API 29–36), requesting an unrecognized permission can cause system dialog confusion or no-op failures. LocalCast conditionally prompts for local network access only on devices running Android 17+, maintaining backward compatibility across all modern Android versions.

---

### ADR-8: Telegram CI/CD Direct Binary Delivery & Packaging Optimization (<50 MB Limit)

* **Context:** The GitHub Actions pipeline compiles `LocalCast-poc-v0.1.0-debug.apk` and delivers the deliverable binary directly to the Telegram group via the Telegram Bot API (`api.telegram.org`).
* **Problem:** Telegram Bot API enforces a hard **50 MB (50,000,000 bytes)** ceiling on direct `sendDocument` binary uploads. Uncompressed native WebRTC binaries across 4 CPU architectures previously ballooned the fat debug APK to ~81.67 MB, which previously triggered a text-only `sendMessage` fallback.
* **The Decision:**
  1. **Packaging Optimization:**
     - Configure `packaging.jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts` to enable DEFLATE compression on native libraries.
     - Restrict packaged ABIs via `defaultConfig.ndk.abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))` to physical ARM mobile devices, excluding unneeded desktop emulator binaries (`x86`, `x86_64`).
     - This reduces the compiled debug APK from ~81.67 MB to ~20.5–22 MB, providing >28 MB (>55%) of safety headroom below the 50 MB Telegram ceiling.
  2. **Direct Delivery Pipeline:**
     - Remove the `sendMessage` fallback branch completely. Delivery is guaranteed to be direct binary upload via `sendDocument`.
     - In `.github/workflows/android-build.yml`, enforce an explicit pre-flight check asserting the staged APK exists and its byte size is strictly $\le$ 50,000,000 bytes (failing with `exit 1` immediately if exceeded).
     - Upload via `curl` with retries/timeouts (`--retry 3 --retry-delay 5 --connect-timeout 30 --max-time 300`) and strictly validate that the Telegram API response contains `"ok":true`.
* **Why This Matters:**
  - Guarantees testers receive the installable APK binary directly inside the Telegram group on every successful CI build without external link navigation, while maintaining a strict fail-fast CI guardrail against artifact size regressions.
