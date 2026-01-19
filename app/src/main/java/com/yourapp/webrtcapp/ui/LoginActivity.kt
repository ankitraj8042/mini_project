package com.yourapp.webrtcapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.auth.AuthManager

class LoginActivity : AppCompatActivity() {

    private lateinit var phoneInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText
    private lateinit var otpInput: TextInputEditText
    private lateinit var otpInputLayout: TextInputLayout
    private lateinit var registerButton: Button
    private lateinit var loadingProgress: ProgressBar
    private lateinit var statusMessage: TextView
    
    private var isOtpSent = false
    private var currentPhoneNumber = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if already logged in
        val authManager = AuthManager.getInstance(this)
        if (authManager.isLoggedIn()) {
            navigateToDialActivity()
            return
        }
        
        setContentView(R.layout.activity_login)

        phoneInput = findViewById(R.id.phoneInput)
        nameInput = findViewById(R.id.nameInput)
        otpInput = findViewById(R.id.otpInput)
        otpInputLayout = findViewById(R.id.otpInputLayout)
        registerButton = findViewById(R.id.registerButton)
        loadingProgress = findViewById(R.id.loadingProgress)
        statusMessage = findViewById(R.id.statusMessage)

        registerButton.setOnClickListener {
            if (!isOtpSent) {
                sendOtp()
            } else {
                verifyOtp()
            }
        }
    }
    
    private fun sendOtp() {
        val phoneNumber = phoneInput.text.toString().trim()
        
        when {
            phoneNumber.isEmpty() -> {
                Toast.makeText(this, "Please enter your phone number", Toast.LENGTH_SHORT).show()
                return
            }
            phoneNumber.length != 10 -> {
                Toast.makeText(this, "Please enter a valid 10-digit phone number", Toast.LENGTH_SHORT).show()
                return
            }
            !phoneNumber.all { it.isDigit() } -> {
                Toast.makeText(this, "Phone number should contain only digits", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        currentPhoneNumber = phoneNumber
        showLoading(true)
        
        AuthManager.getInstance(this).sendOtp(phoneNumber, { devOtp ->
            runOnUiThread {
                showLoading(false)
                isOtpSent = true
                otpInputLayout.visibility = View.VISIBLE
                registerButton.text = "Verify OTP"
                val otpHint = if (devOtp != null) "(Dev OTP: $devOtp)" else ""
                statusMessage.text = "OTP sent to $phoneNumber $otpHint"
                statusMessage.visibility = View.VISIBLE
                phoneInput.isEnabled = false
                Toast.makeText(this, "OTP sent successfully!", Toast.LENGTH_SHORT).show()
            }
        }, { error ->
            runOnUiThread {
                showLoading(false)
                // For development/demo, allow bypass
                showBypassOption(phoneNumber, error)
            }
        })
    }
    
    private fun verifyOtp() {
        val otp = otpInput.text.toString().trim()
        val displayName = nameInput.text.toString().trim().ifEmpty { "User" }
        
        if (otp.isEmpty()) {
            Toast.makeText(this, "Please enter the OTP", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (otp.length != 6) {
            Toast.makeText(this, "OTP must be 6 digits", Toast.LENGTH_SHORT).show()
            return
        }
        
        showLoading(true)
        
        AuthManager.getInstance(this).verifyOtp(currentPhoneNumber, otp, { userInfo ->
            runOnUiThread {
                showLoading(false)
                Toast.makeText(this, "Welcome ${userInfo.displayName}!", Toast.LENGTH_SHORT).show()
                navigateToDialActivity()
            }
        }, { error ->
            runOnUiThread {
                showLoading(false)
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        })
    }
    
    private fun showBypassOption(phoneNumber: String, errorMessage: String) {
        // Show error and offer legacy login for development
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connection Issue")
            .setMessage("$errorMessage\n\nWould you like to continue in offline mode? (Development only)")
            .setPositiveButton("Offline Mode") { _, _ ->
                // Legacy login without server
                AuthManager.getInstance(this).legacyLogin(phoneNumber)
                navigateToDialActivity()
            }
            .setNegativeButton("Retry") { _, _ ->
                // Reset state
                isOtpSent = false
                otpInputLayout.visibility = View.GONE
                registerButton.text = "Send OTP"
                phoneInput.isEnabled = true
            }
            .show()
    }
    
    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
        registerButton.isEnabled = !show
    }
    
    private fun navigateToDialActivity() {
        val authManager = AuthManager.getInstance(this)
        val intent = Intent(this, DialActivity::class.java)
        intent.putExtra("MY_PHONE", authManager.getPhoneNumber())
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
