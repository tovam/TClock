package org.fossify.clock.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.databinding.ItemScheduledAlarmBinding
import org.fossify.clock.models.Alarm
import java.text.DateFormat
import java.util.Date

class ScheduledAlarmsAdapter(
    private val textColor: Int,
) : RecyclerView.Adapter<ScheduledAlarmsAdapter.ViewHolder>() {
    data class Item(
        val alarm: Alarm,
        val triggerAtMillis: Long,
    )

    private var items: List<Item> = emptyList()

    fun updateItems(newItems: List<Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduledAlarmBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemScheduledAlarmBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item) {
            val context = binding.root.context
            val alarm = item.alarm
            binding.scheduledAlarmTime.apply {
                text = DateFormat.getDateTimeInstance(
                    DateFormat.FULL,
                    DateFormat.SHORT
                ).format(Date(item.triggerAtMillis))
                setTextColor(textColor)
            }
            binding.scheduledAlarmLabel.apply {
                text = alarm.label.ifBlank { context.getString(R.string.unnamed_alarm) }
                setTextColor(textColor)
            }
            binding.scheduledAlarmSource.apply {
                text = if (alarm.isCalendarAlarm()) {
                    val offset = alarm.calendarOffsetMinutes
                    when {
                        offset < 0 -> context.getString(
                            R.string.calendar_alarm_before,
                            -offset
                        )
                        offset > 0 -> context.getString(
                            R.string.calendar_alarm_after,
                            offset
                        )
                        else -> context.getString(R.string.calendar_alarm_at_start)
                    }
                } else {
                    context.getString(R.string.manual_alarm)
                }
                setTextColor(textColor)
            }
        }
    }
}
