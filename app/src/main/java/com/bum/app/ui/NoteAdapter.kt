package com.bum.app.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bum.app.R
import com.bum.app.models.Note
import com.bum.app.security.SecureDataManager
import java.text.SimpleDateFormat
import java.util.*

// Adapter الملاحظات الرئيسية
class NoteAdapter(
    private var items: MutableList<Note>,
    private val secure: SecureDataManager,
    private val onOpen: (Note) -> Unit,
    private val onDelete: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.VH>() {

    fun setItems(list: List<Note>) {
        items = list.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {

        if (payloads.isNotEmpty() && payloads.contains("tick")) {
            val n = items[position]
            h.tvPolicy.text = policyLabel(n)
            return
        }
        super.onBindViewHolder(h, position, payloads)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val n = items[position]


        when (n.type) {
            Note.NoteType.TEXT -> {
                h.iv.visibility = View.GONE
                h.tvPreview.visibility = View.VISIBLE
                val plain = secure.decryptNoteText(n) ?: "—"
                val preview = if (plain.length > 160) plain.substring(0, 160) + "…" else plain
                h.tvPreview.text = preview
            }
            Note.NoteType.IMAGE -> {
                h.iv.visibility = View.GONE
                h.tvPreview.visibility = View.VISIBLE
                val cap = if (n.imageNote.isNotBlank()) "  —  ${n.imageNote}" else ""
                h.tvPreview.text = "🖼  صورة$cap"
            }
        }
        if (n.attachments.isNotEmpty()) {
            h.tvPreview.text = "${h.tvPreview.text}  •  📎 ${n.attachments.size}"
        }

        val df = SimpleDateFormat("HH:mm • dd/MM", Locale.getDefault())
        h.tvDate.text = df.format(Date(n.createdAt))

        // شارة الصلاحية
        h.tvPolicy.text = policyLabel(n)

        // شارة "تدمير بعد القراءة"
        h.tvDestroy.visibility =
            if (n.destroyOnRead) View.VISIBLE else View.GONE

        h.itemView.setOnClickListener { onOpen(n) }
        h.btnDelete.setOnClickListener { onDelete(n) }
    }

    private fun policyLabel(n: Note): String = when (n.expiryPolicy) {
        Note.ExpiryPolicy.PERMANENT -> "♾ دائم"
        Note.ExpiryPolicy.ON_APP_CLOSE -> "⚡ يموت عند الإغلاق"
        else -> {
            val remain = n.remainingMs()
            if (remain <= 0L) "⌛ منتهية"
            else "⏳ ${formatRemain(remain)}"
        }
    }

    private fun formatRemain(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}ث"
            sec < 3600 -> "${sec/60}د"
            sec < 86400 -> "${sec/3600}س"
            else -> "${sec/86400}ي"
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.iv_thumb)
        val tvPreview: TextView = v.findViewById(R.id.tv_preview)
        val tvDate: TextView = v.findViewById(R.id.tv_date)
        val tvPolicy: TextView = v.findViewById(R.id.tv_policy)
        val tvDestroy: TextView = v.findViewById(R.id.tv_destroy)
        val btnDelete: View = v.findViewById(R.id.btn_delete)
    }
}
