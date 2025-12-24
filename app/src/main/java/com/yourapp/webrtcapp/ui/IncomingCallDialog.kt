package com.yourapp.webrtcapp.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.yourapp.webrtcapp.R

class IncomingCallDialog(
    context: Context,
    private val callerName: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_incoming_call)
        
        setCancelable(false)
        
        // Set caller name
        findViewById<TextView>(R.id.callerNameText).text = "Call from: $callerName"

        findViewById<Button>(R.id.acceptBtn).setOnClickListener {
            dismiss()
            onAccept()
        }

        findViewById<Button>(R.id.rejectBtn).setOnClickListener {
            dismiss()
            onReject()
        }
    }
}
