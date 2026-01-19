/**
 * Signaling Server for WebRTC with MongoDB Backend
 * 
 * Features:
 * - User authentication with OTP
 * - Call history storage
 * - Contacts management
 * - WebRTC signaling (offer/answer/ICE)
 * - In-call messaging (emoji, chat)
 * - TURN credential generation
 * - TLS/WSS support
 */

const WebSocket = require("ws");
const express = require("express");
const { MongoClient, ObjectId } = require("mongodb");
const https = require("https");
const http = require("http");
const fs = require("fs");
const crypto = require("crypto");

// ==================== CONFIGURATION ====================
const PORT = process.env.PORT || 3000;
const USE_TLS = process.env.USE_TLS === "true";
const CERT_PATH = process.env.CERT_PATH || "./certs/server.crt";
const KEY_PATH = process.env.KEY_PATH || "./certs/server.key";

// MongoDB Configuration
const MONGODB_URI = process.env.MONGODB_URI || "mongodb+srv://ankitrajj23_db_user:9874563210@echoo.ofymnz7.mongodb.net/?appName=Echoo";
const DB_NAME = "echoo";

// TURN server configuration
const TURN_SECRET = process.env.TURN_SECRET || "echoo-turn-secret-2024";
const TURN_TTL = 3600;
const TURN_SERVERS = [
  "turn:openrelay.metered.ca:80",
  "turn:openrelay.metered.ca:443",
  "turn:openrelay.metered.ca:443?transport=tcp"
];

// ==================== MONGODB CONNECTION ====================
let db = null;
let usersCollection = null;
let callHistoryCollection = null;
let callStatsCollection = null;
let contactsCollection = null;
let otpCollection = null;

async function connectToMongoDB() {
  try {
    console.log("🔄 Connecting to MongoDB...");
    const client = new MongoClient(MONGODB_URI);
    await client.connect();
    db = client.db(DB_NAME);
    
    // Initialize collections
    usersCollection = db.collection("users");
    callHistoryCollection = db.collection("call_history");
    callStatsCollection = db.collection("call_stats");
    contactsCollection = db.collection("contacts");
    otpCollection = db.collection("otps");
    
    // Create indexes
    await usersCollection.createIndex({ phoneNumber: 1 }, { unique: true });
    await callHistoryCollection.createIndex({ participants: 1 });
    await callHistoryCollection.createIndex({ startTime: -1 });
    await callStatsCollection.createIndex({ callId: 1 });
    await callStatsCollection.createIndex({ caller: 1 });
    await callStatsCollection.createIndex({ callee: 1 });
    await callStatsCollection.createIndex({ timestamp: -1 });
    await otpCollection.createIndex({ phoneNumber: 1 });
    await otpCollection.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 });
    
    console.log("✅ Connected to MongoDB successfully!");
    console.log(`   Database: ${DB_NAME}`);
    return true;
  } catch (error) {
    console.error("❌ MongoDB connection failed:", error.message);
    return false;
  }
}

// ==================== EXPRESS APP (REST API) ====================
const app = express();
app.use(express.json());

// CORS middleware
app.use((req, res, next) => {
  res.header("Access-Control-Allow-Origin", "*");
  res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization");
  res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  if (req.method === "OPTIONS") return res.sendStatus(200);
  next();
});

// Health check
app.get("/health", (req, res) => {
  res.json({ status: "ok", mongodb: db ? "connected" : "disconnected" });
});

// ==================== AUTH ENDPOINTS ====================

// Generate OTP
app.post("/auth/send-otp", async (req, res) => {
  try {
    const { phoneNumber } = req.body;
    if (!phoneNumber || phoneNumber.length < 10) {
      return res.status(400).json({ error: "Invalid phone number" });
    }
    
    // Generate 6-digit OTP
    const otp = Math.floor(100000 + Math.random() * 900000).toString();
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000); // 5 minutes
    
    // Store OTP in database
    await otpCollection.updateOne(
      { phoneNumber },
      { $set: { otp, expiresAt, attempts: 0 } },
      { upsert: true }
    );
    
    // In production, send OTP via SMS service (Twilio, etc.)
    // For development, we'll return it in response
    console.log(`📱 OTP for ${phoneNumber}: ${otp}`);
    
    res.json({ 
      success: true, 
      message: "OTP sent successfully",
      // Remove this in production - only for testing
      devOtp: process.env.NODE_ENV === "production" ? undefined : otp
    });
  } catch (error) {
    console.error("Error sending OTP:", error);
    res.status(500).json({ error: "Failed to send OTP" });
  }
});

