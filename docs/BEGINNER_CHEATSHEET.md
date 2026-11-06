# LocalCast Beginner's Cheat Sheet & Presentation Guide

This guide is designed for developers, students, and beginners who want to master the concepts behind **LocalCast** and confidently explain the project to teammates, mentors, or job interviewers.

---

## 1. Quick Glossary: Plain-English Definitions

| Term | What It Sounds Like | What It Actually Means in LocalCast |
|---|---|---|
| **WebRTC** | Web Real-Time Communication | An open-source technology that lets two devices stream live audio and video directly to each other with ultra-low latency (under 100 milliseconds). |
| **Signaling** | Traffic lights / hand waving | The initial conversation between two devices (over WebSockets) where they introduce themselves, exchange IP addresses, and agree on video formats *before* the video starts. |
| **SDP** | Session Description Protocol | A plain-text "contract" describing what audio and video formats a device supports (e.g. *"I want to send 720p H.264 video and Opus audio"*). |
| **ICE Candidate** | A physical address slip | A note containing an IP address and port where a device can be reached (e.g. `192.168.1.10:50000`). |
| **Host Candidate** | Local home address | An ICE candidate that represents a device's actual private IP address on the local Wi-Fi router. LocalCast uses **only** host candidates. |
| **STUN / TURN** | Internet phonebooks & relays | Cloud servers used on the public internet to discover public IP addresses (STUN) or relay video data (TURN). **LocalCast completely omits these** because it operates strictly offline on local Wi-Fi. |
| **SurfaceViewRenderer**| A canvas on the screen | A specialized Android View from Google's WebRTC library that draws incoming video frames directly onto the device's GPU. |
| **EglBase** | An OpenGL engine room | A shared graphics context that lets the camera, video encoder, and screen renderer pass GPU textures to each other without copying pixels in CPU memory. |
| **Foreground Service** | A high-priority background worker | An Android background component with an ongoing notification that tells the operating system: *"Do not kill this app; it is actively using the camera and microphone for the user."* |

---

## 2. Top 10 Questions Beginners & Interviewers Ask

### Q1: "If WebRTC is peer-to-peer, why do we need a WebSocket signaling server at all?"
> **Answer:** WebRTC devices cannot find each other out of thin air. Before two devices can stream video directly, they need an initial communication channel to introduce themselves and exchange their local IP addresses and agreed video formats. Ktor's WebSocket server acts as this temporary "matchmaker." Once the WebRTC handshake completes, the video and audio stream directly peer-to-peer, bypassing the WebSocket server completely.

### Q2: "How does LocalCast stream video with zero internet access?"
> **Answer:** LocalCast connects both devices to the same local Wi-Fi router or mobile hotspot. The Ktor WebSocket signaling server runs locally on the host phone on port 8765 (`ws://192.168.x.x:8765`), and WebRTC is configured with an empty STUN/TURN server list (`RTCConfiguration(emptyList())`). All packets travel exclusively across the local Wi-Fi radio waves—no WAN, cloud, or external internet required.

### Q3: "What happens when the publisher minimizes the app to check messages?"
> **Answer:** Because LocalCast runs a declared Android `ForegroundService` with `camera` and `microphone` foreground service types, the Android OS keeps the camera sensor and streaming loop alive in the background. An ongoing notification remains in the notification shade, allowing the publisher to see live streaming telemetry and tap "Stop Sharing" at any time.

### Q4: "Why does WebRTC use UDP instead of TCP for video?"
> **Answer:** TCP guarantees delivery by retransmitting lost packets. In live video streaming, if packet #5 drops, TCP pauses the stream until packet #5 is resent, causing a noticeable freeze. WebRTC uses UDP (specifically RTP): if a packet drops, it skips it and renders the next available frame immediately, maintaining ultra-low sub-100ms latency.

### Q5: "Why did the Telegram bot reject our APK with error 413?"
> **Answer:** The official Telegram Bot API has a strict file upload limit of 50 MB. Because LocalCast bundles Google's precompiled WebRTC native C++ binaries (`.so` files) for all four major Android CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), the fat debug APK was ~81 MB. Our CI/CD pipeline intelligently detects when the APK exceeds 50 MB and delivers a rich HTML release card with a direct download button pointing to GitHub Releases.

