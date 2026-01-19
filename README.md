# Echoo - WebRTC Video Calling App

A real-time video calling Android application built with WebRTC technology, featuring peer-to-peer communication, call quality monitoring, and a Node.js signaling server with MongoDB backend.

![Platform](https://img.shields.io/badge/Platform-Android-green)
![Language](https://img.shields.io/badge/Language-Kotlin-purple)
![WebRTC](https://img.shields.io/badge/WebRTC-Enabled-blue)

## Features

- **HD Video Calls** – Real-time peer-to-peer video communication using WebRTC
- **Audio Calls** – Voice-only calling option
- **Call History** – View past calls with duration and timestamps
- **Real-time Statistics** – Monitor call quality (bitrate, packet loss, latency)
- **User Authentication** – Phone number based login system
- **Online Presence** – See when contacts are available
- **In-call Controls** – Mute, camera toggle, speaker switch

## Tech Stack

### Android App
| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | XML Layouts |
| WebRTC | libwebrtc |
| Networking | OkHttp (WebSocket) |
| Architecture | MVVM |

### Backend Server
| Component | Technology |
|-----------|------------|
| Runtime | Node.js |
| Framework | Express.js |
| WebSocket | ws |
| Database | MongoDB Atlas |

## Project Structure

```
echoo/
├── app/                          # Android Application
│   ├── src/main/
│   │   ├── java/.../webrtcapp/
│   │   │   ├── ui/               # Activities (Login, Dial, Call, Stats)
│   │   │   ├── webrtc/           # WebRTC peer connection management
│   │   │   ├── signaling/        # WebSocket signaling client
│   │   │   ├── api/              # REST API client
│   │   │   ├── auth/             # Authentication manager
│   │   │   └── utils/            # Constants and helpers
│   │   └── res/                  # Layouts, drawables, values
│   └── build.gradle.kts
│
├── signaling-server/             # Node.js Backend
│   ├── server.js                 # WebSocket + REST API server
│   ├── package.json
│   └── README.md
│
└── README.md
```

## Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 24+ (Android 7.0)
- Node.js v18+
- MongoDB Atlas account

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/ankitraj8042/mini_project.git
cd mini_project
```

### 2. Setup Signaling Server

```bash
cd signaling-server
npm install
```

Update MongoDB connection string in `server.js`:
```javascript
const MONGODB_URI = "mongodb+srv://<username>:<password>@<cluster>.mongodb.net/";
```

Start the server:
```bash
npm start
```

Server runs on `ws://0.0.0.0:3000`

### 3. Configure Android App

Update server IP in `app/src/main/java/.../utils/Constants.kt`:
```kotlin
const val SERVER_IP = "YOUR_SERVER_IP"
const val SERVER_PORT = 3000
```

### 4. Build & Run

1. Open the project in Android Studio
2. Sync Gradle files
3. Connect an Android device or start an emulator
4. Run the app

## How It Works

```
┌─────────────┐         WebSocket          ┌─────────────────┐
│   Phone A   │◄────────────────────────►  │ Signaling Server │
│  (Caller)   │                            │   (Node.js)      │
└──────┬──────┘                            └────────┬────────┘
       │                                            │
       │  Peer-to-Peer (WebRTC)                     │
       │◄──────────────────────────────────────────►│
       │                                            │
┌──────┴──────┐         WebSocket          ┌───────┴───────┐
│   Phone B   │◄────────────────────────►  │    MongoDB    │
│  (Callee)   │                            │    Atlas      │
└─────────────┘                            └───────────────┘
```

1. **Signaling** – WebSocket server exchanges SDP offers/answers and ICE candidates
2. **Connection** – WebRTC establishes direct peer-to-peer media streams
3. **Media** – Video/audio flows directly between devices (no server relay)
4. **Storage** – User data and call history stored in MongoDB

## Screenshots

| Login | Dial Pad | Video Call |
|-------|----------|------------|
| Phone authentication | Enter number to call | Active video session |

## API Documentation

See [signaling-server/README.md](signaling-server/README.md) for detailed API endpoints and WebSocket message types.

## Testing

For local testing with two devices:

1. Run the signaling server on your computer
2. Connect both devices to the same network
3. Update `Constants.kt` with your computer's local IP
4. Install the app on both devices
5. Login with different phone numbers and call each other

**Tip:** Use ngrok for testing across different networks:
```bash
ngrok http 3000
```

## Authors

- Ankit Raj

## License

This project is for educational purposes (5th Semester Mini Project).

---

*Built with ❤️ using WebRTC and Kotlin*