// Verify OTP and register/login user
app.post("/auth/verify-otp", async (req, res) => {
  try {
    const { phoneNumber, otp, deviceInfo } = req.body;
    
    if (!phoneNumber || !otp) {
      return res.status(400).json({ error: "Phone number and OTP required" });
    }
    
    // Find OTP record
    const otpRecord = await otpCollection.findOne({ phoneNumber });
    
    if (!otpRecord) {
      return res.status(400).json({ error: "OTP not found. Please request new OTP." });
    }
    
    if (otpRecord.attempts >= 3) {
      await otpCollection.deleteOne({ phoneNumber });
      return res.status(400).json({ error: "Too many attempts. Please request new OTP." });
    }
    
    if (new Date() > otpRecord.expiresAt) {
      await otpCollection.deleteOne({ phoneNumber });
      return res.status(400).json({ error: "OTP expired. Please request new OTP." });
    }
    
    if (otpRecord.otp !== otp) {
      await otpCollection.updateOne({ phoneNumber }, { $inc: { attempts: 1 } });
      return res.status(400).json({ error: "Invalid OTP" });
    }
    
    // OTP verified - delete it
    await otpCollection.deleteOne({ phoneNumber });
    
    // Generate auth token
    const authToken = crypto.randomBytes(32).toString("hex");
    const tokenExpiry = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000); // 30 days
    
    // Create or update user
    const result = await usersCollection.findOneAndUpdate(
      { phoneNumber },
      {
        $set: {
          lastLogin: new Date(),
          authToken,
          tokenExpiry,
          deviceInfo: deviceInfo || {}
        },
        $setOnInsert: {
          phoneNumber,
          createdAt: new Date(),
          displayName: phoneNumber
        }
      },
      { upsert: true, returnDocument: "after" }
    );
    
    // Extract the document from the result
    const user = result.value || result;
    
    console.log(`✅ User authenticated: ${phoneNumber}`);
    
    res.json({
      success: true,
      user: {
        id: user._id.toString(),
        phoneNumber: user.phoneNumber,
        displayName: user.displayName
      },
      authToken
    });
  } catch (error) {
    console.error("Error verifying OTP:", error);
    res.status(500).json({ error: "Verification failed" });
  }
});

// Verify auth token middleware
async function authMiddleware(req, res, next) {
  const authToken = req.headers.authorization?.replace("Bearer ", "");
  
  if (!authToken) {
    return res.status(401).json({ error: "No auth token provided" });
  }
  
  const user = await usersCollection.findOne({ 
    authToken,
    tokenExpiry: { $gt: new Date() }
  });
  
  if (!user) {
    return res.status(401).json({ error: "Invalid or expired token" });
  }
  
  req.user = user;
  next();
}

// ==================== USER ENDPOINTS ====================

// Get user profile
app.get("/user/profile", authMiddleware, async (req, res) => {
  res.json({
    id: req.user._id,
    phoneNumber: req.user.phoneNumber,
    displayName: req.user.displayName,
    createdAt: req.user.createdAt
  });
});

// Update user profile
app.put("/user/profile", authMiddleware, async (req, res) => {
  try {
    const { displayName } = req.body;
    
    await usersCollection.updateOne(
      { _id: req.user._id },
      { $set: { displayName } }
    );
    
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: "Failed to update profile" });
  }
});

// ==================== CALL HISTORY ENDPOINTS ====================

