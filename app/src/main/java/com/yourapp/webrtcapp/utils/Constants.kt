package com.yourapp.webrtcapp.utils

object Constants {
    // ⚠️ IMPORTANT: Change this IP to your computer's IP address
    // Run 'ipconfig' in terminal and use the IPv4 address from your WiFi adapter
    // Example: "192.168.1.100" or "10.120.0.46"
    const val SERVER_IP = "10.40.93.46"
    const val SERVER_PORT = 3000
    
    // For emulator testing (connects to host machine's localhost)
    const val EMULATOR_SERVER_URL = "ws://10.0.2.2:$SERVER_PORT"
    
    // For real devices (both phones should use this)
    const val DEVICE_SERVER_URL = "ws://$SERVER_IP:$SERVER_PORT"
}
