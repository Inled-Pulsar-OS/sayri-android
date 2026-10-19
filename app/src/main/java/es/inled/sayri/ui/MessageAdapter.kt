package es.inled.sayri.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import es.inled.sayri.R
import es.inled.sayri.data.ChatMessage
import es.inled.sayri.databinding.ItemMessageBinding

class MessageAdapter(private val messages: MutableList<ChatMessage> = mutableListOf()) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val context = holder.itemView.context

        holder.binding.tvAuthor.text = msg.author
        holder.binding.tvContent.text = msg.content

        val parentLayout = holder.itemView as LinearLayout
        val card = holder.binding.cardMessage

        if (msg.isUser) {
            parentLayout.gravity = Gravity.END
            card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_user))
            holder.binding.tvAuthor.setTextColor(ContextCompat.getColor(context, R.color.bubble_user_text))
            holder.binding.tvContent.setTextColor(ContextCompat.getColor(context, R.color.bubble_user_text))
        } else {
            parentLayout.gravity = Gravity.START
            card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_sayri))
            holder.binding.tvAuthor.setTextColor(ContextCompat.getColor(context, R.color.accent_teal))
            holder.binding.tvContent.setTextColor(ContextCompat.getColor(context, R.color.bubble_sayri_text))
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(newContent: String) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].content = newContent
            notifyItemChanged(lastIdx)
        }
    }
}