// Get call history
app.get("/calls/history", authMiddleware, async (req, res) => {
  try {
    const limit = parseInt(req.query.limit) || 50;
    
    const calls = await callHistoryCollection
      .find({ participants: req.user.phoneNumber })
      .sort({ startTime: -1 })
      .limit(limit)
      .toArray();
    
    res.json({ calls });
  } catch (error) {
    console.error("Error fetching call history:", error);
    res.status(500).json({ error: "Failed to fetch call history" });
  }
});

// Get stats for a specific call
app.get("/calls/stats/:callId", authMiddleware, async (req, res) => {
  try {
    const callId = req.params.callId;
    
    const stats = await callStatsCollection.findOne({ 
      callId: callId,
      participants: req.user.phoneNumber 
    });
    
    if (!stats) {
      return res.status(404).json({ error: "Stats not found for this call" });
    }
    
    // Log for debugging
    console.log(`📊 Fetching stats for callId: ${callId}`);
    console.log(`   - totalSamples: ${stats.totalSamples}`);
    console.log(`   - samples array exists: ${!!stats.samples}`);
    console.log(`   - samples length: ${stats.samples?.length || 0}`);
    
    res.json({ stats });
  } catch (error) {
    console.error("Error fetching call stats:", error);
    res.status(500).json({ error: "Failed to fetch call stats" });
  }
});

// Get all stats for user's calls (for history view)
app.get("/calls/stats", authMiddleware, async (req, res) => {
  try {
    const limit = parseInt(req.query.limit) || 20;
    
    const stats = await callStatsCollection
      .find({ participants: req.user.phoneNumber })
      .sort({ timestamp: -1 })
      .limit(limit)
      .toArray();
    
    res.json({ stats });
  } catch (error) {
    console.error("Error fetching call stats:", error);
    res.status(500).json({ error: "Failed to fetch call stats" });
  }
});

// ==================== CONTACTS ENDPOINTS ====================

// Get contacts
app.get("/contacts", authMiddleware, async (req, res) => {
  try {
    const contacts = await contactsCollection
      .find({ userId: req.user._id })
      .toArray();
    
    // Check which contacts are registered users
    const phoneNumbers = contacts.map(c => c.phoneNumber);
    const registeredUsers = await usersCollection
      .find({ phoneNumber: { $in: phoneNumbers } })
      .toArray();
    
    const registeredSet = new Set(registeredUsers.map(u => u.phoneNumber));
    
    const enrichedContacts = contacts.map(c => ({
      ...c,
      isRegistered: registeredSet.has(c.phoneNumber)
    }));
    
    res.json({ contacts: enrichedContacts });
  } catch (error) {
    console.error("Error fetching contacts:", error);
    res.status(500).json({ error: "Failed to fetch contacts" });
  }
});

// Add contact
app.post("/contacts", authMiddleware, async (req, res) => {
  try {
    const { phoneNumber, name } = req.body;
    
    if (!phoneNumber) {
      return res.status(400).json({ error: "Phone number required" });
    }
    
    // Check if user exists
    const targetUser = await usersCollection.findOne({ phoneNumber });
    
    await contactsCollection.updateOne(
      { userId: req.user._id, phoneNumber },
      { 
        $set: { 
          name: name || phoneNumber,
          phoneNumber,
          isRegistered: !!targetUser,
          updatedAt: new Date()
        },
        $setOnInsert: { createdAt: new Date() }
      },
      { upsert: true }
    );
    
    res.json({ success: true, isRegistered: !!targetUser });
  } catch (error) {
    console.error("Error adding contact:", error);
    res.status(500).json({ error: "Failed to add contact" });
  }
});

// Delete contact
app.delete("/contacts/:phoneNumber", authMiddleware, async (req, res) => {
  try {
    await contactsCollection.deleteOne({
      userId: req.user._id,
      phoneNumber: req.params.phoneNumber
    });
    
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: "Failed to delete contact" });
  }
});

// ==================== TURN CREDENTIALS ENDPOINT ====================

app.get("/turn-credentials", authMiddleware, async (req, res) => {
  const credentials = generateTurnCredentials(req.user.phoneNumber);
  res.json(credentials);
});

