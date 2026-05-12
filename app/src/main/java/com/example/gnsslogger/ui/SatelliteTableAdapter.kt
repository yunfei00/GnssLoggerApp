package com.example.gnsslogger.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gnsslogger.data.GnssSatelliteRecord
import com.example.gnsslogger.databinding.ItemSatelliteRowBinding

class SatelliteTableAdapter : RecyclerView.Adapter<SatelliteTableAdapter.VH>() {

    private var items: List<GnssSatelliteRecord> = emptyList()

    fun submit(list: List<GnssSatelliteRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSatelliteRowBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemSatelliteRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(s: GnssSatelliteRecord) {
            binding.textConstellation.text = s.constellationName
            binding.textSvid.text = s.svid.toString()
            binding.textCn0.text = formatFloat(s.cn0DbHz)
            binding.textElev.text = formatFloat(s.elevationDegrees)
            binding.textAzim.text = formatFloat(s.azimuthDegrees)
            binding.textUsed.text = if (s.usedInFix) "Y" else "N"
            binding.textCarrier.text = s.carrierFrequencyHz?.let { formatFloat(it) } ?: "-"
            binding.textBaseband.text = s.basebandCn0DbHz?.let { formatFloat(it) } ?: "-"
        }

        private fun formatFloat(v: Float): String =
            if (v.isFinite()) String.format(java.util.Locale.US, "%.1f", v) else "-"
    }
}
