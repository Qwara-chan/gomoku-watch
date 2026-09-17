// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

/**
 * Rapfi 引擎 JNI 桥。librapfi.so 在 App 进程内以独立线程运行 Piskvork 协议循环，
 * 输入/输出经 streambuf 重定向对接 Java 侧：
 *  - [start] 启动协议线程（幂等；END 退出后可再次调用以重建会话）；
 *  - [write] 写入一条协议命令；
 *  - [readLine] 阻塞读取一行引擎输出，会话结束且队列耗尽后返回 null；
 *  - [isRunning] 协议线程是否存活。
 */
object RapfiNative {
    init {
        System.loadLibrary("rapfi")
    }

    external fun start(workDir: String)
    external fun write(line: String)
    external fun readLine(): String?
    external fun isRunning(): Boolean
}
