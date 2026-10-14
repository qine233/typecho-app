package noline.zhouzhou.ui.screens.drive

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 从 SAF 选来的一个本地文件，给上传当分片来源。
 * 只顺着开一条流按顺序读：内容提供器的流不一定支持随机定位，别指望 seek，
 * 而 Cloudreve 的分片本来就是从头到尾一片一片发的。
 */
class DriveSource private constructor(
    val name: String,
    val size: Long,
    val mimeType: String,
    private val resolver: ContentResolver,
    private val document: Uri
) {

    private var stream: InputStream? = null

    /** start 只是形参（对齐上传接口的签名），真正的位置由这条流自己走到哪儿决定。 */
    suspend fun read(start: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = stream ?: resolver.openInputStream(document)?.also { stream = it }
            ?: throw IllegalStateException("这个文件已经打不开了")
        input.readNBytes(length)
    }

    fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    companion object {
        /** 拿不到元数据就返回 null，让界面说一句人话，而不是半途抛异常。 */
        fun of(resolver: ContentResolver, document: Uri): DriveSource? {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val meta = runCatching {
                resolver.query(document, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val rawName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val bytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    (rawName?.takeIf { it.isNotBlank() } ?: document.lastPathSegment ?: "") to bytes
                }
            }.getOrNull() ?: return null

            val name = sanitizeDriveName(meta.first)
            if (name.isBlank()) return null
            val mime = runCatching { resolver.getType(document) }.getOrNull()
                ?.takeIf { it.contains('/') && !it.endsWith("*") }
                ?: "application/octet-stream"
            return DriveSource(name, meta.second, mime, resolver, document)
        }
    }
}

/** 网盘路径的最后一段就是文件名，斜杠和首尾的点必须抹掉，空名干脆判死。 */
fun sanitizeDriveName(raw: String): String =
    raw.replace('/', '-').replace('\\', '-').trim().trim('.').ifBlank { "未命名文件" }
