package com.kline.trainer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val records: MutableList<TradeRecord>
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val tvType: TextView = v.findViewById(R.id.tvType)
        val tvEntry: TextView = v.findViewById(R.id.tvEntry)
        val tvExit: TextView = v.findViewById(R.id.tvExit)
        val tvEstRR: TextView = v.findViewById(R.id.tvEstRR)
        val tvActRR: TextView = v.findViewById(R.id.tvActRR)
        val tvMaxLoss: TextView = v.findViewById(R.id.tvMaxLoss)
        val tvPnl: TextView = v.findViewById(R.id.tvPnl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun getItemCount() = records.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = records[pos]
        h.tvNo.text = r.no.toString()
        h.tvType.text = if (r.type == "LONG") "多" else "空"
        h.tvType.setTextColor(if (r.type == "LONG") Color.parseColor("#2ECC71") else Color.parseColor("#E74C3C"))
        h.tvEntry.text = "%.2f".format(r.entryPrice)
        h.tvExit.text = "%.2f".format(r.exitPrice)
        h.tvEstRR.text = r.estRR.toString()
        h.tvActRR.text = if (r.actualRR == Double.MAX_VALUE) "∞" else r.actualRR.toString()
        h.tvMaxLoss.text = "%.2f".format(r.maxLossPrice)
        h.tvPnl.text = "%+.1f".format(r.pnl)
        h.tvPnl.setTextColor(if (r.pnl >= 0) Color.parseColor("#2ECC71") else Color.parseColor("#E74C3C"))
    }
}
