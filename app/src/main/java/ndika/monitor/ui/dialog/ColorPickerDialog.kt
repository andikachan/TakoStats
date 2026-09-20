package ndika.monitor.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ndika.monitor.R
import ndika.monitor.databinding.DialogColorPickerBinding
import java.util.Locale

class ColorPickerDialog(
    context: Context,
    private val initialColor: Int,
    private val showAlpha: Boolean = true,
    private val onColorSelected: (color: Int) -> Unit
) {

    private val builder = MaterialAlertDialogBuilder(context)
    private var currentColor = initialColor

    fun show(): Dialog {
        val inflater = LayoutInflater.from(builder.context)
        val binding = DialogColorPickerBinding.inflate(inflater)

        var a = Color.alpha(initialColor)
        var r = Color.red(initialColor)
        var g = Color.green(initialColor)
        var b = Color.blue(initialColor)

        binding.seekAlpha.progress = a
        binding.seekRed.progress = r
        binding.seekGreen.progress = g
        binding.seekBlue.progress = b

        if (!showAlpha) {
            binding.layoutAlpha.visibility = android.view.View.GONE
        }

        fun updateUi() {
            currentColor = Color.argb(a, r, g, b)
            binding.viewColorPreview.setBackgroundColor(currentColor)
            val hex = String.format(Locale.US, "#%02X%02X%02X%02X", a, r, g, b)
            binding.textColorHex.text = hex
            binding.textAlphaVal.text = "$a"
            binding.textRedVal.text = "$r"
            binding.textGreenVal.text = "$g"
            binding.textBlueVal.text = "$b"
        }

        updateUi()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    when (seekBar?.id) {
                        R.id.seek_alpha -> a = progress
                        R.id.seek_red -> r = progress
                        R.id.seek_green -> g = progress
                        R.id.seek_blue -> b = progress
                    }
                    updateUi()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        binding.seekAlpha.setOnSeekBarChangeListener(listener)
        binding.seekRed.setOnSeekBarChangeListener(listener)
        binding.seekGreen.setOnSeekBarChangeListener(listener)
        binding.seekBlue.setOnSeekBarChangeListener(listener)

        // Preset color chips
        binding.chipWhite.setOnClickListener { a = 255; r = 255; g = 255; b = 255; syncSeekBars(binding, a, r, g, b); updateUi() }
        binding.chipGreen.setOnClickListener { a = 255; r = 76; g = 175; b = 80; syncSeekBars(binding, a, r, g, b); updateUi() }
        binding.chipRed.setOnClickListener { a = 255; r = 244; g = 67; b = 54; syncSeekBars(binding, a, r, g, b); updateUi() }
        binding.chipCyan.setOnClickListener { a = 255; r = 0; g = 229; b = 255; syncSeekBars(binding, a, r, g, b); updateUi() }
        binding.chipYellow.setOnClickListener { a = 255; r = 255; g = 235; b = 59; syncSeekBars(binding, a, r, g, b); updateUi() }
        binding.chipTransBlack.setOnClickListener { a = 160; r = 0; g = 0; b = 0; syncSeekBars(binding, a, r, g, b); updateUi() }

        val dialog = builder
            .setTitle(R.string.select_color)
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                onColorSelected(currentColor)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
        return dialog
    }

    private fun syncSeekBars(binding: DialogColorPickerBinding, a: Int, r: Int, g: Int, b: Int) {
        binding.seekAlpha.progress = a
        binding.seekRed.progress = r
        binding.seekGreen.progress = g
        binding.seekBlue.progress = b
    }
}
