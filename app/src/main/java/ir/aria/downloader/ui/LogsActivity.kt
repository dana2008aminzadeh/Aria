package ir.aria.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ir.aria.downloader.databinding.ActivityLogsBinding
import ir.aria.downloader.util.AppLog
import java.io.File

/** Shows the diagnostic log and lets the user copy, clear or share it as a file. */
class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.shareLogsButton.setOnClickListener { shareLogs() }
        binding.copyLogsButton.setOnClickListener { copyLogs() }
        binding.clearLogsButton.setOnClickListener {
            AppLog.clear()
            refresh()
            Toast.makeText(this, "لاگ پاک شد", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val text = AppLog.text()
        binding.logsText.text = if (text.isBlank()) "هنوز لاگی ثبت نشده است." else text
        binding.logsScroll.post { binding.logsScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun copyLogs() {
        val text = AppLog.text()
        if (text.isBlank()) {
            Toast.makeText(this, "لاگی برای کپی نیست", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AllDown log", text))
        Toast.makeText(this, "لاگ کپی شد", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val source = AppLog.currentFile()
        if (source == null) {
            Toast.makeText(this, "لاگی برای ارسال نیست", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val export = File(cacheDir, "alldown-log.txt")
            source.copyTo(export, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", export)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AllDown diagnostic log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "ارسال لاگ"))
        } catch (e: Exception) {
            AppLog.log("لاگ", "ارسال لاگ ناموفق بود", e)
            Toast.makeText(this, "ارسال لاگ ناموفق بود؛ از کپی استفاده کنید", Toast.LENGTH_LONG).show()
        }
    }
}