function generateTurnCredentials(userId) {
  const timestamp = Math.floor(Date.now() / 1000) + TURN_TTL;
  const username = `${timestamp}:${userId}`;
  const hmac = crypto.createHmac("sha1", TURN_SECRET);
  hmac.update(username);
  const password = hmac.digest("base64");
  
  return {
    username,
    password,
    ttl: TURN_TTL,
    uris: TURN_SERVERS
  };
}

// ==================== SERVER SETUP ====================
let server;

if (USE_TLS && fs.existsSync(CERT_PATH) && fs.existsSync(KEY_PATH)) {
  const options = {
    cert: fs.readFileSync(CERT_PATH),
    key: fs.readFileSync(KEY_PATH)
  };
  server = https.createServer(options, app);
  console.log("🔒 TLS enabled");
} else {
  server = http.createServer(app);
  console.log("🔓 Running without TLS (development mode)");
}

// ==================== WEBSOCKET SETUP ====================
const wss = new WebSocket.Server({ server });
const clients = new Map(); // phoneNumber -> { ws, user }

// Active calls tracking
const activeCalls = new Map(); // callId -> { caller, callee, startTime, isVideo }

function broadcastUserList() {
  const userList = Array.from(clients.keys());
  const message = JSON.stringify({ type: "userList", users: userList });
  clients.forEach(({ ws }) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(message);
    }
  });
}

function sendTo(userId, message) {
  const client = clients.get(userId);
  if (client && client.ws.readyState === WebSocket.OPEN) {
    client.ws.send(JSON.stringify(message));
    return true;
  }
  return false;
}

async function saveCallHistory(callId, caller, callee, isVideo, duration, status) {
  try {
    await callHistoryCollection.insertOne({
      callId,
      caller,
      callee,
      participants: [caller, callee],
      isVideo,
      duration,
      status, // "completed", "missed", "rejected"
      startTime: new Date(Date.now() - (duration * 1000)),
      endTime: new Date()
    });
    console.log(`📝 Call history saved: ${caller} → ${callee} (${status})`);
  } catch (error) {
    console.error("Error saving call history:", error);
  }
}

// Save detailed call statistics
async function saveCallStats(data) {
  try {
    // Log incoming samples for debugging
    console.log(`📊 saveCallStats received:`);
    console.log(`   - samples type: ${typeof data.samples}`);
    console.log(`   - samples isArray: ${Array.isArray(data.samples)}`);
    console.log(`   - samples length: ${data.samples?.length || 0}`);
    if (data.samples && data.samples.length > 0) {
      console.log(`   - first sample: ${JSON.stringify(data.samples[0])}`);
    }
    
    const statsDoc = {
      callId: data.callId,
      caller: data.caller,
      callee: data.callee,
      participants: [data.caller, data.callee],
      isVideo: data.isVideo,
      duration: data.duration,
      totalSamples: data.totalSamples,
      
      // Summary statistics
      avgSendBitrateKbps: data.avgSendBitrateKbps,
      avgReceiveBitrateKbps: data.avgReceiveBitrateKbps,
      avgPacketLossPercent: data.avgPacketLossPercent,
      avgRttMs: data.avgRttMs,
      totalDataUsedBytes: data.totalDataUsedBytes,
      
      // Quality distribution (how much time in each quality level)
      qualityDistribution: data.qualityDistribution,
      
      // All sample data points for graphs
      samples: data.samples,
      
      timestamp: new Date()
    };
    
    await callStatsCollection.insertOne(statsDoc);
    
    // Also update the call history with stats summary
    await callHistoryCollection.updateOne(
      { callId: data.callId },
      { 
        $set: { 
          hasStats: true,
          avgBitrateKbps: (data.avgSendBitrateKbps + data.avgReceiveBitrateKbps) / 2,
          avgPacketLossPercent: data.avgPacketLossPercent,
          avgRttMs: data.avgRttMs,
          totalDataUsedMB: (data.totalDataUsedBytes / (1024 * 1024)).toFixed(2)
        }
      }
    );
    
    console.log(`📊 Call stats saved: ${data.caller} → ${data.callee}, ${data.totalSamples} samples, ${data.duration}s duration`);
  } catch (error) {
    console.error("Error saving call stats:", error);
  }
}

