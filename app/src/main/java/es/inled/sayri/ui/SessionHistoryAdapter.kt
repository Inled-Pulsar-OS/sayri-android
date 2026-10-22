package es.inled.sayri.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import es.inled.sayri.data.SessionSummary
import es.inled.sayri.databinding.ItemHistorySessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionHistoryAdapter(
    private val onSessionSelected: (SessionSummary) -> Unit,
    private val onSessionDelete: (SessionSummary) -> Unit
) : RecyclerView.Adapter<SessionHistoryAdapter.SessionViewHolder>() {

    private val sessions = mutableListOf<SessionSummary>()

    fun setSessions(items: List<SessionSummary>) {
        sessions.clear()
        sessions.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemHistorySessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun getItemCount(): Int = sessions.size

    inner class SessionViewHolder(private val binding: ItemHistorySessionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: SessionSummary) {
            val title = session.title?.takeIf { it.isNotBlank() } ?: "Conversation"
            binding.tvSessionTitle.text = title

            val dateStr = formatTimestamp(session.updatedAt ?: session.createdAt)
            binding.tvSessionTime.text = dateStr

            val count = session.msgCount
            binding.tvSessionCount.text = "• $count msgs"

            binding.root.setOnClickListener {
                onSessionSelected(session)
            }

            binding.btnDeleteSession.setOnClickListener {
                onSessionDelete(session)
            }
        }

        private fun formatTimestamp(ts: Double?): String {
            if (ts == null || ts <= 0) return ""
            return try {
                val date = Date((ts * 1000).toLong())
                val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                sdf.format(date)
            } catch (e: Exception) {
                ""
            }
        }
    }
}
