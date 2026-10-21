package com.teledrive.files.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.teledrive.core.model.PdfByteSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min
import kotlin.math.roundToInt

/** Raised when the platform renderer needs a password that we have not supplied yet. */
class PdfPasswordRequiredException : IOException("This PDF is password protected")

/** Raised when this Android version cannot open the document's encryption. */
class PdfEncryptionUnsupportedException : IOException("This PDF uses encryption this Android version cannot open")

/** Random-access, chunked reader used by the outline parser; never loads the whole file. */
class FilePdfByteSource(private val file: File) : PdfByteSource, Closeable {
    private val handle = RandomAccessFile(file, "r")
    override val size: Long get() = handle.length()

    override fun read(offset: Long, count: Int): ByteArray {
        if (offset < 0L || count <= 0) return ByteArray(0)
        return synchronized(handle) {
            val available = size - offset
            if (available <= 0L) return@synchronized ByteArray(0)
            val wanted = min(count.toLong(), available).toInt()
            val buffer = ByteArray(wanted)
            handle.seek(offset)
            handle.readFully(buffer)
            buffer
        }
    }

    override fun close() = handle.close()
}

/** Text hits for one page, already resolved to page-space rectangles. */
data class PdfPageSearchHits(
    val matchCount: Int,
    val bounds: List<RectF>,
    /** Page width in points, needed to scale highlights onto the rendered bitmap. */
    val pageWidthPoints: Int,
)

/**
 * Thin, single-owner wrapper over the platform renderer.
 *
 *  - the ParcelFileDescriptor and the renderer are closed together,
 *  - only one page is open at a time (a platform constraint),
 *  - bitmaps are sized by the caller and never exceed [maxDimensionPx],
 *  - no API touches the document after [close].
 */
class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
) : Closeable {
    val pageCount: Int = renderer.pageCount
    val firstPageWidthPoints: Int
    val firstPageHeightPoints: Int

    private var closed = false

    init {
        val page = renderer.openPage(0)
        try {
            firstPageWidthPoints = page.width.coerceAtLeast(1)
            firstPageHeightPoints = page.height.coerceAtLeast(1)
        } finally {
            page.close()
        }
    }

    /** Renders one page at [targetWidthPx]; the caller owns the returned bitmap. */
    fun renderPage(pageIndex: Int, targetWidthPx: Int, maxDimensionPx: Int = 4096): Bitmap {
        check(!closed) { "PdfDocument is closed" }
        require(pageIndex in 0 until pageCount) { "Page index out of range" }
        val width = targetWidthPx.coerceIn(64, maxDimensionPx)
        val page = renderer.openPage(pageIndex)
        try {
            var bitmapWidth = width
            var bitmapHeight = (width.toDouble() * page.height / page.width).roundToInt().coerceAtLeast(1)
            if (bitmapHeight > maxDimensionPx) {
                bitmapHeight = maxDimensionPx
                bitmapWidth = (page.width.toDouble() * bitmapHeight / page.height).roundToInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            // The platform page must be rendered inside the bitmap bounds.
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    /**
     * Text search needs the API 35 platform implementation. On older Android the
     * caller must show that search is unavailable instead of pretending to scan.
     */
    fun searchPage(pageIndex: Int, query: String): PdfPageSearchHits {
        check(!closed) { "PdfDocument is closed" }
        require(pageIndex in 0 until pageCount) { "Page index out of range" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return PdfPageSearchHits(0, emptyList(), 0)
        val page = renderer.openPage(pageIndex)
        try {
            val matches = PageSearchApi35.search(page, query)
            return PdfPageSearchHits(
                matchCount = matches.size,
                bounds = matches.flatMap { it.bounds },
                pageWidthPoints = page.width,
            )
        } finally {
            page.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * Opens [file]. A [password] is only usable on API 35+, where the platform exposes
         * [LoadParams]; on older versions the caller receives a typed unsupported error.
         */
        fun open(file: File, password: String? = null): PdfDocument {
            if (!file.isFile || !file.canRead()) throw IOException("The local PDF is no longer readable")
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = if (password == null) {
                    PdfRenderer(descriptor)
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        throw PdfEncryptionUnsupportedException()
                    }
                    PasswordApi35.createRenderer(descriptor, password)
                }
                return PdfDocument(renderer, descriptor)
            } catch (error: PdfPasswordRequiredException) {
                descriptor.close()
                throw error
            } catch (error: PdfEncryptionUnsupportedException) {
                descriptor.close()
                throw error
            } catch (error: SecurityException) {
                descriptor.close()
                // The platform reports a missing or incorrect password as SecurityException.
                throw if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    PdfPasswordRequiredException()
                } else {
                    PdfEncryptionUnsupportedException()
                }
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
        }
    }
}

/**
 * Isolated so the API 35 classes are only resolved when a compatible device calls them.
 * Types are resolved lazily; keeping them in a separate object prevents older runtimes
 * from verifying the main class against missing framework classes.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object PasswordApi35 {
    fun createRenderer(descriptor: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object PageSearchApi35 {
    fun search(page: PdfRenderer.Page, query: String): List<android.graphics.pdf.models.PageMatchBounds> =
        page.searchText(query)
}
