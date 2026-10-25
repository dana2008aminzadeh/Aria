package ir.aria.downloader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.aria.downloader.R
import ir.aria.downloader.diag.DiagLog
import ir.aria.downloader.engine.FormatOptions
import ir.aria.downloader.engine.ExtractorEngine

/** Two-stage real-format -> real-output picker for one link/track. */
object FormatChooserDialog {

    fun show(
        context: Context,
        title: String,
        formats: List<ExtractorEngine.FormatRow>,
        audioOnly: Boolean,
        onResult: (ExtractorEngine.FormatSelection?) -> Unit
    ): AlertDialog? {
        val choices = FormatOptions.choices(formats, audioOnly)
        DiagLog.i(
            "Chooser",
            "show title='" + title + "' audioOnly=$audioOnly formatRows=" + formats.size +
                " choices=" + choices.size +
                choices.joinToString("") { "\n choice: " + it.label + " | " + it.detail }
        )
        if (choices.isEmpty()) {
            DiagLog.w("Chooser", "no choices available -> null result")
            onResult(null)
            return null
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_format_chooser, null, false)
        val formatSpinner = view.findViewById<Spinner>(R.id.formatSpinner)
        val outputSpinner = view.findViewById<Spinner>(R.id.outputSpinner)
        val formatDetail = view.findViewById<TextView>(R.id.formatDetail)
        val outputDetail = view.findViewById<TextView>(R.id.outputDetail)
        view.findViewById<TextView>(R.id.mediaTitle).text = title
        view.findViewById<TextView>(R.id.formatSummary).text =
            context.getString(R.string.formats_available, choices.size)

        var selectedChoice = choices.first()
        var outputs = FormatOptions.outputs(selectedChoice, audioOnly)
        var finished = false
        fun finish(result: ExtractorEngine.FormatSelection?) {
            if (finished) return
            finished = true
            DiagLog.i(
                "Chooser",
                "finish -> " + (result?.let {
                    "fmt=" + it.formatId + " afmt=" + it.audioFormatId + " out=" + it.outputKind +
                        "/" + it.outputExt + " abr=" + it.audioBitrateKbps + " label=[" + it.label + "]"
                } ?: "null (cancelled/dismissed)")
            )
            onResult(result)
        }

        formatSpinner.adapter = ChoiceAdapter(context, choices)
        outputSpinner.adapter = OutputAdapter(context, outputs)
        formatDetail.text = selectedChoice.detail
        outputDetail.text = outputs.firstOrNull()?.detail.orEmpty()

        formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                selectedChoice = choices[position]
                DiagLog.d("Chooser", "format picked [$position] " + selectedChoice.label)
                outputs = FormatOptions.outputs(selectedChoice, audioOnly)
                outputSpinner.adapter = OutputAdapter(context, outputs)
                formatDetail.text = selectedChoice.detail
                outputDetail.text = outputs.firstOrNull()?.detail.orEmpty()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                DiagLog.d("Chooser", "output picked [$position] " + outputs.getOrNull(position)?.label)
                outputDetail.text = outputs.getOrNull(position)?.detail.orEmpty()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (audioOnly) R.string.choose_audio_output else R.string.choose_format_output)
            .setView(view)
            .setPositiveButton(R.string.add_to_queue) { _, _ ->
                val output = outputs.getOrNull(outputSpinner.selectedItemPosition) ?: outputs.first()
                finish(output.selection(selectedChoice))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(null) }
            .setOnCancelListener { finish(null) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            // Also completes a suspended caller if the activity/dialog is dismissed externally.
            finish(null)
        }
        dialog.show()
        return dialog
    }

    private class ChoiceAdapter(
        context: Context,
        private val values: List<FormatOptions.Choice>
    ) : ArrayAdapter<FormatOptions.Choice>(context, 0, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(position, convertView, parent, compact = true)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(position, convertView, parent, compact = false)

        private fun bind(position: Int, convertView: View?, parent: ViewGroup, compact: Boolean): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_format_option, parent, false)
            val value = values[position]
            view.findViewById<TextView>(R.id.optionTitle).text = value.label
            view.findViewById<TextView>(R.id.optionDetail).apply {
                text = value.detail
                visibility = if (compact) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    private class OutputAdapter(
        context: Context,
        private val values: List<FormatOptions.Output>
    ) : ArrayAdapter<FormatOptions.Output>(context, 0, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(position, convertView, parent, compact = true)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(position, convertView, parent, compact = false)

        private fun bind(position: Int, convertView: View?, parent: ViewGroup, compact: Boolean): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_format_option, parent, false)
            val value = values[position]
            view.findViewById<TextView>(R.id.optionTitle).text = value.label
            view.findViewById<TextView>(R.id.optionDetail).apply {
                text = value.detail
                visibility = if (compact) View.GONE else View.VISIBLE
            }
            return view
        }
    }
}
