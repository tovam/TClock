package org.fossify.clock.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.clock.R
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Limits
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.engine.Cl1MirrorOverrides
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.databinding.DialogCl1CopyOptionsBinding
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast

class Cl1CopyOptionsDialog(
    private val activity: BaseSimpleActivity,
    private val source: Cl1EventSnapshot,
    titleId: Int,
    private val callback: (Cl1MirrorOverrides) -> Unit,
) {
    private val binding = DialogCl1CopyOptionsBinding.inflate(
        activity.layoutInflater,
        null,
        false
    )

    init {
        bindEnabledStates()
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val overrides = readOverrides() ?: return@setOnClickListener
                        dialog.dismiss()
                        callback(overrides)
                    }
                }
            }
    }

    private fun bindEnabledStates() = binding.apply {
        cl1TitleMode.setOnCheckedChangeListener { _, checkedId ->
            cl1TitleValue.isEnabled = checkedId != R.id.cl1_title_inherit
            cl1TitleTemplateHelp.visibility =
                if (checkedId == R.id.cl1_title_template) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
        }
        cl1StartEnabled.setOnCheckedChangeListener { _, checked ->
            cl1StartSeconds.isEnabled = checked
        }
        cl1DurationMode.setOnCheckedChangeListener { _, checkedId ->
            cl1DurationSeconds.isEnabled =
                checkedId != R.id.cl1_duration_inherit
        }
    }

    private fun readOverrides(): Cl1MirrorOverrides? {
        val titleValue = binding.cl1TitleValue.text.toString()
        val titleMode = binding.cl1TitleMode.checkedRadioButtonId
        val titleValueInvalid = titleValue.isEmpty() ||
            titleValue.toByteArray(Charsets.UTF_8).size > Cl1Limits.TITLE_BYTES
        if (
            titleMode != R.id.cl1_title_inherit &&
            titleValueInvalid
        ) {
            activity.toast(R.string.cl1_title_invalid)
            return null
        }
        val title = when (titleMode) {
            R.id.cl1_title_replace -> Cl1TitleOverride.Replacement(
                titleValue
            )

            R.id.cl1_title_template -> Cl1TitleOverride.Template(
                titleValue
            )

            else -> Cl1TitleOverride.Inherited
        }
        val startOffset = if (binding.cl1StartEnabled.isChecked) {
            val value = binding.cl1StartSeconds.text.toString().toLongOrNull()
            if (
                value == null ||
                value !in -Cl1Limits.OFFSET_SECONDS..Cl1Limits.OFFSET_SECONDS
            ) {
                activity.toast(R.string.cl1_start_offset_invalid)
                return null
            }
            value
        } else {
            null
        }
        val sourceEvent = try {
            source.canonicalEvent()
        } catch (_: IllegalArgumentException) {
            activity.toast(R.string.cl1_source_incompatible)
            return null
        }
        val sourceDuration = try {
            Math.subtractExact(
                sourceEvent.endUnixSeconds,
                sourceEvent.startUnixSeconds
            )
        } catch (_: ArithmeticException) {
            activity.toast(R.string.cl1_duration_invalid)
            return null
        }
        if (sourceDuration <= 0L) {
            activity.toast(R.string.cl1_source_incompatible)
            return null
        }
        val duration = when (binding.cl1DurationMode.checkedRadioButtonId) {
            R.id.cl1_duration_fixed -> {
                val value = binding.cl1DurationSeconds.text.toString().toULongOrNull()
                if (
                    value == null ||
                    value == 0UL ||
                    value > Long.MAX_VALUE.toULong()
                ) {
                    activity.toast(R.string.cl1_duration_invalid)
                    return null
                }
                Cl1DurationOverride.Fixed(value)
            }

            R.id.cl1_duration_delta -> {
                val value = binding.cl1DurationSeconds.text.toString().toLongOrNull()
                val resultingDuration = try {
                    value?.let { Math.addExact(sourceDuration, it) }
                } catch (_: ArithmeticException) {
                    null
                }
                if (value == null || resultingDuration == null || resultingDuration <= 0L) {
                    activity.toast(R.string.cl1_duration_invalid)
                    return null
                }
                Cl1DurationOverride.Delta(value)
            }

            else -> Cl1DurationOverride.Inherited
        }
        val targetDuration = when (duration) {
            Cl1DurationOverride.Inherited -> sourceDuration
            is Cl1DurationOverride.Fixed -> duration.seconds.toLong()
            is Cl1DurationOverride.Delta -> Math.addExact(
                sourceDuration,
                duration.seconds
            )
        }
        try {
            val targetStart = Math.addExact(
                sourceEvent.startUnixSeconds,
                startOffset ?: 0L
            )
            Math.addExact(targetStart, targetDuration)
        } catch (_: ArithmeticException) {
            activity.toast(R.string.cl1_duration_invalid)
            return null
        }
        return Cl1MirrorOverrides(
            title = title,
            startOffsetSeconds = startOffset,
            duration = duration
        )
    }
}