### Q6: "Why write a custom `SignalCodec` instead of using Gson or Jackson?"
> **Answer:** WebRTC SDP strings contain multiline formatting with explicit carriage-return line feeds (`\r\n`). Standard reflection-based JSON libraries often strip or escape these characters inconsistently, breaking WebRTC's C++ SDP parser. A pure-Kotlin tokenizer has zero external dependencies, zero reflection performance overhead, and guarantees strict character preservation.

### Q7: "What is the difference between an SDP Offer and an SDP Answer?"
> **Answer:** An **SDP Offer** is created by the initiating device (Host), proposing what video/audio codecs and resolutions it can transmit. An **SDP Answer** is created by the receiving device, confirming which of those proposed formats it supports and accepts. Once both sides apply these descriptions, media negotiation is complete.

### Q8: "What does 'Process Binding to Wi-Fi' mean and why is it necessary?"
> **Answer:** If an Android phone is connected to a local Wi-Fi network that has no internet connection, Android's operating system often automatically routes network requests through the phone's cellular data connection instead. LocalCast calls `ConnectivityManager.bindProcessToNetwork(wifiNetwork)` to force all app traffic through the Wi-Fi interface, preventing local socket failures.

### Q9: "What is an `IceCandidateQueue` and why is it needed?"
> **Answer:** In WebRTC, ICE candidates are discovered asynchronously (trickled) while the SDP Offer/Answer negotiation is taking place. If an ICE candidate arrives before the native C++ engine finishes applying the remote SDP, calling `addIceCandidate()` throws an error. `IceCandidateQueue` holds candidates in a FIFO queue and flushes them the instant `setRemoteDescription` completes.

### Q10: "How does LocalCast handle camera hardware differences between budget and flagship phones?"
> **Answer:** LocalCast targets **720p @ 30 FPS** (`1280x720`). If an older or budget Android phone's camera sensor rejects 720p capture, `CameraMicCapture` automatically catches the exception and falls back to **480p @ 30 FPS** (`640x480`), ensuring the app never crashes on low-end hardware.

---

## 3. The 3-Minute Live Demonstration Script

When showing LocalCast to someone in person, follow this concise script:

### Step 1: Show the Offline Setup (30 Seconds)
- **What to say:** *"LocalCast is a completely offline, peer-to-peer Android video and audio streaming app. Notice both phones are on this local Wi-Fi router, but the router's internet cable is completely unplugged."*
- **What to show:** Open LocalCast on both phones. Point out the **M0 Infrastructure Telemetry** showing `WebRTC: OK (LocalICE=true)`.

### Step 2: Host & Pair (60 Seconds)
- **What to say:** *"On Phone A, I tap 'Start Hosting'. Phone A binds its sockets to Wi-Fi, launches an embedded Ktor WebSocket server on port 8765, starts a Camera/Mic Foreground Service, and displays its local IP: `192.168.1.10`."*
- **What to do:**
  1. On Phone A: Tap **Start Hosting & Share Camera/Mic**.
  2. On Phone B: Enter Phone A's IP and tap **Connect to Host**.
  3. On Phone A: An incoming join card pops up. Tap **Approve & Stream**.

### Step 3: Demonstrate Low Latency & Controls (60 Seconds)
- **What to say:** *"In less than a second, they exchanged SDP contracts and local ICE candidates. Phone B is now playing Phone A's 720p camera and microphone with sub-100ms latency."*
- **What to do:**
  - Wave your hand in front of Phone A to show the instantaneous response on Phone B.
  - Tap **Mute Mic** and **Pause Cam** on Phone A; show Phone B updating in real time.
  - Tap **Flip Cam** to switch smoothly between front and rear cameras.

### Step 4: Prove Background Resilience (30 Seconds)
- **What to say:** *"Watch what happens when I minimize the host app by pressing the Android Home button."*
- **What to do:**
  - Press the Android **Home** button on Phone A.
  - Show Phone B: the video stream continues without interruption!
  - Pull down Phone A's notification shade to show the ongoing LocalCast notification with the **Stop Sharing** button.
  - Tap **Stop Sharing** to cleanly release the camera, microphone, and WebRTC engine.
