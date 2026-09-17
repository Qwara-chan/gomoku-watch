// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 assets 中的 pachi 数据文件解压到应用私有目录，作为引擎会话的 workDir。
 * 引擎本体是 JNI 库（jniLibs 中的 libpachi.so，由系统自动装载），这里只准备数据：
 * 走子模式库（patterns_mm.*）与 opening.dat 开局库，pachi 按工作目录查找它们。
 * 全部缺失时引擎自动降级为纯蒙特卡洛（依然可用，只是前中盘更弱），所以这里尽力安装、
 * 不强制失败；但装一半比不装更糟——要么完整要么重头来，完整性校验照旧。
 */
object EngineInstaller {

    private const val TAG = "EngineInstaller"
    private const val ASSET_COMMON = "engine/common"
    private const val STAMP = "pachi-v1.stamp"

    /** 完整性校验覆盖的文件；解压中途失败时不写 stamp，下次启动重解 */
    private val REQUIRED_FILES = listOf(
        "patterns_mm.gamma",
        "patterns_mm.spat",
        "opening.dat",
    )

    @Volatile
    private var engineDir: File? = null

    @Synchronized
    fun ensureEngineDataInstalled(context: Context): File {
        engineDir?.let { return it }
        val target = File(context.filesDir, "engine")
        val stamp = File(target, STAMP)
        if (stamp.exists() && REQUIRED_FILES.all { File(target, it).isFile }) {
            engineDir = target
            return target
        }
        target.mkdirs()
        Log.i(TAG, "Installing engine data to ${target.absolutePath}")
        copyAssetTree(context.assets, ASSET_COMMON, target)
        // 解压不完整就不要写戳，否则会一直以残缺目录启动引擎
        val missing = REQUIRED_FILES.filterNot { File(target, it).isFile }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "engine assets incomplete, missing: $missing (engine falls back to plain MC)")
            stamp.delete()
        } else {
            stamp.writeText("ok")
        }
        engineDir = target
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
