package com.yourapp.webrtcapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.yourapp.webrtcapp.R

class LoginActivity : AppCompatActivity() {

    private lateinit var phoneInput: TextInputEditText
    private lateinit var registerButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        phoneInput = findViewById(R.id.phoneInput)
        registerButton = findViewById(R.id.registerButton)

        registerButton.setOnClickListener {
            val phoneNumber = phoneInput.text.toString().trim()
            when {
                phoneNumber.isEmpty() -> {
                    Toast.makeText(this, "Please enter your phone number", Toast.LENGTH_SHORT).show()
                }
                phoneNumber.length != 10 -> {
                    Toast.makeText(this, "Please enter a valid 10-digit phone number", Toast.LENGTH_SHORT).show()
                }
                !phoneNumber.all { it.isDigit() } -> {
                    Toast.makeText(this, "Phone number should contain only digits", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val intent = Intent(this, DialActivity::class.java)
                    intent.putExtra("MY_PHONE", phoneNumber)
                    startActivity(intent)
                }
            }
        }
    }
}
