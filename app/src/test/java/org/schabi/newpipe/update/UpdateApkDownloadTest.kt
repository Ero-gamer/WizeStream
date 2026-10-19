package org.schabi.newpipe.update

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UpdateApkDownloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val url = "https://example.com/update.apk"

    @Test
    fun cancellationInterruptsABlockedResponseAndDeletesThePartialFile() {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.countDown()
                if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("Timed out")
                throw IOException("Cancelled")
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = 100L
            override fun source() = source
        }
        val call = responseCall(body)
        doAnswer {
            cancelled.countDown()
            null
        }.`when`(call).cancel()
        val download = UpdateApkDownload { call }
        val output = File(temporaryFolder.root, "partial.apk")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                assertThrows(IOException::class.java) { download.download(url, output, 100) { _, _ -> } }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue(output.exists())
            download.cancel()
            result.get(5, TimeUnit.SECONDS)
            verify(call).cancel()
            assertFalse(output.exists())
        } finally {
            cancelled.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationBeforeStartingPreventsTheRequest() {
        val call = mock(Call::class.java)
        val download = UpdateApkDownload { call }
        download.cancel()
        assertThrows(IOException::class.java) {
            download.download(url, File(temporaryFolder.root, "cancelled.apk"), 1) { _, _ -> }
        }
        verify(call).cancel()
        verify(call, never()).execute()
    }

    @Test
    fun cancellationWhileCreatingTheCallStillPreventsTheRequest() {
        val call = mock(Call::class.java)
        lateinit var download: UpdateApkDownload
        download = UpdateApkDownload {
            download.cancel()
            call
        }
        assertThrows(IOException::class.java) {
            download.download(url, File(temporaryFolder.root, "cancelled.apk"), 1) { _, _ -> }
        }
        verify(call).cancel()
        verify(call, never()).execute()
    }

    @Test
    fun completeDownloadPreservesTheFileAndReportsProgress() {
        val output = File(temporaryFolder.root, "complete.apk")
        val progress = mutableListOf<Pair<Long, Long>>()
        UpdateApkDownload { responseCall("fixture".toResponseBody()) }
            .download(url, output, 7) { downloaded, total -> progress += downloaded to total }
        assertEquals("fixture", output.readText())
        assertEquals(7L to 7L, progress.last())
    }

    @Test
    fun truncatedAndOversizedResponsesAreDeleted() {
        listOf(3L, 20L).forEach { expectedSize ->
            val output = File(temporaryFolder.root, "$expectedSize.apk")
            val download = UpdateApkDownload { responseCall("fixture".toResponseBody()) }
            assertThrows(IOException::class.java) {
                download.download(url, output, expectedSize) { _, _ -> }
            }
            assertFalse(output.exists())
        }
    }

    private fun responseCall(body: ResponseBody): Call = mock(Call::class.java).also { call ->
        `when`(call.execute()).thenReturn(
            Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        )
    }
}
