// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WeightsDownloader 的 JVM 集成测试：用 JDK 内置 HttpServer 模拟下载源，
 * 覆盖正常下载+校验、hash 不匹配、Range 断点续传、多源切换。
 */
class WeightsDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var dir: File

    /** path → 要返回的文件字节 */
    private val payloads = HashMap<String, ByteArray>()

    /** path → 行为：null=正常(支持 Range)，"404"=不存在，"norange"=无视 Range 永远 200 全量，
     *  "bad206"=206 但 Content-Range 总长与内容都对不上（jsDelivr 边缘缓存的压缩变体 Range），
     *  "slow"=200 小 chunk 慢速吐字节（取消测试用），"short"=200 但提前 EOF（Content-Length 报大） */
    private val modes = HashMap<String, String>()

    /** 收到过 Range 头的 path（验证 supportsRange=false 的源不发 Range） */
    private val rangeSeen = HashSet<String>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("weights-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = payloads[path]
        if (body == null || modes[path] == "404") {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null) rangeSeen += path
        val from = range?.removePrefix("bytes=")?.substringBefore("-")?.toLongOrNull()
        if (from != null && modes[path] == "bad206") {
            exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size - 1}")
            exchange.sendResponseHeaders(206, 50)
            exchange.responseBody.use { it.write(ByteArray(50)) }
        } else if (from == null && modes[path] == "slow") {
            // 慢速源：小 chunk + sleep，给取消测试留出时间窗；客户端断开后写会抛异常，直接结束
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { out ->
                var off = 0
                while (off < body.size) {
                    val n = minOf(4096, body.size - off)
                    try {
                        out.write(body, off, n)
                        out.flush()
                    } catch (e: Exception) {
                        break
                    }
                    off += n
                    Thread.sleep(5)
                }
            }
        } else if (from == null && modes[path] == "short") {
            // Content-Length 报了全量但提前 EOF（坏镜像/边缘缓存截断）
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body, 0, body.size - 10_000) }
        } else if (from != null && modes[path] != "norange") {
            when {
                from >= body.size -> exchange.sendResponseHeaders(416, -1)
                else -> {
                    exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    exchange.sendResponseHeaders(206, body.size - from)
                    exchange.responseBody.use { it.write(body, from.toInt(), (body.size - from).toInt()) }
                }
            }
        } else {
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        exchange.close()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun spec(name: String, data: ByteArray) = EngineWeights.FileSpec(
        name = name,
        size = data.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
    )

    private fun source(label: String = "test") = EngineWeights.Source(label) { name -> url("/$name") }

    @Test
    fun `download and verify one file`() = runBlocking {
        val data = Random.nextBytes(200_000)
        payloads["/a.bin"] = data
        val result = WeightsDownloader().downloadAll(listOf(spec("a.bin", data)), dir, listOf(source()))
        assertEquals(WeightsDownloader.Result.Success, result)
        assertTrue(File(dir, "a.bin").isFile)
        assertEquals(data.size.toLong(), File(dir, "a.bin").length())
        assertFalse(File(dir, "a.bin.part").exists())
    }

    @Test
    fun `hash mismatch fails and leaves no final file`() = runBlocking {
        val data = Random.nextBytes(100_000)
        payloads["/b.bin"] = data
        // 清单里的 hash 故意写错（模拟镜像投毒/文件被换）
        val bad = spec("b.bin", data).copy(sha256 = "00".repeat(32))
        val result = WeightsDownloader().downloadAll(listOf(bad), dir, listOf(source()))
        assertTrue(result is WeightsDownloader.Result.Failed)
        assertFalse(File(dir, "b.bin").exists())
        // 校验失败的断点不可信，应被删除而不是留着续传
        assertFalse(File(dir, "b.bin.part").exists())
    }

    @Test
    fun `resume from partial file`() = runBlocking {
        val data = Random.nextBytes(300_000)
        payloads["/c.bin"] = data
        // 预置半截断点
        val cut = 123_456
        File(dir, "c.bin.part").writeBytes(data.copyOf(cut))
        val result = WeightsDownloader().downloadAll(listOf(spec("c.bin", data)), dir, listOf(source()))
        assertEquals(WeightsDownloader.Result.Success, result)
        // 必须真的发出了 Range 请求（靠进度回调判断是假阳性：整量重下下到一半也会超过断点）
        assertTrue("应从断点续传（服务端收到 Range 头）", rangeSeen.contains("/c.bin"))
        assertTrue(File(dir, "c.bin").isFile)
        assertEquals(data.toList(), File(dir, "c.bin").readBytes().toList())
    }

    @Test
    fun `server without range support restarts from scratch`() = runBlocking {
        val data = Random.nextBytes(150_000)
        payloads["/d.bin"] = data
        modes["/d.bin"] = "norange"
        File(dir, "d.bin.part").writeBytes(data.copyOf(50_000))
        val result = WeightsDownloader().downloadAll(listOf(spec("d.bin", data)), dir, listOf(source()))
        assertEquals(WeightsDownloader.Result.Success, result)
        assertEquals(data.toList(), File(dir, "d.bin").readBytes().toList())
    }

    @Test
    fun `falls over to next source on http error`() = runBlocking {
        val data = Random.nextBytes(80_000)
        payloads["/e.bin"] = data
        modes["/e.bin"] = "404" // 第一个源 404
        val dead = EngineWeights.Source("dead") { name -> url("/$name") }
        // 第二个源用另一组 path，正常
        payloads["/ok/e.bin"] = data
        val good = EngineWeights.Source("good") { name -> url("/ok/$name") }
        val result = WeightsDownloader().downloadAll(listOf(spec("e.bin", data)), dir, listOf(dead, good))
        assertEquals(WeightsDownloader.Result.Success, result)
        assertTrue(File(dir, "e.bin").isFile)
    }

    @Test
    fun `lying 206 does not corrupt the partial file and falls over`() = runBlocking {
        val data = Random.nextBytes(200_000)
        payloads["/h.bin"] = data
        modes["/h.bin"] = "bad206" // 第一个源：假 206（jsDelivr 式）
        payloads["/good/h.bin"] = data
        val bad = EngineWeights.Source("bad") { name -> url("/$name") }
        val good = EngineWeights.Source("good") { name -> url("/good/$name") }
        val cut = 80_000
        File(dir, "h.bin.part").writeBytes(data.copyOf(cut))
        val result = WeightsDownloader().downloadAll(listOf(spec("h.bin", data)), dir, listOf(bad, good))
        assertEquals(WeightsDownloader.Result.Success, result)
        // 假 206 没有污染断点：第二个源从断点续传（发了 Range）后字节与原件一致
        assertTrue("第二个源应保留断点续传，而不是删了重下", rangeSeen.contains("/good/h.bin"))
        assertEquals(data.toList(), File(dir, "h.bin").readBytes().toList())
    }

    @Test
    fun `source without range support never sends Range and redownloads fully`() = runBlocking {
        val data = Random.nextBytes(120_000)
        payloads["/i.bin"] = data
        File(dir, "i.bin.part").writeBytes(data.copyOf(60_000)) // 有断点也不该用
        val noRange = EngineWeights.Source("norangesrc", supportsRange = false) { name -> url("/$name") }
        val result = WeightsDownloader().downloadAll(listOf(spec("i.bin", data)), dir, listOf(noRange))
        assertEquals(WeightsDownloader.Result.Success, result)
        assertEquals(data.toList(), File(dir, "i.bin").readBytes().toList())
        assertFalse("supportsRange=false 的源不应收到 Range 头", rangeSeen.contains("/i.bin"))
    }

    @Test
    fun `all sources failing reports failure`() = runBlocking {
        val data = Random.nextBytes(10_000)
        val result = WeightsDownloader().downloadAll(listOf(spec("f.bin", data)), dir, listOf(source()))
        assertTrue(result is WeightsDownloader.Result.Failed)
        assertFalse(File(dir, "f.bin").exists())
    }

    @Test
    fun `already complete file is skipped`() = runBlocking {
        val data = Random.nextBytes(60_000)
        File(dir, "g.bin").writeBytes(data)
        // 服务端根本没有这个文件：任何下载尝试都会 404
        val result = WeightsDownloader().downloadAll(listOf(spec("g.bin", data)), dir, listOf(source()))
        assertEquals(WeightsDownloader.Result.Success, result)
        assertEquals(data.toList(), File(dir, "g.bin").readBytes().toList())
    }

    @Test
    fun `cancellation keeps the partial file for resume`() = runBlocking {
        val data = Random.nextBytes(2_000_000)
        payloads["/j.bin"] = data
        modes["/j.bin"] = "slow" // 慢速吐字节，保证能在下载途中取消
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val deferred = scope.async {
            WeightsDownloader().downloadAll(listOf(spec("j.bin", data)), dir, listOf(source())) { p ->
                if (p.fileDone > 0) job.cancel()
            }
        }
        try {
            deferred.await()
            throw AssertionError("取消后 downloadAll 应抛 CancellationException")
        } catch (e: CancellationException) {
            // 预期路径
        }
        // 取消保留 .part 断点（下次续传），且不产生最终文件
        val part = File(dir, "j.bin.part")
        assertTrue("取消应保留 .part 断点", part.isFile)
        assertTrue("断点应有已下载的字节", part.length() > 0)
        assertFalse(File(dir, "j.bin").exists())
    }

    @Test
    fun `416 with complete-size part is finalized after hash check`() = runBlocking {
        val data = Random.nextBytes(80_000)
        payloads["/k.bin"] = data
        // 尺寸已完整但还没校验过（上次下完最后一刻被杀）：416 = 没有更多字节可给
        File(dir, "k.bin.part").writeBytes(data)
        val result = WeightsDownloader().downloadAll(listOf(spec("k.bin", data)), dir, listOf(source()))
        assertEquals(WeightsDownloader.Result.Success, result)
        assertTrue(rangeSeen.contains("/k.bin"))
        assertEquals(data.toList(), File(dir, "k.bin").readBytes().toList())
        assertFalse(File(dir, "k.bin.part").exists())
    }

    @Test
    fun `416 with out-of-range part clears it and reports failure`() = runBlocking {
        val data = Random.nextBytes(60_000)
        payloads["/l.bin"] = data
        // 清单说 100KB、源只有 60KB、本地断点 70KB：越界 416 → 断点作废删除，源失败
        val wrongSize = spec("l.bin", data).copy(size = 100_000)
        File(dir, "l.bin.part").writeBytes(Random.nextBytes(70_000))
        val result = WeightsDownloader().downloadAll(listOf(wrongSize), dir, listOf(source()))
        assertTrue(result is WeightsDownloader.Result.Failed)
        assertFalse("越界断点应被删除", File(dir, "l.bin.part").exists())
        assertFalse(File(dir, "l.bin").exists())
    }

    @Test
    fun `early eof leaves a valid prefix that the next source resumes`() = runBlocking {
        val data = Random.nextBytes(200_000)
        payloads["/m.bin"] = data
        modes["/m.bin"] = "short" // 第一个源：Content-Length 报全量但提前 EOF
        payloads["/good/m.bin"] = data
        val bad = EngineWeights.Source("bad") { name -> url("/$name") }
        val good = EngineWeights.Source("good") { name -> url("/good/$name") }
        val result = WeightsDownloader().downloadAll(listOf(spec("m.bin", data)), dir, listOf(bad, good))
        assertEquals(WeightsDownloader.Result.Success, result)
        // 截断源留下的是有效前缀：第二个源从断点续传（发了 Range），最终字节完整
        assertTrue(rangeSeen.contains("/good/m.bin"))
        assertEquals(data.toList(), File(dir, "m.bin").readBytes().toList())
    }
}
