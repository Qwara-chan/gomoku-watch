// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import java.util.Locale

/**
 * Pachi 引擎数据文件（走子模式库 patterns_mm.* + 开局库 opening.dat，共 ~2.6MB）的运行时下载清单。
 * 这些文件默认不打进 APK（gradle 属性 `-PbundleWeights=true` 可恢复内置，见 app/build.gradle.kts），
 * 首次需要引擎时从本仓库的固定 tag Release（[RELEASE_TAG]）或镜像站下载。
 *
 * 完整性靠这里钉死的 SHA-256 保证：镜像站/网络投毒只会校验失败并自动换源。
 * 换数据文件时：替换文件 → 更新 [FILES] 的 size/sha256 → **把 [RELEASE_TAG] 进位**
 * （如 go-engine-data-v2），老版本 APK 仍按旧 tag 下载旧文件，互不干扰。
 * CI（.github/workflows/build.yml）会校验清单与仓库文件一致，并把文件上传到
 * 对应 tag 的 prerelease。
 *
 * 注意：这些文件缺失时 pachi 会降级为纯蒙特卡洛（能下但更弱）——门控的意义是保证
 * 每个用户都拿到完整棋力，而不是让引擎能启动（它本来就能）。
 */
object EngineWeights {

    const val RELEASE_TAG = "go-engine-data-v1"
    const val REPO = "Qwara-chan/gomoku-watch"

    /** 仓库内数据文件路径（jsDelivr 按 git tag 取文件，与 host 测试 workDir 同一路径） */
    private const val REPO_PATH = "app/src/main/assets/engine/common"

    data class FileSpec(val name: String, val size: Long, val sha256: String)

    val FILES = listOf(
        FileSpec(
            "patterns_mm.gamma",
            315382L,
            "dfcd495f7547ca2b31f8552ef253767ae725c0950ad227d5ae63cde4d9055abe",
        ),
        FileSpec(
            "patterns_mm.spat",
            693941L,
            "b415fa8476036146222af097150e2029c87c628bfe6b059b32d47e42d9fcbc08",
        ),
        FileSpec(
            "opening.dat",
            1697286L,
            "49214c55c6e2de11a8377d4f07d51fa4077037cdd3ea35ba7ca013cffc01ddbf",
        ),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.size }

    /**
     * @param supportsRange 该源的 Range 响应是否可信。jsDelivr 边缘缓存会对压缩变体做 Range
     *  （Content-Range 总长与内容都对不上、或提前 EOF），只能整量下载，断点续传交给其它源。
     */
    data class Source(val label: String, val supportsRange: Boolean = true, val urlOf: (String) -> String)

    /**
     * 下载源，按「自动」模式下的尝试顺序排列。镜像域名时效性强，失效的表现只是
     * 该源连不上/校验不过然后落到下一个；增减镜像只改这里。
     */
    val SOURCES = listOf(
        Source("GitHub") { name ->
            "https://github.com/$REPO/releases/download/$RELEASE_TAG/$name"
        },
        Source("jsDelivr", supportsRange = false) { name ->
            "https://cdn.jsdelivr.net/gh/$REPO@$RELEASE_TAG/$REPO_PATH/$name"
        },
        Source("gh-proxy") { name ->
            "https://gh-proxy.com/https://github.com/$REPO/releases/download/$RELEASE_TAG/$name"
        },
        Source("ghfast") { name ->
            "https://ghfast.top/https://github.com/$REPO/releases/download/$RELEASE_TAG/$name"
        },
    )

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
