# Echoo - Signaling Server

Backend signaling server for the **Echoo** WebRTC video calling Android application. Handles real-time communication, user authentication, call history, and WebRTC session management.

## Overview

This Node.js server provides:
- **WebSocket Signaling** – Facilitates WebRTC peer connection (offer/answer/ICE exchange)
- **User Management** – Registration, login, and online presence tracking via MongoDB
- **Call History** – Stores call logs with duration, quality metrics, and timestamps
- **Real-time Statistics** – Collects and stores call quality data (bitrate, packet loss, RTT)
- **REST API** – User profiles, contacts, and call statistics endpoints

## Tech Stack

| Component | Technology |
|-----------|------------|
| Runtime | Node.js |
| WebSocket | ws |
| HTTP Server | Express.js |
| Database | MongoDB Atlas |
| Protocol | WebRTC Signaling |

## Quick Start

### Prerequisites
- Node.js v18+
- MongoDB Atlas account (or local MongoDB instance)

### Installation

```bash
cd signaling-server
npm install
```

### Configuration

Update the MongoDB connection string in `server.js`:

```javascript
const MONGODB_URI = "mongodb+srv://<username>:<password>@<cluster>.mongodb.net/?appName=<app>";
```

### Run the Server

```bash
npm start
```

Server runs on `ws://0.0.0.0:3000`

## API Endpoints

### Authentication
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register or login user |

### Users
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/users/:phone` | Get user profile |
| PUT | `/api/users/:phone` | Update user profile |

### Call History
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/calls/:phone` | Get call history |
| POST | `/api/calls` | Save call record |

### Statistics
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Server health check |

## WebSocket Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `join` | Client → Server | Register user with phone number |
| `offer` | Client ↔ Client | WebRTC SDP offer |
| `answer` | Client ↔ Client | WebRTC SDP answer |
| `candidate` | Client ↔ Client | ICE candidate exchange |
| `reject` | Client ↔ Client | Decline incoming call |
| `hangup` | Client ↔ Client | End active call |
| `busy` | Server → Client | Callee is on another call |

## Project Structure

```
signaling-server/
├── server.js        # Main server with WebSocket & REST API
├── package.json     # Dependencies and scripts
└── README.md        # Documentation
```

## Related

- **Android App**: Echoo video calling app (Kotlin + WebRTC)
- **Database**: MongoDB Atlas for user data and call history

## License

ISC

