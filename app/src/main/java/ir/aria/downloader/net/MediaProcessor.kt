package ir.aria.downloader.net

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException

/** Executes real on-device remuxing/conversion; argument arrays avoid shell quoting bugs. */
object MediaProcessor {

    class ProcessingException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun merge(video: File, audio: File, output: File): File =
        execute(MediaCommands.merge(video, audio, output), output)

    fun remux(input: File, output: File, includeAudio: Boolean = true): File =
        execute(MediaCommands.remux(input, output, includeAudio), output)

    fun convertAudio(input: File, output: File, ext: String, bitrateKbps: Int): File =
        execute(MediaCommands.audio(input, output, ext, bitrateKbps), output)

    private fun execute(arguments: List<String>, output: File): File {
        output.parentFile?.mkdirs()
        output.delete()
        val session = try {
            FFmpegKit.executeWithArguments(arguments.toTypedArray())
        } catch (t: Throwable) {
            throw ProcessingException("FFmpeg could not start", t)
        }
        if (!ReturnCode.isSuccess(session.returnCode) || !output.isFile || output.length() <= 0L) {
            output.delete()
            val detail = session.output?.takeLast(500).orEmpty()
            throw ProcessingException("FFmpeg failed (rc=${session.returnCode}) $detail")
        }
        return output
    }
}