wss.on("connection", async (ws, req) => {
  let currentUser = null;
  const clientIp = req.socket.remoteAddress;
  console.log(`🔌 New WebSocket connection from ${clientIp}`);

  ws.on("message", async (message) => {
    let data;
    try {
      data = JSON.parse(message.toString());
    } catch (e) {
      console.error("Invalid JSON:", message.toString());
      return;
    }

    const logPrefix = `[${data.from || data.userId || currentUser || "?"}]`;
    
    switch (data.type) {
      case "authenticate": {
        // Authenticate WebSocket connection with token
        const user = await usersCollection.findOne({
          authToken: data.authToken,
          tokenExpiry: { $gt: new Date() }
        });
        
        if (!user) {
          ws.send(JSON.stringify({ type: "authError", message: "Invalid token" }));
          ws.close();
          return;
        }
        
        // Remove old connection if exists
        if (clients.has(user.phoneNumber)) {
          clients.get(user.phoneNumber).ws.close();
        }
        
        currentUser = user.phoneNumber;
        clients.set(user.phoneNumber, { ws, user });
        
        console.log(`✅ Authenticated: ${user.phoneNumber}`);
        ws.send(JSON.stringify({ type: "authenticated", userId: user.phoneNumber }));
        broadcastUserList();
        break;
      }
      
      case "join": {
        // Legacy join (for backward compatibility)
        if (clients.has(data.userId)) {
          clients.get(data.userId).ws.close();
        }
        currentUser = data.userId;
        clients.set(data.userId, { ws, user: { phoneNumber: data.userId } });
        console.log(`✅ User joined: ${data.userId}`);
        broadcastUserList();
        break;
      }

      case "offer": {
        console.log(`📞 Offer: ${data.from} → ${data.to} (video: ${data.isVideoCall})`);
        
        // Create call tracking
        const callId = crypto.randomUUID();
        activeCalls.set(callId, {
          caller: data.from,
          callee: data.to,
          startTime: Date.now(),
          isVideo: data.isVideoCall
        });
        
        sendTo(data.to, {
          type: "offer",
          from: data.from,
          to: data.to,
          sdp: data.sdp,
          isVideoCall: data.isVideoCall !== false,
          callId
        });
        break;
      }

      case "answer": {
        console.log(`📞 Answer: ${data.from} → ${data.to} (callId: ${data.callId})`);
        
        // Update call startTime to when the call was actually answered
        if (data.callId && activeCalls.has(data.callId)) {
          const call = activeCalls.get(data.callId);
          call.startTime = Date.now();  // Reset to actual answer time
          call.answered = true;
          console.log(`📞 Call ${data.callId} answered, timer started`);
        }
        
        sendTo(data.to, {
          type: "answer",
          from: data.from,
          to: data.to,
          sdp: data.sdp,
          callId: data.callId
        });
        break;
      }

      case "candidate": {
        sendTo(data.to, {
          type: "candidate",
          from: data.from,
          to: data.to,
          candidate: data.candidate
        });
        break;
      }

      case "reject": {
        console.log(`❌ Reject: ${data.from} → ${data.to}`);
        
        // Save as rejected call
        if (data.callId && activeCalls.has(data.callId)) {
          const call = activeCalls.get(data.callId);
          await saveCallHistory(data.callId, call.caller, call.callee, call.isVideo, 0, "rejected");
          activeCalls.delete(data.callId);
        }
        
        sendTo(data.to, {
          type: "reject",
          from: data.from,
          to: data.to
        });
        break;
      }

      case "hangup": {
        console.log(`📴 Hangup: ${data.from} → ${data.to} (callId: ${data.callId})`);
        
        // Calculate duration and save
        if (data.callId && activeCalls.has(data.callId)) {
          const call = activeCalls.get(data.callId);
          const duration = call.answered ? Math.floor((Date.now() - call.startTime) / 1000) : 0;
          const status = call.answered ? "completed" : "missed";
          console.log(`📴 Call ${data.callId} ended: ${status}, duration: ${duration}s`);
          await saveCallHistory(data.callId, call.caller, call.callee, call.isVideo, duration, status);
          activeCalls.delete(data.callId);
        } else {
          console.log(`⚠️ No active call found for callId: ${data.callId}`);
        }
        
        sendTo(data.to, {
          type: "hangup",
          from: data.from,
          to: data.to
        });
        break;
      }
      
      case "callStats": {
        console.log(`📊 Call stats received from ${data.from}`);
        try {
          await saveCallStats(data);
        } catch (error) {
          console.error("Error saving call stats:", error);
        }
        break;
      }
        
      case "emoji": {
        console.log(`😊 Emoji: ${data.from} → ${data.to}: ${data.emoji}`);
        sendTo(data.to, {
          type: "emoji",
          from: data.from,
          to: data.to,
          emoji: data.emoji
        });
        break;
      }
        
      case "chat": {
        console.log(`💬 Chat: ${data.from} → ${data.to}`);
        sendTo(data.to, {
          type: "chat",
          from: data.from,
          to: data.to,
          message: data.message
        });
        break;
      }
        
      case "checkUser": {
        const isOnline = clients.has(data.userId);
        const isRegistered = await usersCollection.findOne({ phoneNumber: data.userId });
        ws.send(JSON.stringify({
          type: "userStatus",
          userId: data.userId,
          online: isOnline,
          registered: !!isRegistered
        }));
        break;
      }

      case "getUsers": {
        ws.send(JSON.stringify({
          type: "userList",
          users: Array.from(clients.keys())
        }));
        break;
      }
        
      case "getTurnCredentials": {
        const credentials = generateTurnCredentials(currentUser || "anonymous");
        ws.send(JSON.stringify({
          type: "turnCredentials",
          ...credentials
        }));
        console.log(`🔑 TURN credentials generated for ${currentUser}`);
        break;
      }
    }
  });

  ws.on("close", async () => {
    if (currentUser) {
      clients.delete(currentUser);
      console.log(`❎ User left: ${currentUser}`);
      
      // Mark any active calls as missed
      for (const [callId, call] of activeCalls) {
        if (call.caller === currentUser || call.callee === currentUser) {
          await saveCallHistory(callId, call.caller, call.callee, call.isVideo, 0, "missed");
          activeCalls.delete(callId);
        }
      }
      
      broadcastUserList();
    }
  });

  ws.on("error", (error) => {
    console.error(`WebSocket error for ${currentUser}:`, error.message);
  });
});

