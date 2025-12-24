const WebSocket = require("ws");

const wss = new WebSocket.Server({ port: 3000 });
const clients = new Map(); // userId -> ws

function broadcastUserList() {
  const userList = Array.from(clients.keys());
  const message = JSON.stringify({ type: "userList", users: userList });
  console.log(`Broadcasting user list: ${JSON.stringify(userList)}`);
  console.log(`Message being sent: ${message}`);
  clients.forEach((ws, userId) => {
    if (ws.readyState === WebSocket.OPEN) {
      console.log(`  -> Sending to ${userId}: ${message}`);
      ws.send(message);
    }
  });
}

function sendTo(userId, message) {
  const ws = clients.get(userId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
    return true;
  } else {
    console.log(`User ${userId} not found or disconnected`);
    return false;
  }
}

wss.on("connection", (ws) => {
  let currentUserId = null;

  ws.on("message", (message) => {
    let data;
    try {
      data = JSON.parse(message.toString());
    } catch (e) {
      console.error("Invalid JSON:", message.toString());
      return;
    }

    console.log("Received:", data.type, "from:", data.from || data.userId || currentUserId);

    switch (data.type) {
      case "join":
        // Remove old connection if exists
        if (clients.has(data.userId)) {
          clients.get(data.userId).close();
        }
        currentUserId = data.userId;
        clients.set(data.userId, ws);
        console.log(`User joined: ${data.userId}`);
        console.log(`Active users: ${Array.from(clients.keys()).join(", ")}`);
        
        // Send user list to all clients
        broadcastUserList();
        break;

      case "offer":
        console.log(`Forwarding offer from ${data.from} to ${data.to} (video: ${data.isVideoCall})`);
        sendTo(data.to, {
          type: "offer",
          from: data.from,
          to: data.to,
          sdp: data.sdp,
          isVideoCall: data.isVideoCall !== false  // default to true
        });
        break;

      case "answer":
        console.log(`Forwarding answer from ${data.from} to ${data.to}`);
        sendTo(data.to, {
          type: "answer",
          from: data.from,
          to: data.to,
          sdp: data.sdp
        });
        break;

      case "candidate":
        console.log(`Forwarding ICE candidate from ${data.from} to ${data.to}`);
        sendTo(data.to, {
          type: "candidate",
          from: data.from,
          to: data.to,
          candidate: data.candidate
        });
        break;

      case "reject":
        console.log(`Call rejected by ${data.from}`);
        sendTo(data.to, {
          type: "reject",
          from: data.from,
          to: data.to
        });
        break;

      case "hangup":
        console.log(`Call ended by ${data.from}`);
        sendTo(data.to, {
          type: "hangup",
          from: data.from,
          to: data.to
        });
        break;
        
      case "emoji":
        console.log(`========= EMOJI =========`);
        console.log(`From: ${data.from}`);
        console.log(`To: ${data.to}`);
        console.log(`Emoji: ${data.emoji}`);
        console.log(`Target user exists: ${clients.has(data.to)}`);
        const emojiSent = sendTo(data.to, {
          type: "emoji",
          from: data.from,
          to: data.to,
          emoji: data.emoji
        });
        console.log(`Emoji forwarded: ${emojiSent ? 'SUCCESS' : 'FAILED'}`);
        console.log(`=========================`);
        break;
        
      case "checkUser":
        // Check if a specific user is online
        const targetUser = data.userId;
        const isOnline = clients.has(targetUser);
        console.log(`Checking user ${targetUser}: ${isOnline ? "online" : "offline"}`);
        ws.send(JSON.stringify({
          type: "userStatus",
          userId: targetUser,
          online: isOnline
        }));
        break;

      case "getUsers":
        // Send current user list to requesting client
        ws.send(JSON.stringify({
          type: "userList",
          users: Array.from(clients.keys())
        }));
        break;
    }
  });

  ws.on("close", () => {
    if (currentUserId) {
      clients.delete(currentUserId);
      console.log(`User left: ${currentUserId}`);
      console.log(`Active users: ${Array.from(clients.keys()).join(", ")}`);
      broadcastUserList();
    }
  });

  ws.on("error", (error) => {
    console.error("WebSocket error:", error);
  });
});

console.log("=================================");
console.log("Signaling server running on port 3000");
console.log("=================================");
