package org.schabi.newpipe.update

import java.io.File
import java.io.IOException
import okhttp3.Call
import okhttp3.Request

internal class UpdateApkDownload(private val calls: Call.Factory) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    fun download(
        url: String,
        outputFile: File,
        expectedSize: Long,
        progress: (Long, Long) -> Unit
    ) {
        val call = calls.newCall(Request.Builder().url(url).build())
        activeCall = call
        try {
            checkCancellation()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val totalBytes = body.contentLength().takeIf { it >= 0 } ?: -1L
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkCancellation()
                            val read = input.read(buffer)
                            if (read == -1) break
                            checkCancellation()
                            downloadedBytes += read
                            if (downloadedBytes > expectedSize) {
                                throw IOException("Downloaded APK exceeds the release asset size")
                            }
                            output.write(buffer, 0, read)
                            progress(downloadedBytes, totalBytes)
                        }
                    }
                }
                checkCancellation()
                if (downloadedBytes <= 0L || outputFile.length() != downloadedBytes ||
                    downloadedBytes != expectedSize ||
                    (totalBytes >= 0L && downloadedBytes != totalBytes)
                ) {
                    throw IOException("Downloaded APK does not match the release asset size")
                }
            }
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            activeCall = null
        }
    }

    private fun checkCancellation() {
        if (cancelled) {
            activeCall?.cancel()
            throw IOException("Download cancelled")
        }
    }
}