// ==================== START SERVER ====================
async function startServer() {
  // Connect to MongoDB first
  const dbConnected = await connectToMongoDB();
  
  if (!dbConnected) {
    console.warn("⚠️ Starting without database - some features disabled");
  }
  
  server.listen(PORT, "0.0.0.0", () => {
    console.log("=================================");
    console.log(`🚀 Signaling server running`);
    console.log(`   HTTP/WS: http://0.0.0.0:${PORT}`);
    console.log(`   WebSocket: ws://0.0.0.0:${PORT}`);
    console.log(`   MongoDB: ${dbConnected ? "Connected" : "Not connected"}`);
    console.log("=================================");
    console.log("\n📱 REST API Endpoints:");
    console.log("   POST /auth/send-otp     - Request OTP");
    console.log("   POST /auth/verify-otp   - Verify OTP & Login");
    console.log("   GET  /user/profile      - Get user profile");
    console.log("   GET  /calls/history     - Get call history");
    console.log("   GET  /contacts          - Get contacts");
    console.log("   POST /contacts          - Add contact");
    console.log("   GET  /turn-credentials  - Get TURN credentials");
    console.log("");
  });
}

// Graceful shutdown
process.on("SIGINT", async () => {
  console.log("\n🛑 Shutting down...");
  clients.forEach(({ ws }) => ws.close(1001, "Server shutting down"));
  wss.close();
  server.close();
  process.exit(0);
});

// Start the server
startServer();
