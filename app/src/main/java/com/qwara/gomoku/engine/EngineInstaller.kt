package com.qwara.gomoku.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把 assets 中的 Rapfi 权重与配置文件解压到应用私有目录。
 * 引擎本体是 JNI 库（jniLibs 中的 librapfi.so，由系统自动装载），
 * 这里只准备数据文件：config.toml 与 NNUE/经典评估权重（Rapfi 要求可从工作目录找到）。
 */
object EngineInstaller {

    private const val TAG = "EngineInstaller"
    private const val ASSET_COMMON = "engine/common"
    private const val STAMP = "weights-v1.stamp"

    @Volatile
    private var weightsDir: File? = null

    @Synchronized
    fun ensureWeightsInstalled(context: Context): File {
        weightsDir?.let { return it }
        val target = File(context.filesDir, "engine")
        val stamp = File(target, STAMP)
        if (stamp.exists()) {
            weightsDir = target
            return target
        }
        target.mkdirs()
        Log.i(TAG, "Installing engine weights to ${target.absolutePath}")
        copyAssetTree(context.assets, ASSET_COMMON, target)
        stamp.writeText("ok")
        weightsDir = target
        return target
    }

    private fun copyAssetTree(am: android.content.res.AssetManager, path: String, outDir: File) {
        val entries = am.list(path) ?: return
        if (entries.isEmpty()) {
            val out = File(outDir, path.substringAfterLast('/'))
            am.open(path).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Log.d(TAG, "copied $path")
        } else {
            for (e in entries) {
                copyAssetTree(am, "$path/$e", outDir)
            }
        }
    }
}
