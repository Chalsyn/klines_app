package com.kline.trainer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView

class PositionAdapter(
    private val positions: MutableList<Position>,
    private val onClose: (Int) -> Unit
) : RecyclerView.Adapter<PositionAdapter.VH>() {

    private var currentPrice = 0.0
    private var multiplier = 100

    fun updatePnl(cp: Double, mult: Int) {
        currentPrice = cp; multiplier = mult
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvInfo: TextView = v.findViewById(R.id.tvPosInfo)
        val tvPnl: TextView = v.findViewById(R.id.tvPosPnl)
        val btnClose: TextView = v.findViewById(R.id.btnClosePos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_position, parent, false))

    override fun getItemCount() = positions.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = positions[pos]
        val pnl = if (p.type == "LONG") (currentPrice - p.price) * multiplier
                  else (p.price - currentPrice) * multiplier
        val arrow = if (p.type == "LONG") "🟢多" else "🔴空"
        val slStr = if (p.sl > 0) " SL:${"%.2f".format(p.sl)}" else ""
        val tpStr = if (p.tp > 0) " TP:${"%.2f".format(p.tp)}" else ""
        h.tvInfo.text = "$arrow @${"%.2f".format(p.price)}$slStr$tpStr"
        h.tvPnl.text = "%+.1f".format(pnl)
        h.tvPnl.setTextColor(if (pnl >= 0) Color.parseColor("#2ECC71") else Color.parseColor("#E74C3C"))
        h.btnClose.setOnClickListener {
            AlertDialog.Builder(h.itemView.context, R.style.DarkDialog)
                .setTitle("确认平仓")
                .setMessage("以当前价 ${"%.2f".format(currentPrice)} 平仓？\n预计盈亏: ${"%+.1f".format(pnl)}")
                .setPositiveButton("✅ 平仓") { _, _ -> onClose(h.adapterPosition) }
                .setNegativeButton("取消", null).show()
        }
    }
}
