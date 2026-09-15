// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

/*
 *  Rapfi, a Gomoku/Renju playing engine supporting piskvork protocol.
 *  Copyright (C) 2022  Rapfi developers
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

// JNI bridge that runs Rapfi's piskvork protocol loop inside the host process instead of as a
// child executable. Android's SELinux policy denies `execute_no_trans` on app data files, so a
// `files/`-resident binary cannot be execve'd; loading this shared library and driving the
// protocol over in-process streams keeps the engine usable.
//
// The engine side is left untouched: `std::cin`/`std::cout`/`std::cerr` are re-pointed at custom
// streambufs before the protocol loop starts, so every `std::cin >> token` and every
// `MESSAGEL(...)` in the engine transparently reaches the Java layer.
//
//     Java RapfiNative.write(line)  ->  InputBuffer  ->  std::cin   ->  engine
//     Java RapfiNative.readLine()   <-  Java queue   <-  pump       <-  OutputBuffer <- std::cout
//
// The engine emits lines from several threads (the protocol thread plus its search threads), and
// a thread that calls into the JVM must detach before it exits or the JVM keeps its Thread object
// forever. Rather than attach every engine thread, the engine threads only touch a plain C++ queue
// and one pump thread per session owns the JNI side: it attaches once and detaches when it ends,
// so repeated sessions start and stop without accumulating JVM threads.
//
// One Java queue is created here and shared by every session, matching the single reader thread on
// the Java side. The engine's own globals (Search::Engine, the transposition table, the config)
// are reused across sessions, exactly as the standalone binary reuses them between games.

#include "../command/command.h"

#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <iostream>
#include <mutex>
#include <streambuf>
#include <string>
#include <thread>
#include <unistd.h>
#include <utility>

namespace {

/// Cap on the lines waiting to cross into Java. A caller that stops reading (the app going to the
/// background mid-analysis) would otherwise make the queue grow without bound; dropping the oldest
/// lines keeps memory flat, and a reader that resumes still sees the most recent positions.
constexpr size_t kMaxPendingLines = 8192;

// -------------------------------------------------
// Input: Java -> engine

/// Streambuf fed by RapfiNative.write(). A line only becomes visible to the engine once its
/// trailing '\n' has arrived, so `std::cin >> token` can never split a command in half.
///
/// `underflow()` is what makes the blocking read work: the engine's formatted extraction calls it
/// whenever the get area runs dry, and it returns traits::eof() only after the session has been
/// asked to shut down.
class InputBuffer : public std::streambuf
{
public:
    /// Queue one command line, waking a blocked reader.
    void writeLine(const std::string &line)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (eofReached_)
            return;

        pending_.append(line);
        if (pending_.empty() || pending_.back() != '\n')
            pending_.push_back('\n');

        cv_.notify_all();
    }

    /// Release a blocked reader and make every later read return EOF.
    void shutdown()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        eofReached_ = true;
        pending_.clear();
        cv_.notify_all();
    }

protected:
    int_type underflow() override
    {
        std::unique_lock<std::mutex> lock(mutex_);

        // Wait for at least one complete line: a partially received command must never be exposed.
        cv_.wait(lock, [this] { return eofReached_ || pending_.find('\n') != std::string::npos; });

        if (pending_.find('\n') != std::string::npos)
            drainCompleteLinesLocked();
        else
            buffer_.clear();  // shutting down, and no complete line is left

        if (buffer_.empty())
            return traits_type::eof();

        setg(buffer_.data(), buffer_.data() + 0, buffer_.data() + buffer_.size());
        return traits_type::to_int_type(buffer_.front());
    }

private:
    /// Move every complete line currently queued into the get area.
    void drainCompleteLinesLocked()
    {
        size_t lastNewline = pending_.rfind('\n');
        buffer_.assign(pending_, 0, lastNewline + 1);
        pending_.erase(0, lastNewline + 1);
    }

    std::mutex              mutex_;
    std::condition_variable cv_;
    std::string             pending_;  // lines not yet handed to the engine
    std::string             buffer_;   // get-area backing store
    bool                    eofReached_ = false;
};

// -------------------------------------------------
// Output: engine -> Java

/// Thread-safe hand-off queue for engine output lines. The engine's output macros run on several
/// threads, so pushes are mutex-protected; the pump thread pops from here.
class LinePipe
{
public:
    void push(std::string line)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_)
            return;

        if (lines_.size() >= kMaxPendingLines)
            lines_.pop_front();  // drop the oldest: the reader is behind

        lines_.push_back(std::move(line));
        cv_.notify_all();
    }

    /// No further lines will arrive; the pump drains what is left and then finishes.
    void close()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        closed_ = true;
        cv_.notify_all();
    }

    enum class PopResult {
        Line,     ///< A line was taken.
        Timeout,  ///< Nothing available yet; the caller should keep waiting.
        Closed,   ///< The pipe is closed and drained.
    };

    /// Wait up to `timeout` for a line, distinguishing "nothing yet" from "finished" so a caller
    /// never mistakes a quiet moment for the end of the session.
    PopResult pop(std::string &out, std::chrono::milliseconds timeout)
    {
        std::unique_lock<std::mutex> lock(mutex_);
        cv_.wait_for(lock, timeout, [this] { return closed_ || !lines_.empty(); });

        if (!lines_.empty()) {
            out = std::move(lines_.front());
            lines_.pop_front();
            return PopResult::Line;
        }
        return closed_ ? PopResult::Closed : PopResult::Timeout;
    }

private:
    std::mutex              mutex_;
    std::condition_variable cv_;
    std::deque<std::string> lines_;
    bool                    closed_ = false;
};

/// Streambuf collecting what the engine writes to std::cout / std::cerr. Mutex-protected because
/// the engine's output macros are called from several search threads. Complete lines are handed to
/// the pipe and dropped from the buffer, so it cannot grow during a long analysis.
class OutputBuffer : public std::streambuf
{
public:
    explicit OutputBuffer(LinePipe &pipe) : pipe_(pipe) {}

protected:
    int_type overflow(int_type ch = traits_type::eof()) override
    {
        if (traits_type::eq_int_type(ch, traits_type::eof()))
            return traits_type::not_eof(ch);

        char c = traits_type::to_char_type(ch);

        std::lock_guard<std::mutex> lock(mutex_);
        line_.push_back(c);
        if (c == '\n')
            flushLinesLocked(false);

        return traits_type::not_eof(ch);
    }

    /// std::cin's tie flushes std::cout before each read, so a trailing bare line is complete here;
    /// forward it instead of holding it until some later write.
    int sync() override
    {
        std::lock_guard<std::mutex> lock(mutex_);
        flushLinesLocked(true);
        return 0;
    }

private:
    /// Hand every complete line to the pipe, leaving any partial tail behind (or forwarding that
    /// tail too when `forceTail` is set).
    void flushLinesLocked(bool forceTail)
    {
        size_t start = 0;
        for (;;) {
            size_t nl = line_.find('\n', start);
            if (nl == std::string::npos)
                break;
            emitLocked(line_.substr(start, nl - start));
            start = nl + 1;
        }
        line_.erase(0, start);
        if (forceTail && !line_.empty())
            emitLocked(std::exchange(line_, std::string {}));
    }

    void emitLocked(std::string text)
    {
        if (!text.empty() && text.back() == '\r')
            text.pop_back();
        pipe_.push(std::move(text));
    }

    std::mutex  mutex_;
    std::string line_;
    LinePipe   &pipe_;
};

// -------------------------------------------------
// Java plumbing

/// Bridge-wide handles, resolved once and then used by every session. The output queue lives here
/// rather than per session so the Java reader thread keeps one stable source of lines.
struct JavaBridge
{
    JavaVM   *jvm       = nullptr;
    jobject   queue     = nullptr;  // global ref: java.util.concurrent.LinkedBlockingQueue
    jmethodID queueAdd  = nullptr;  // BlockingQueue.add(Object)
    jmethodID queuePoll = nullptr;  // BlockingQueue.poll(long, TimeUnit)
    jobject   timeUnit  = nullptr;  // global ref: TimeUnit.MILLISECONDS

    std::mutex  initMutex;
    bool        initialized = false;
    std::string initError;
};

JavaBridge gBridge;

// -------------------------------------------------
// Session

/// One engine session: a fresh pair of stream buffers, the thread running the protocol loop, and
/// the pump thread carrying its output into the Java queue.
struct Session
{
    LinePipe     pipe;
    InputBuffer  input;
    OutputBuffer output {pipe};

    std::streambuf *savedCin  = nullptr;
    std::streambuf *savedCout = nullptr;
    std::streambuf *savedCerr = nullptr;

    std::thread      thread;
    std::thread      pumpThread;
    std::atomic_bool running {false};
    std::atomic_bool pumpFinished {false};
};

std::mutex gSessionMutex;
Session   *gSession = nullptr;

// -------------------------------------------------
// Helpers

std::string jstringToUtf8(JNIEnv *env, jstring s)
{
    if (!s)
        return {};

    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (!chars)
        return {};

    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

/// JavaVM::GetEnv and AttachCurrentThread are declared with slightly different parameter types by
/// the JDK implementations: GetEnv takes `void **` everywhere, while AttachCurrentThreadAsDaemon
/// takes `JNIEnv **` in the Android NDK's C++ wrapper but `void **` in OpenJDK. These wrappers
/// hide the difference so the bridge has one portable call site.
///
/// The attach call deliberately passes a null thread name: the name argument is typed
/// `JavaVMAttachArgs *` by the NDK wrapper but `void *` by OpenJDK, and a null is valid for both.
/// Leaving the thread unnamed costs nothing here, since the pump thread lives for one session.
inline jint jvmGetEnv(JavaVM *vm, JNIEnv **env)
{
    return vm->GetEnv(reinterpret_cast<void **>(env), JNI_VERSION_1_6);
}

inline jint jvmAttachDaemon(JavaVM *vm, JNIEnv **env)
{
#if defined(__ANDROID__)
    return vm->AttachCurrentThreadAsDaemon(env, nullptr);
#else
    return vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(env), nullptr);
#endif
}

/// Move engine lines from the C++ pipe into the Java queue. This is the only place the bridge
/// enters the JVM from a non-Java thread, so it attaches once on entry and detaches on exit; that
/// detach is what keeps repeated sessions from accumulating JVM Thread objects.
void pumpToJava(Session *session)
{
    if (!gBridge.jvm || !gBridge.queue) {
        session->pumpFinished.store(true, std::memory_order_release);
        return;
    }

    JNIEnv *env      = nullptr;
    bool    attached = false;

    if (jvmGetEnv(gBridge.jvm, &env) != JNI_OK) {
        if (jvmAttachDaemon(gBridge.jvm, &env) != JNI_OK) {
            session->pumpFinished.store(true, std::memory_order_release);
            return;
        }
        attached = true;
    }

    std::string line;
    for (;;) {
        auto result = session->pipe.pop(line, std::chrono::milliseconds(50));
        if (result == LinePipe::PopResult::Closed)
            break;
        if (result == LinePipe::PopResult::Timeout)
            continue;

        jstring text = env->NewStringUTF(line.c_str());
        if (text) {
            env->CallBooleanMethod(gBridge.queue, gBridge.queueAdd, text);
            env->DeleteLocalRef(text);
        }
        if (env->ExceptionCheck())
            env->ExceptionClear();
    }

    if (attached)
        gBridge.jvm->DetachCurrentThread();

    session->pumpFinished.store(true, std::memory_order_release);
}

/// Remove every line left over from an earlier session, so a restart does not hand the Java reader
/// output that belongs to the previous game.
void drainJavaQueue(JNIEnv *env)
{
    if (!gBridge.queue)
        return;

    for (;;) {
        jobject line = env->CallObjectMethod(gBridge.queue, gBridge.queuePoll, jlong(0),
                                             gBridge.timeUnit);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        if (!line)
            return;
        env->DeleteLocalRef(line);
    }
}

/// Cache every Java object and method the bridge needs.
bool initJavaBridge(JNIEnv *env)
{
    std::lock_guard<std::mutex> lock(gBridge.initMutex);
    if (gBridge.initialized)
        return true;

    if (env->GetJavaVM(&gBridge.jvm) != JNI_OK) {
        gBridge.initError = "cannot obtain the JavaVM handle";
        return false;
    }

    // The queue is created here so the Java side only has to poll it; the reader thread and the
    // pump then share one stable object for the whole process lifetime.
    jclass clsQueue = env->FindClass("java/util/concurrent/LinkedBlockingQueue");
    jclass clsUnit  = env->FindClass("java/util/concurrent/TimeUnit");
    if (!clsQueue || !clsUnit) {
        gBridge.initError = "java.util.concurrent classes not found";
        return false;
    }

    jmethodID queueCtor = env->GetMethodID(clsQueue, "<init>", "()V");
    jobject   queue     = queueCtor ? env->NewObject(clsQueue, queueCtor) : nullptr;

    gBridge.queueAdd  = env->GetMethodID(clsQueue, "add", "(Ljava/lang/Object;)Z");
    gBridge.queuePoll = env->GetMethodID(clsQueue, "poll",
                                          "(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;");
    jfieldID fieldMillis = env->GetStaticFieldID(clsUnit, "MILLISECONDS",
                                                 "Ljava/util/concurrent/TimeUnit;");
    jobject  unitMillis  = fieldMillis ? env->GetStaticObjectField(clsUnit, fieldMillis) : nullptr;

    if (!queue || !gBridge.queueAdd || !gBridge.queuePoll || !unitMillis) {
        gBridge.initError = "cannot create the output queue";
        return false;
    }

    gBridge.queue    = env->NewGlobalRef(queue);
    gBridge.timeUnit = env->NewGlobalRef(unitMillis);

    env->DeleteLocalRef(queue);
    env->DeleteLocalRef(unitMillis);
    env->DeleteLocalRef(clsQueue);
    env->DeleteLocalRef(clsUnit);

    gBridge.initialized = true;
    return true;
}

void throwJava(JNIEnv *env, const char *className, const std::string &message)
{
    jclass cls = env->FindClass(className);
    if (cls)
        env->ThrowNew(cls, message.c_str());
}

}  // namespace

// -------------------------------------------------
// Engine entry point run on the session thread

/// Run the piskvork protocol loop to completion. `main.cpp` reaches this through
/// `Command::gomocupLoop()` after parsing its command line and loading the config; calling it
/// directly skips both, so the caller has to load the config first.
///
/// Exported so the loop can also be started from plain C/C++, without JNI.
extern "C" void rapfiRunProtocolLoop()
{
    Command::gomocupLoop();
}

// -------------------------------------------------
// JNI surface: com.qwara.gomoku.engine.RapfiNative

extern "C" JNIEXPORT void JNICALL
Java_com_qwara_gomoku_engine_RapfiNative_start(JNIEnv *env, jclass, jstring workDir)
{
    std::string dir = jstringToUtf8(env, workDir);
    if (dir.empty()) {
        throwJava(env, "java/lang/IllegalArgumentException", "start(): workDir must not be empty");
        return;
    }

    // Resolve the shared Java handles before taking the session lock: the lookups below run a
    // little Java code, which must not happen while another thread is blocked on that lock.
    if (!initJavaBridge(env)) {
        throwJava(env, "java/lang/IllegalStateException", "start(): " + gBridge.initError);
        return;
    }

    std::lock_guard<std::mutex> sessionLock(gSessionMutex);

    // Release the previous session before creating its replacement, so at most one protocol loop is
    // ever live. After `END` the loop thread has already returned and the joins are immediate; if
    // it is still searching, the shutdown makes its next read fail and the joins return once the
    // current depth finishes.
    if (gSession) {
        gSession->input.shutdown();
        if (gSession->thread.joinable())
            gSession->thread.join();

        gSession->pipe.close();
        if (gSession->pumpThread.joinable())
            gSession->pumpThread.join();

        gSession->running.store(false, std::memory_order_release);

        if (gSession->savedCin)
            std::cin.rdbuf(gSession->savedCin);
        if (gSession->savedCout)
            std::cout.rdbuf(gSession->savedCout);
        if (gSession->savedCerr)
            std::cerr.rdbuf(gSession->savedCerr);

        delete gSession;
        gSession = nullptr;
    }

    // Drop anything the previous game left in the queue so the new session starts clean.
    drainJavaQueue(env);

    // The engine resolves config.toml and the weight files relative to the working directory, so
    // enter the caller-provided directory for the whole session. chdir is process-wide and is
    // deliberately left in place: the protocol thread and the engine's search threads must keep
    // resolving paths against it for as long as the session runs.
    if (::chdir(dir.c_str()) != 0) {
        throwJava(env, "java/lang/IllegalStateException",
                  "start(): cannot enter workDir " + dir + ": " + ::strerror(errno));
        return;
    }

    Session *session = new Session();

    // Point the standard streams at this session's buffers. The saved handles are the previous
    // session's buffers (or the real ones on the first run), which is what makes a restart route
    // output to the same Java queue.
    session->savedCin  = std::cin.rdbuf(&session->input);
    session->savedCout = std::cout.rdbuf(&session->output);
    session->savedCerr = std::cerr.rdbuf(&session->output);
    std::cin.clear();
    std::cout.clear();
    std::cerr.clear();

    // Load the config before the loop starts: without it the engine silently falls back to its
    // built-in classical evaluation, which is much weaker than the NNUE weights on disk.
    Command::configPath          = "config.toml";
    Command::allowInternalConfig = true;
    const bool configLoaded      = Command::loadConfig();

    session->running.store(true, std::memory_order_release);
    gSession = session;

    session->pumpThread = std::thread([session] { pumpToJava(session); });

    session->thread = std::thread([session] {
        rapfiRunProtocolLoop();
        // gomocupLoop() returns only after the engine's search threads are idle and destroyed, so
        // once it is done no further engine output can appear.
        session->running.store(false, std::memory_order_release);
        session->pipe.close();
    });

    if (!configLoaded)
        throwJava(env, "java/lang/IllegalStateException",
                  "start(): config.toml could not be loaded; the engine is running on its built-in "
                  "config with classical evaluation");
}

/// Queue one command line for the engine. Safe to call from any thread.
extern "C" JNIEXPORT void JNICALL
Java_com_qwara_gomoku_engine_RapfiNative_write(JNIEnv *env, jclass, jstring line)
{
    std::string text = jstringToUtf8(env, line);

    std::lock_guard<std::mutex> sessionLock(gSessionMutex);
    if (!gSession)
        return;

    gSession->input.writeLine(text);
}

/// Block until one engine output line is available, then return it. Returns null once the session
/// has ended and the pump has finished flushing, which is the reader thread's exit condition.
extern "C" JNIEXPORT jstring JNICALL
Java_com_qwara_gomoku_engine_RapfiNative_readLine(JNIEnv *env, jclass)
{
    // Snapshot what is needed under the lock, then release it: the poll below can block for a long
    // time (the engine may think for seconds between output lines) and must not hold up a
    // concurrent start() on another thread.
    jobject queue    = nullptr;
    jobject timeUnit = nullptr;
    jmethodID poll   = nullptr;
    {
        std::lock_guard<std::mutex> sessionLock(gSessionMutex);
        if (!gSession || !gBridge.queue)
            return nullptr;
        queue    = gBridge.queue;
        timeUnit = gBridge.timeUnit;
        poll     = gBridge.queuePoll;
    }

    for (;;) {
        // Wake up periodically rather than waiting once for the whole timeout, so a session that
        // ended without another line still lets the reader observe the shutdown promptly.
        jobject line = env->CallObjectMethod(queue, poll, jlong(100), timeUnit);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        if (line)
            return static_cast<jstring>(line);

        // Only report the end once the loop has stopped AND the pump has flushed every remaining
        // line, otherwise the tail of the final analysis could be dropped.
        std::lock_guard<std::mutex> sessionLock(gSessionMutex);
        if (!gSession)
            return nullptr;
        if (!gSession->running.load(std::memory_order_acquire)
            && gSession->pumpFinished.load(std::memory_order_acquire))
            return nullptr;
    }
}

/// Whether the protocol loop thread is still alive. After `END` the output queue may still hold
/// lines that were produced before the loop returned.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_qwara_gomoku_engine_RapfiNative_isRunning(JNIEnv *, jclass)
{
    std::lock_guard<std::mutex> sessionLock(gSessionMutex);
    if (!gSession)
        return JNI_FALSE;
    return gSession->running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
