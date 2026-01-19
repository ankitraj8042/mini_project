package com.yourapp.webrtcapp.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.api.Contact

/**
 * Adapter for displaying contacts list
 */
class ContactsAdapter(
    private val onVideoCall: (String) -> Unit,
    private val onAudioCall: (String) -> Unit,
    private val onDelete: (Contact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    private val items = mutableListOf<Contact>()

    fun submitList(newItems: List<Contact>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
    
    fun addContact(contact: Contact) {
        items.add(0, contact)
        notifyItemInserted(0)
    }
    
    fun removeContact(phoneNumber: String) {
        val index = items.indexOfFirst { it.phoneNumber == phoneNumber }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarText: TextView = itemView.findViewById(R.id.avatarText)
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val phoneText: TextView = itemView.findViewById(R.id.phoneText)
        private val videoCallBtn: ImageButton = itemView.findViewById(R.id.videoCallBtn)
        private val audioCallBtn: ImageButton = itemView.findViewById(R.id.audioCallBtn)

        fun bind(contact: Contact) {
            // Avatar initial
            val initial = (contact.name.firstOrNull() ?: contact.phoneNumber.firstOrNull() ?: '?')
                .uppercaseChar()
            avatarText.text = initial.toString()
            
            // Set random background color based on name
            val colors = listOf(
                "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", 
                "#2196F3", "#009688", "#4CAF50", "#FF9800"
            )
            val colorIndex = kotlin.math.abs(contact.name.hashCode()) % colors.size
            avatarText.background.setTint(android.graphics.Color.parseColor(colors[colorIndex]))
            
            nameText.text = contact.name.ifEmpty { "Unknown" }
            phoneText.text = contact.phoneNumber
            
            // Click handlers
            videoCallBtn.setOnClickListener { onVideoCall(contact.phoneNumber) }
            audioCallBtn.setOnClickListener { onAudioCall(contact.phoneNumber) }
            
            // Long press to delete
            itemView.setOnLongClickListener {
                onDelete(contact)
                true
            }
        }
    }
}
