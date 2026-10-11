// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 assets 中的 pachi 数据文件解压到应用私有目录，并跟踪它们的就绪状态。
 * 引擎本体是 JNI 库（jniLibs 中的 libpachi.so，由系统自动装载），这里只准备数据：
 * 走子模式库（patterns_mm.*）与 opening.dat 开局库，pachi 按工作目录查找它们。
 *
 * 三个数据文件（共 ~2.6MB）默认不打进 APK（见 EngineWeights），首次需要引擎时由
 * 数据下载页联网下载；`-PbundleWeights=true` 的构建仍把它们打进 assets，这里照旧解压。
 * 文件缺失时 pachi 会降级为纯蒙特卡洛（能下但更弱）——UI 层的门控保证用户先下载，
 * 让每个人都拿到完整棋力。
 */
object EngineInstaller {

    private const val TAG = "EngineInstaller"
    private const val ASSET_COMMON = "engine/common"
    private const val STAMP = "pachi-v1.stamp"

    class WeightsNotInstalledException(val missing: List<String>) :
        Exception("engine data files not installed: $missing")

    sealed interface WeightsStatus {
        /** 全部就绪；[sizeOnDisk] 为数据文件占用的字节数 */
        data class Ready(val sizeOnDisk: Long) : WeightsStatus

        /** 缺数据文件（含从未下载与下载中/中断）；[resumedBytes] 为 .part 里已有的字节，用于续传进度展示 */
        data class Missing(val missing: List<EngineWeights.FileSpec>, val resumedBytes: Long) : WeightsStatus
    }

    @Volatile
    private var engineDir: File? = null

    /** 引擎启动路径的兜底：确保数据文件齐全，缺文件则抛 [WeightsNotInstalledException]。 */
    @Synchronized
    fun ensureEngineDataInstalled(context: Context): File {
        engineDir?.let { return it }
        val target = File(context.filesDir, "engine")
        val stamp = File(target, STAMP)
        if (stamp.exists() && EngineWeights.FILES.all { File(target, it.name).isFile }) {
            engineDir = target
            return target
        }
        target.mkdirs()
        Log.i(TAG, "Installing engine data to ${target.absolutePath}")
        copyAssetTree(context.assets, ASSET_COMMON, target)
        // 解压/下载不完整就不要写戳，否则会一直以残缺目录启动引擎
        val missing = EngineWeights.FILES.filterNot { File(target, it.name).isFile }
        if (missing.isNotEmpty()) throw WeightsNotInstalledException(missing.map { it.name })
        stamp.writeText("ok")
        engineDir = target
        return target
    }

    /** UI/门控用的快速状态检查：只校验文件存在且大小与清单一致（不做 hash）。 */
    @Synchronized
    fun status(context: Context): WeightsStatus {
        val target = File(context.filesDir, "engine")
        val ok = EngineWeights.FILES.all {
            val f = File(target, it.name)
            f.isFile && f.length() == it.size
        }
        if (ok) {
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

    /** 数据文件下载并逐文件校验通过后由 UI 层调用：写戳。 */
    @Synchronized
    fun markInstalled(context: Context) {
        val target = File(context.filesDir, "engine")
        val missing = EngineWeights.FILES.filterNot { File(target, it.name).isFile }
        check(missing.isEmpty()) { "engine data incomplete after download: ${missing.map { it.name }}" }
        File(target, STAMP).writeText("ok")
        engineDir = target
    }

    /** 删除数据文件与 stamp，释放空间或准备重新下载。 */
    @Synchronized
    fun clearWeights(context: Context) {
        val target = File(context.filesDir, "engine")
        for (spec in EngineWeights.FILES) {
            File(target, spec.name).delete()
            File(target, spec.name + WeightsDownloader.PART_SUFFIX).delete()
        }
        File(target, STAMP).delete()
        engineDir = null
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
}
