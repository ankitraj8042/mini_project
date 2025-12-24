package com.yourapp.webrtcapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yourapp.webrtcapp.R

class LoginActivity : AppCompatActivity() {

    private lateinit var usernameInput: EditText
    private lateinit var joinButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        usernameInput = findViewById(R.id.usernameInput)
        joinButton = findViewById(R.id.joinButton)

        joinButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            if (username.isNotEmpty()) {
                val intent = Intent(this, UserListActivity::class.java)
                intent.putExtra("USERNAME", username)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
