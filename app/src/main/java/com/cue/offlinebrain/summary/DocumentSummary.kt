package com.cue.offlinebrain.summary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

class DocumentSummary(context: Context, private val summarizer: ExtractiveSummarizer = ExtractiveSummarizer()) {
    private val appContext = context.applicationContext
    init { PDFBoxResourceLoader.init(appContext) }

    suspend fun pdf(uri: Uri, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        onProgress("Opening PDF on this device")
        val stream = appContext.contentResolver.openInputStream(uri) ?: throw IOException("The selected PDF could not be opened.")
        val extracted = stream.use { input ->
            PDDocument.load(input).use { document ->
                val count = minOf(document.numberOfPages, MAX_TEXT_PAGES)
                val text = PDFTextStripper().apply { startPage = 1; endPage = count }.getText(document).take(MAX_EXTRACTED_CHARS)
                Triple(text, count, document.documentInformation?.title.orEmpty())
            }
        }
        if (extracted.first.isNotBlank()) {
            onProgress("Summarising extracted PDF text")
            return@withContext "PDF · first ${extracted.second} page(s)\n\n" + PdfTextCleanup.report(extracted.first, extracted.third).toChatText()
        }
        scannedPdf(uri, onProgress)
    }

    private suspend fun scannedPdf(uri: Uri, onProgress: (String) -> Unit): String {
        val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Scanned PDF OCR failed: Android did not provide a seekable file descriptor.")
        val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) {
            descriptor.close()
            throw IOException("Scanned PDF OCR failed while opening the renderer: ${error.message ?: "invalid PDF"}", error)
        }
        val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val pagesToRead = minOf(renderer.pageCount, MAX_SCANNED_PAGES)
            if (pagesToRead == 0) throw IOException("This PDF has no pages to read.")
            val text = StringBuilder()
            for (index in 0 until pagesToRead) {
                onProgress("Reading scanned PDF page ${index + 1} of $pagesToRead")
                val page = renderer.openPage(index)
                val bitmap = try {
                    val scale = min(1.8, min(MAX_RENDER_WIDTH.toDouble() / page.width, MAX_RENDER_HEIGHT.toDouble() / page.height))
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                        page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } finally { page.close() }
                try {
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                    if (result.text.isNotBlank()) text.append("\n\nPage ${index + 1}:\n").append(result.text)
                } finally { bitmap.recycle() }
            }
            if (text.isBlank()) throw IOException("Scanned PDF OCR completed but no readable text was found in the first $pagesToRead pages.")
            onProgress("Summarising OCR text from $pagesToRead scanned page(s)")
            return "Scanned PDF · OCR · first $pagesToRead page(s)\n\n" + PdfTextCleanup.report(text.toString(), "Scanned document").toChatText()
        } finally {
            runCatching { recognizer.close() }
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    fun web(url: String, focus: String? = null): String {
        require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "Please provide a valid web URL." }
        val page = Jsoup.connect(url).userAgent("OfflineBrainAgent/1.0").timeout(10_000).get()
        page.select("script,style,nav,footer,header,aside,noscript,form").remove()
        val text = page.select("main,article").firstOrNull()?.text()?.takeIf { it.length > 250 } ?: page.body().text()
        if (text.isBlank()) return "I couldn't extract readable text from that page."
        return "Page summary — ${page.title()}:\n" + summarizer.summarize(text, 6, focus) + "\n\nSource: $url"
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        private const val MAX_TEXT_PAGES = 80
        private const val MAX_SCANNED_PAGES = 40
        private const val MAX_RENDER_WIDTH = 1400
        private const val MAX_RENDER_HEIGHT = 1800
        private const val MAX_EXTRACTED_CHARS = 200_000
    }
}
