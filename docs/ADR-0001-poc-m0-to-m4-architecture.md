# ADR-0001: LocalCast POC v0.1 (Milestones M0–M4) Architecture & Toolchain

**Date:** 2026-11-04  
**Status:** Accepted  

## Context

This codebase implements the complete **POC v0.1 (Milestones M0 through M4)** vertical slice defined in the LocalCast POC Engineering Specification v0.3 while preserving the domain and adapter boundaries required by the **LocalCast Complete Product Scope v1.0**.

## Key Architectural Decisions

1. **MediaEngine Interface Boundary (`rtc/MediaEngine.kt` -> `rtc/WebRtcFactory.kt`)**
   - `io.github.webrtc-sdk:android:150.7871.04` is isolated behind `MediaEngine`.
   - `PeerSession` (`rtc/PeerSession.kt`) configures `PeerConnection.RTCConfiguration(emptyList())` with Unified Plan semantics so zero STUN/TURN servers are used.
   - `IceCandidateQueue` (`rtc/RtcObserver.kt`) queues remote trickled ICE candidates that arrive before `setRemoteDescription` completes and flushes them in FIFO order immediately after the remote SDP is applied.

2. **Camera2 & Microphone Capture Pipeline (`capture/CameraMicCapture.kt`)**
   - Uses `Camera2Enumerator` and `SurfaceTextureHelper` to capture at **720p @ 30 FPS** (`1280x720@30`), automatically falling back to **480p @ 30 FPS** (`640x480@30`) if the hardware camera rejects 720p.
   - Supports live front/rear camera flipping (`switchCamera`) and independent camera/microphone mute controls (`TRACK_STATE` synchronized over WebSocket).
   - Video rendering uses WebRTC's `SurfaceViewRenderer` embedded in Jetpack Compose via `AndroidView` with automatic lifecycle cleanup in `DisposableEffect`.

3. **Foreground Service Ownership (`service/StreamingForegroundService.kt` & `SessionRuntimeCoordinator.kt`)**
   - `SessionRuntimeCoordinator` owns the active WebRTC, Camera/Mic, Wi-Fi binding, and Ktor WebSocket state outside `MainActivity`.
   - `StreamingForegroundService` is started from a visible `MainActivity` after `CAMERA` and `RECORD_AUDIO` runtime permissions are granted, displaying an ongoing notification (`StreamingNotification`) with a **Stop Sharing** action button.

4. **Conditional Android 17 (API 37) `ACCESS_LOCAL_NETWORK` Enforcement**
   - `LocalNetworkPermissionHelper` requests `android.permission.ACCESS_LOCAL_NETWORK` at runtime only when `Build.VERSION.SDK_INT >= 37`, avoiding unnecessary runtime prompts on Android 10–16 (`API 29–36`).
