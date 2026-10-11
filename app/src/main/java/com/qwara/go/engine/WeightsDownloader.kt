// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 权重的 HTTP 下载器：逐文件下载到 `<name>.part`，SHA-256 校验通过才转正，
 * 支持断点续传（Range）与多源自动切换。纯 JVM 实现（无 Android 依赖），供 JVM 单测覆盖。
 *
 * 取消（协程 CancellationException）会立即中断并保留 .part，下次从断点继续。
 */
class WeightsDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    sealed interface Result {
        data object Success : Result
        data class Failed(val message: String) : Result
    }

    data class Progress(
        /** 正在下载的文件下标（0 起） */
        val fileIndex: Int,
        val fileName: String,
        /** 当前文件已有字节（含上次断点） */
        val fileDone: Long,
        val fileTotal: Long,
        /** 全部文件累计已有字节（含已完成文件与断点） */
        val totalDone: Long,
        val totalBytes: Long,
        val sourceLabel: String,
    )

    /**
     * 下载 [files] 到 [targetDir]。按顺序处理每个文件；每个文件在 [sources] 里依次尝试，
     * 直到有一个源下载并校验成功，全部源失败则返回 [Result.Failed]。
     * 已存在且校验通过的目标文件直接跳过（上次下到一半被杀进程的场景）。
     */
    suspend fun downloadAll(
        files: List<EngineWeights.FileSpec>,
        targetDir: File,
        sources: List<EngineWeights.Source>,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "no download sources" }
        targetDir.mkdirs()
        val totalBytes = files.sumOf { it.size }
        // 已完成文件与断点都计入总进度
        var doneBefore = 0L
        for ((index, spec) in files.withIndex()) {
            // 文件间的取消检查点：让「删除权重」与取消在文件切换处即时生效，
            // 避免已取消的任务继续写下一个 .part
            currentCoroutineContext().ensureActive()
            val target = File(targetDir, spec.name)
            if (target.isFile && target.length() == spec.size && sha256(target) == spec.sha256) {
                doneBefore += spec.size
                continue
            }
            target.delete()
            var lastError = "无可用下载源"
            var ok = false
            for (source in sources) {
                val part = File(targetDir, spec.name + PART_SUFFIX)
                val progress: (Long, Long) -> Unit = { fileDone, fileTotal ->
                    onProgress(
                        Progress(
                            fileIndex = index,
                            fileName = spec.name,
                            fileDone = fileDone,
                            fileTotal = fileTotal,
                            totalDone = doneBefore + fileDone,
                            totalBytes = totalBytes,
                            sourceLabel = source.label,
                        ),
                    )
                }
                try {
                    fetchFrom(spec, source.urlOf(spec.name), part, source.supportsRange, progress)
                    if (part.length() != spec.size) {
                        throw IOException("大小不符：${part.length()} != ${spec.size}")
                    }
                    if (sha256(part) != spec.sha256) {
                        // 内容被改（坏镜像/劫持）：断点不可信，删掉换个源从头来
                        part.delete()
                        throw IOException("SHA-256 校验失败")
                    }
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    ok = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = "${source.label}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (!ok) return@withContext Result.Failed("${spec.name} 下载失败（$lastError）")
            doneBefore += spec.size
        }
        Result.Success
    }

    /** 从单个源把 [spec] 下到 [part]（含断点续传）；只负责写字节，校验在调用方。
     *  [allowRange] 为 false 的源不发 Range：已有的 .part 被放弃，整量重下。 */
    private suspend fun fetchFrom(
        spec: EngineWeights.FileSpec,
        url: String,
        part: File,
        allowRange: Boolean,
        onBytes: (done: Long, total: Long) -> Unit,
    ) {
        var resumeFrom = if (allowRange && part.isFile) part.length() else 0L
        if (resumeFrom > spec.size) {
            part.delete()
            resumeFrom = 0
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // 不用压缩编码：Content-Length 即字节数，进度才准确
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Mozilla/5.0 (gomoku-watch)")
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // 206 也可能是假的：jsDelivr 的边缘缓存会对压缩变体做 Range，
                    // Content-Range 里的总长≠文件大小、内容是压缩流切片。校验起点、终点与总长，
                    // 不可信就当源失败换下一个（.part 里已有的字节是好的，不删）。
                    val cr = conn.getHeaderField("Content-Range") ?: ""
                    val m = CONTENT_RANGE.matcher(cr)
                    val startOk = m.matches() && m.group(1)!!.toLong() == resumeFrom
                    val endOk = m.matches() && m.group(2)!!.toLong() == spec.size - 1
                    val totalOk = m.matches() && (m.group(3) == "*" || m.group(3)!!.toLong() == spec.size)
                    if (!startOk || !endOk || !totalOk) throw IOException("Range 响应不可信: $cr")
                }
                HttpURLConnection.HTTP_OK -> resumeFrom = 0 // 源不支持 Range：从头下
                416 -> {
                    if (resumeFrom == spec.size) return // 已完整，交给校验
                    part.delete()
                    throw IOException("断点越界（416），已清除重下")
                }
                else -> throw IOException("HTTP $code")
            }
            onBytes(resumeFrom, spec.size)
            var done = resumeFrom
            var lastPush = System.nanoTime()
            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(resumeFrom)
                    if (resumeFrom == 0L) out.setLength(0)
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        currentCoroutineContext().ensureActive()
                        val now = System.nanoTime()
                        // 进度回调节流：手表上每次回调都是一次整屏重组
                        if (now - lastPush >= PROGRESS_MIN_INTERVAL_NS) {
                            lastPush = now
                            onBytes(done, spec.size)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            onBytes(done, spec.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val PROGRESS_MIN_INTERVAL_NS = 150_000_000L
        private val CONTENT_RANGE =
            java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}
