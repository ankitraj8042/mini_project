# Signaling Server

WebSocket signaling server for WebRTC call establishment.

## Features

- User presence tracking
- WebRTC signaling (offer/answer/ICE candidates)
- In-call messaging (emoji reactions, chat)
- TURN credential generation with short-lived tokens
- TLS/WSS support for secure connections

## Quick Start (Development)

```bash
cd signaling-server
npm install
node server.js
```

Server runs on `ws://0.0.0.0:3000`

## Production Setup with TLS

### 1. Generate Self-Signed Certificates (Development)

```bash
mkdir -p certs
openssl req -x509 -newkey rsa:4096 -keyout certs/server.key -out certs/server.crt -days 365 -nodes -subj "/CN=localhost"
```

### 2. Use Let's Encrypt (Production)

```bash
# Install certbot
sudo apt install certbot

# Generate certificate
sudo certbot certonly --standalone -d your-domain.com

# Certificates will be at:
# /etc/letsencrypt/live/your-domain.com/fullchain.pem
# /etc/letsencrypt/live/your-domain.com/privkey.pem
```

### 3. Run with TLS

```bash
# Using environment variables
USE_TLS=true \
CERT_PATH=/path/to/server.crt \
KEY_PATH=/path/to/server.key \
PORT=443 \
node server.js
```

Server runs on `wss://0.0.0.0:443`

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 3000 | Server port |
| `USE_TLS` | false | Enable TLS/WSS |
| `CERT_PATH` | ./certs/server.crt | Path to SSL certificate |
| `KEY_PATH` | ./certs/server.key | Path to SSL private key |
| `TURN_SECRET` | your-turn-secret-here | HMAC secret for TURN credentials |

## TURN Credential Generation

The server generates time-limited TURN credentials compatible with coturn's REST API.

### Request Credentials

Send WebSocket message:
```json
{"type": "getTurnCredentials"}
```

Response:
```json
{
  "type": "turnCredentials",
  "username": "1234567890:user123",
  "password": "base64encodedhmac",
  "ttl": 3600,
  "uris": ["turn:server:80", "turn:server:443"]
}
```

### Configure coturn

```bash
# /etc/turnserver.conf
use-auth-secret
static-auth-secret=your-turn-secret-here
realm=your-domain.com
```

## Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `join` | Client→Server | Register user |
| `getUsers` | Client→Server | Request user list |
| `userList` | Server→Client | List of online users |
| `offer` | Client→Client | SDP offer |
| `answer` | Client→Client | SDP answer |
| `candidate` | Client→Client | ICE candidate |
| `reject` | Client→Client | Call rejection |
| `hangup` | Client→Client | End call |
| `emoji` | Client→Client | Emoji reaction |
| `chat` | Client→Client | Chat message |
| `getTurnCredentials` | Client→Server | Request TURN creds |
| `turnCredentials` | Server→Client | TURN credentials |

## Docker Deployment

```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --production
COPY server.js ./
EXPOSE 3000 443
CMD ["node", "server.js"]
```

```bash
docker build -t signaling-server .
docker run -d -p 3000:3000 \
  -e USE_TLS=false \
  signaling-server
```

## Nginx Reverse Proxy (Recommended for Production)

```nginx
upstream signaling {
    server 127.0.0.1:3000;
}

server {
    listen 443 ssl http2;
    server_name signal.your-domain.com;
    
    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    
    location / {
        proxy_pass http://signaling;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400;
    }
}
```
