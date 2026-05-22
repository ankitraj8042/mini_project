# Echoo - Adaptive WebRTC Calling App

Echoo is a Kotlin Android application for real-time peer-to-peer audio and video calling. It uses WebRTC for media transport, a Node.js signaling server for session setup, and MongoDB for OTP authentication, contacts, call history, and call-quality analytics.

## Project Status

This repository is complete enough for an academic mini-project/demo and resume portfolio entry. The Android app, signaling server, authentication flow, contacts, call history, in-call chat/emoji, and network-quality monitoring features are implemented.

It is not production-ready yet. Before public deployment, add a production TURN service, a real SMS OTP provider, TLS/WSS certificates, CI builds, and a documented two-device end-to-end test report.

## Key Features

- Real-time peer-to-peer audio and video calls using WebRTC.
- Phone-number OTP login backed by MongoDB.
- WebSocket signaling for SDP offers, answers, ICE candidates, presence, call events, chat, and emoji messages.
- Contacts list, online user tracking, recent call history, missed/rejected/completed call states.
- In-call controls for mute, speaker, camera toggle, camera switch, video/audio mode, hangup, chat, and emoji reactions.
- Live call telemetry for bitrate, packet loss, RTT, network type, data usage, and quality status.
- Adaptive quality handling that changes video bitrate/resolution and suggests audio-only mode on poor networks.
- Kotlin quality-prediction module with unit tests for 2G, 3G, 4G, WiFi, edge cases, and profile transitions.
- Node.js backend APIs for authentication, profile, contacts, call history, call stats, health checks, and TURN credential delivery.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Android | Kotlin, XML layouts, AndroidX, Material Components |
| Real-time media | WebRTC via local `libwebrtc.aar` |
| Signaling | OkHttp WebSocket client, Node.js `ws` server |
| Backend API | Node.js, Express.js |
| Database | MongoDB Atlas or compatible MongoDB instance |
| Testing | JUnit for Android logic, Node syntax smoke test |

## Architecture

```text
Android app
  |-- HTTP REST --> Node/Express API --> MongoDB
  |-- WebSocket --> Node signaling server
  |                    |-- user presence
  |                    |-- offer/answer/ICE relay
  |                    |-- call events, chat, emoji, stats
  |
  |-- WebRTC media path --> peer Android device
```

The signaling server does not carry the audio/video stream. It only coordinates connection setup and call events. Media flows between devices through WebRTC, using STUN/TURN when required by the network.

## Project Structure

```text
mini_project/
|-- app/                         Android application
|   |-- libs/libwebrtc.aar        Local WebRTC dependency
|   |-- src/main/java/.../ui      Login, dial, call, stats screens
|   |-- src/main/java/.../signaling
|   |-- src/main/java/.../api
|   |-- src/main/java/.../auth
|   |-- src/main/java/.../ai      Adaptive quality and network profile logic
|   |-- src/main/java/.../turn
|   `-- src/test                 Unit tests
|-- signaling-server/            Node.js backend and WebSocket server
|-- gradle/                      Gradle wrapper and version catalog
`-- README.md
```

## Prerequisites

- Android Studio with JDK 17.
- Android SDK 34 installed.
- Android device or emulator. Two physical devices on the same network are best for call testing.
- Node.js 18 or newer.
- MongoDB Atlas or local MongoDB for auth/history/stat persistence.

## Backend Setup

From the project root:

```powershell
cd signaling-server
npm.cmd install
```

For your own MongoDB/TURN configuration, set environment variables before starting the server:

```powershell
$env:MONGODB_URI="mongodb+srv://<username>:<password>@<cluster>.mongodb.net/?appName=Echoo"
$env:TURN_SECRET="<turn-secret-for-production>"
```

Start the backend:

```powershell
npm.cmd start
```

The local server listens on:

- REST: `http://0.0.0.0:3000`
- WebSocket: `ws://0.0.0.0:3000`

## Android Setup

1. Open the project root in Android Studio.
2. Sync Gradle.
3. Start the signaling server.
4. Connect a device or start an emulator.
5. Configure the server IP from the app's server settings, or update `DEFAULT_SERVER_IP` in `app/src/main/java/com/yourapp/webrtcapp/utils/Constants.kt`.
6. Build and run the `app` module.

For emulator-to-host testing, use `ws://10.0.2.2:3000`. For physical devices, use your computer's local LAN IP and keep both phones on the same network.

## Validation Commands

Backend smoke test:

```powershell
node --check signaling-server/server.js
```

Android unit tests:

```powershell
.\gradlew.bat test
```

Android debug build:

```powershell
.\gradlew.bat assembleDebug
```

If Gradle reports that `JAVA_HOME` is not set, install/configure JDK 17 or use the JDK bundled with Android Studio.

## Current Limitations

- OTP is development-friendly: the backend returns `devOtp` unless `NODE_ENV=production`.
- Production SMS delivery is not integrated yet.
- Public OpenRelay TURN settings are suitable for demos, not production reliability.
- Production credentials should be supplied through environment variables and never committed to the repository.
- The Android package name still uses the original sample namespace `com.yourapp.webrtcapp`.
- Full WebRTC behavior must be verified on two real devices because local unit tests cannot validate camera, microphone, NAT traversal, or peer media quality.

## Resume Summary

Echoo is a real-time Android communication app built with Kotlin, WebRTC, Node.js, WebSockets, and MongoDB. It demonstrates peer-to-peer audio/video calling, OTP authentication, contact management, call history, live network telemetry, and adaptive media-quality handling for low-bandwidth networks.

Suggested resume bullets:

- Built a Kotlin Android WebRTC calling app with peer-to-peer audio/video calls, WebSocket signaling, OTP login, contacts, call history, and in-call chat/emoji interactions.
- Developed a Node.js/Express signaling backend with MongoDB persistence for users, contacts, call records, call-quality samples, and real-time presence.
- Implemented live call telemetry and adaptive media controls using bitrate, packet loss, RTT, network type, and data usage to adjust video quality and recommend audio-only mode.
- Added a Kotlin network-quality prediction module with JUnit coverage for network profiles, edge cases, and low-bandwidth scenarios.

## Author

Ankit Raj

## License

Educational mini-project/demo.
