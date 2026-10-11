// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 assets 中的 Rapfi 配置文件解压到应用私有目录，并跟踪大权重的就绪状态。
 * 引擎本体是 JNI 库（jniLibs 中的 librapfi.so，由系统自动装载），这里只准备数据文件：
 * config.toml 与 NNUE/经典评估权重（Rapfi 要求可从工作目录找到）。
 *
 * 三个 ~10MB 的 .bin.lz4 默认不打进 APK（见 EngineWeights），首次需要引擎时由
 * 权重下载页联网下载；`-PbundleWeights=true` 的构建仍把它们打进 assets，这里照旧解压。
 */
object EngineInstaller {

    private const val TAG = "EngineInstaller"
    private const val ASSET_COMMON = "engine/common"
    private const val STAMP = "weights-v1.stamp"

    /** 始终随包内置的小文件（无 stamp 时每次补解压，共 ~37KB） */
    private val BUNDLED_SMALL_FILES = listOf("config.toml", "model210901.bin")

    /** 缺失即无法启动引擎的文件；用于判断已安装内容是否完整。
     *  三个 ~10MB 的 .lz4 权重必须在内：否则下载/解压中途失败时只有小文件通过校验，
     *  stamp 照写，残缺目录被固化成永久损坏（引擎永远启动失败且不会自愈） */
    private val REQUIRED_FILES = BUNDLED_SMALL_FILES + EngineWeights.FILES.map { it.name }

    class WeightsNotInstalledException(val missing: List<String>) :
        Exception("engine weights not installed: $missing")

    sealed interface WeightsStatus {
        /** 全部就绪；[sizeOnDisk] 为大权重占用的字节数 */
        data class Ready(val sizeOnDisk: Long) : WeightsStatus

        /** 缺权重（含从未下载与下载中/中断）；[resumedBytes] 为 .part 里已有的字节，用于续传进度展示 */
        data class Missing(val missing: List<EngineWeights.FileSpec>, val resumedBytes: Long) : WeightsStatus
    }

    @Volatile
    private var weightsDir: File? = null

    /** 引擎启动路径的兜底：确保数据文件齐全，缺权重则抛 [WeightsNotInstalledException]。 */
    @Synchronized
    fun ensureWeightsInstalled(context: Context): File {
        weightsDir?.let { return it }
        val target = File(context.filesDir, "engine")
        val stamp = File(target, STAMP)
        if (stamp.exists() && REQUIRED_FILES.all { File(target, it).isFile }) {
            weightsDir = target
            return target
        }
        target.mkdirs()
        copyAssetTree(context.assets, ASSET_COMMON, target)
        // 解压/下载不完整就不要写戳，否则会一直以残缺目录启动引擎
        val missing = REQUIRED_FILES.filterNot { File(target, it).isFile }
        if (missing.isNotEmpty()) throw WeightsNotInstalledException(missing)
        stamp.writeText("ok")
        weightsDir = target
        return target
    }

    /** UI/门控用的快速状态检查：只校验大权重存在且大小与清单一致（不做 hash），必要时补小文件自愈。 */
    @Synchronized
    fun status(context: Context): WeightsStatus {
        val target = File(context.filesDir, "engine")
        val weightsOk = EngineWeights.FILES.all {
            val f = File(target, it.name)
            f.isFile && f.length() == it.size
        }
        if (weightsOk) {
            // 大权重齐了：小文件缺失只可能出现在异常状态，补齐即可（37KB，不走整树拷贝，
            // 避免 bundleWeights 构建下每次状态检查都白拷 30MB）
            for (name in BUNDLED_SMALL_FILES) {
                if (!File(target, name).isFile) copyAssetFile(context.assets, "$ASSET_COMMON/$name", target)
            }
            val stamp = File(target, STAMP)
            if (!stamp.exists()) stamp.writeText("ok") // 文件都在只是缺戳（如旧版升级）：补戳即就绪
            val size = EngineWeights.FILES.sumOf { File(target, it.name).length() }
            return WeightsStatus.Ready(size)
        }
        val missing = EngineWeights.FILES.filter { spec ->
            val f = File(target, spec.name)
            !f.isFile || f.length() != spec.size
        }
        val resumed = missing.sumOf { File(target, it.name + WeightsDownloader.PART_SUFFIX).length().coerceAtLeast(0) }
        return WeightsStatus.Missing(missing, resumed)
    }

    /** 权重下载并逐文件校验通过后由 UI 层调用：补小文件并写戳。 */
    @Synchronized
    fun markInstalled(context: Context) {
        val target = File(context.filesDir, "engine")
        target.mkdirs()
        for (name in BUNDLED_SMALL_FILES) {
            copyAssetFile(context.assets, "$ASSET_COMMON/$name", target)
        }
        val missing = REQUIRED_FILES.filterNot { File(target, it).isFile }
        check(missing.isEmpty()) { "weights incomplete after download: $missing" }
        File(target, STAMP).writeText("ok")
        weightsDir = target
    }

    /** 删除大权重与 stamp（保留 37KB 的 config/model 小文件），释放空间或准备重新下载。 */
    @Synchronized
    fun clearWeights(context: Context) {
        val target = File(context.filesDir, "engine")
        for (spec in EngineWeights.FILES) {
            File(target, spec.name).delete()
            File(target, spec.name + WeightsDownloader.PART_SUFFIX).delete()
        }
        File(target, STAMP).delete()
        weightsDir = null
    }

    private fun copyAssetFile(am: AssetManager, path: String, outDir: File) {
        val out = File(outDir, path.substringAfterLast('/'))
        try {
            am.open(path).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Log.d(TAG, "copied $path")
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "skip non-file asset $path", e)
        }
    }

    private fun copyAssetTree(am: AssetManager, path: String, outDir: File) {
        val entries = am.list(path)
        if (!entries.isNullOrEmpty()) {
            for (e in entries) {
                copyAssetTree(am, "$path/$e", outDir)
            }
            return
        }
        // 叶子节点：list() 对文件可能返回 null 或空数组，两种情况都按文件处理
        copyAssetFile(am, path, outDir)
    }
}
