// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

/**
 * Pachi 引擎 JNI 桥。libpachi.so 在 App 进程内以独立线程运行 GTP 协议循环，
 * 输入/输出经 dup2 管道重定向对接 Java 侧：
 *  - [start] 启动协议线程（幂等；EOF 退出后可再次调用以重建会话）；
 *  - [write] 写入一条 GTP 命令；
 *  - [readLine] 阻塞读取一行引擎输出，会话结束且队列耗尽后返回 null；
 *  - [isRunning] 协议线程是否存活；
 *  - [shutdown] 销毁当前会话并回收引擎线程；
 *  - [dupStdout] 会话劫持 fd 1 前复制进程 stdout（仅 host 校验器使用）。
 *
 * 协议行为与已知的坑见 engine-src/README.md（绝不发送 quit；EOF 结束会话；
 * 销毁顺序必须先恢复 stdio 再 join demux）。
 */
object PachiNative {
    init {
        System.loadLibrary("pachi")
    }

    external fun start(workDir: String)
    external fun write(line: String)
    external fun readLine(): String?
    external fun isRunning(): Boolean
    external fun shutdown()
    external fun dupStdout(): Int
}
