// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 assets 中的 Rapfi 权重与配置文件解压到应用私有目录。
 * 引擎本体是 JNI 库（jniLibs 中的 librapfi.so，由系统自动装载），
 * 这里只准备数据文件：config.toml 与 NNUE/经典评估权重（Rapfi 要求可从工作目录找到）。
 */
object EngineInstaller {

    private const val TAG = "EngineInstaller"
    private const val ASSET_COMMON = "engine/common"
    private const val STAMP = "weights-v1.stamp"

    /** 缺失即无法启动引擎的文件；用于判断已解压内容是否完整。
     *  三个 ~10MB 的 .lz4 权重必须在内：否则复制中途失败时只有小文件通过校验，
     *  stamp 照写，残缺目录被固化成永久损坏（引擎永远启动失败且不会自愈） */
    private val REQUIRED_FILES = listOf(
        "config.toml",
        "model210901.bin",
        "mix9svqfreestyle_bsmix.bin.lz4",
        "mix9svqrenju_bs15_black.bin.lz4",
        "mix9svqrenju_bs15_white.bin.lz4",
    )

    @Volatile
    private var weightsDir: File? = null

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
        Log.i(TAG, "Installing engine weights to ${target.absolutePath}")
        copyAssetTree(context.assets, ASSET_COMMON, target)
        // 解压不完整就不要写戳，否则会一直以残缺目录启动引擎
        val missing = REQUIRED_FILES.filterNot { File(target, it).isFile }
        check(missing.isEmpty()) { "engine assets incomplete, missing: $missing" }
        stamp.writeText("ok")
        weightsDir = target
        return target
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
