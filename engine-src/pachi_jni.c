// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

/*
 * Pachi, a Monte Carlo Go engine supporting GTP (Go Text Protocol).
 * Copyright (C) 2006-2026 Pachi authors (see pachi-src/CREDITS)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

// JNI bridge that runs Pachi's GTP protocol loop inside the host process instead of as a
// child executable. Android's SELinux policy denies `execute_no_trans` on app data files, so
// a `files/`-resident binary cannot be execve'd; loading this shared library and driving the
// protocol over in-process pipes keeps the engine usable.
//
// Unlike the Rapfi bridge (which re-pointed C++ iostream streambufs), Pachi talks plain C
// stdio (`fgets(stdin)` / `printf`), so the bridge redirects the *file descriptors* once per
// session with dup2():
//
//     Java PachiNative.write(line)  ->  cmd pipe write end  ->  dup2'ed stdin   ->  fgets
//     printf/putchar(stdout)        ->  dup2'ed stdout      ->  out pipe read end  ->  demux  ->  Java queue
//     fprintf(stderr)               ->  dup2'ed stderr      ->  err pipe read end  ->  demux  ->  Java queue
//
// End of session: closing the cmd pipe write end makes the next `fgets()` return NULL, the
// GTP loop exits and the engine is torn down cleanly (no `quit` command is ever sent — the
// `quit` handler calls exit(0), which would kill the whole app process).
//
//     Java PachiNative.readLine()   <-  Java queue   <-  pump thread   <-  LinePipe   <-  demux thread
//
// The engine emits lines from several threads (the protocol thread plus its search threads);
// a thread that calls into the JVM must detach before it exits. Rather than attach every
// engine thread, the engine threads only touch a plain C queue and one pump thread per
// session owns the JNI side: it attaches once and detaches when it ends, so repeated sessions
// start and stop without accumulating JVM threads.
//
// One Java queue is created here and shared by every session, matching the single reader
// thread on the Java side. The engine's own globals (pattern dictionaries) are freed at
// session end and reloaded on the next start, exactly as the standalone binary re-reads its
// data files on each launch.

#include <jni.h>

#include <pachi.h>
#include <board.h>
#include <debug.h>
#include <engine.h>
#include <gtp.h>
#include <joseki/joseki.h>
#include <pattern/prob.h>
#include <pattern/spatial.h>
#include <random.h>
#include <stone.h>
#include <timeinfo.h>
#include <chat.h>

#include <unistd.h>
#include <poll.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---- file-local helpers (C translation unit) ---- */

/// Engine tuning handed to the UCT engine at construction. Memory figures are MiB, sized for
/// a smartwatch: small initial tree, capped growth, and a global ceiling that also covers the
/// temporary tree used while reallocating. Single search thread — these devices are
/// dual-core at best and the UI must stay responsive.
const char *kUctArgs = "threads=1,tree_size=16,max_tree_size=64,max_mem=96";

/// Cap on the lines waiting to cross into Java. A caller that stops reading (the app going to
/// the background mid-analysis) would otherwise make the queue grow without bound; dropping
/// the oldest lines keeps memory flat, and a reader that resumes still sees recent output.
#define kMaxPendingLines 8192

/// Longest single GTP command / response chunk the demuxer reassembles per fd.
#define kChunkSize 16384

// -------------------------------------------------
// Output: engine -> Java

/// Thread-safe hand-off queue for engine output lines. The engine's output runs on several
/// threads, so pushes are mutex-protected; the demux thread pops from here.
typedef struct
{
    char   **lines;
    size_t   head, count, cap;
    bool     closed;
    pthread_mutex_t mutex;
    pthread_cond_t  cv;
} LinePipe;

static void pipe_init(LinePipe *p)
{
    memset(p, 0, sizeof(*p));
    pthread_mutex_init(&p->mutex, NULL);
    pthread_cond_init(&p->cv, NULL);
    p->cap = 1024;
    p->lines = calloc(p->cap, sizeof(char *));
}

static void pipe_destroy(LinePipe *p)
{
    for (size_t i = 0; i < p->count; i++)
        free(p->lines[(p->head + i) % p->cap]);
    free(p->lines);
    pthread_mutex_destroy(&p->mutex);
    pthread_cond_destroy(&p->cv);
    memset(p, 0, sizeof(*p));
}

static void pipe_push(LinePipe *p, const char *line)
{
    pthread_mutex_lock(&p->mutex);
    if (p->closed) {
        pthread_mutex_unlock(&p->mutex);
        return;
    }
    if (p->count == p->cap) {
        // Drop the oldest line to keep memory flat; a reader that resumes still sees the most
        // recent output. Growing without bound is what we must avoid on a watch.
        free(p->lines[p->head]);
        p->lines[p->head] = NULL;
        p->head = (p->head + 1) % p->cap;
        p->count--;
    }
    size_t idx = (p->head + p->count) % p->cap;
    p->lines[idx] = strdup(line);
    p->count++;
    pthread_cond_signal(&p->cv);
    pthread_mutex_unlock(&p->mutex);
}

/// Returns 1 when a line was taken, 0 on timeout, -1 when closed and drained.
static int pipe_pop(LinePipe *p, char *out, size_t outSize, int timeoutMs)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeoutMs / 1000;
    ts.tv_nsec += (long)(timeoutMs % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }

    pthread_mutex_lock(&p->mutex);
    while (p->count == 0 && !p->closed)
        if (pthread_cond_timedwait(&p->cv, &p->mutex, &ts) != 0)
            break;

    if (p->count == 0) {
        int closed = p->closed;
        pthread_mutex_unlock(&p->mutex);
        return closed ? -1 : 0;
    }
    snprintf(out, outSize, "%s", p->lines[p->head]);
    free(p->lines[p->head]);
    p->lines[p->head] = NULL;
    p->head = (p->head + 1) % p->cap;
    p->count--;
    pthread_mutex_unlock(&p->mutex);
    return 1;
}

static void pipe_close(LinePipe *p)
{
    pthread_mutex_lock(&p->mutex);
    p->closed = true;
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mutex);
}

// -------------------------------------------------
// Session

typedef struct Session
{
    int cmdFd[2];      // [0] engine stdin, [1] Java writes  (Java -> engine)
    int outFd[2];      // [0] Java reads,   [1] engine stdout (engine -> Java)
    int errFd[2];      // [0] Java reads,   [1] engine stderr (engine -> Java)

    LinePipe pipe;

    pthread_t engineThread;
    pthread_t demuxThread;
    pthread_t pumpThread;

    pthread_mutex_t writeMutex;

    volatile bool running;
    volatile bool pumpFinished;
    volatile bool demuxFinished;
} Session;

static pthread_mutex_t gSessionMutex = PTHREAD_MUTEX_INITIALIZER;
static Session        *gSession = NULL;

/// The process's original stdio fds, captured before the first dup2 and restored when the
/// last session ends, so the JVM's own diagnostics are not silently swallowed forever.
static int gSavedStdio[3] = { -1, -1, -1 };
static bool gStdioSaved = false;

// -------------------------------------------------
// Java plumbing (shared across sessions)

struct JavaBridge
{
    JavaVM   *jvm;
    jobject   queue;     // global ref: java.util.concurrent.LinkedBlockingQueue
    jmethodID queueAdd;  // BlockingQueue.add(Object)
    jmethodID queuePoll; // BlockingQueue.poll(long, TimeUnit)
    jobject   timeUnit;  // global ref: TimeUnit.MILLISECONDS

    pthread_mutex_t initMutex;
    bool            initialized;
    char            initError[256];
};

static struct JavaBridge gBridge;

// -------------------------------------------------
// Engine thread: the GTP protocol loop

/// Teardown equivalent to pachi_done() but without touching pachi.c's NULL main engine/board
/// statics (pachi_done() would delete_engine(NULL) and crash — we were never main()).
static void engine_teardown(board_t **b, engine_t **e, gtp_t *gtp)
{
    delete_engine(e);
    board_delete(b);
    gtp_done(gtp);
    chat_done();
    joseki_done();
    prob_dict_done();
    spatial_dict_done();
}

static void *engine_main(void *arg)
{
    Session *s = (Session *)arg;

    // Fresh FILE state: a previous session may have left EOF/error indicators set.
    clearerr(stdin);
    clearerr(stdout);
    clearerr(stderr);
    setlinebuf(stdout);
    setlinebuf(stderr);

    debug_level = 0;         // keep stderr (Java-visible) free of per-playout spam
    debug_boardprint = false;

    // Opening book: only when shipped in the engine workDir. The book makes opening play
    // stronger and more varied; without it fbook stays disabled and everything still works.
    board_t *b = board_new(19, NULL);
    if (access("opening.dat", R_OK) == 0) {
        b->fbookfile = strdup("opening.dat");
        board_clear(b);
    }

    gtp_t gtp;
    gtp_internal_init(&gtp);
    gtp_init(&gtp, b);

    uint64_t randomState;
    fast_srandom(&randomState, (uint64_t)time(NULL) ^ (uint64_t)getpid());

    // The joseki database is only consulted with DCNN builds; the no-DCNN UCT engine would
    // die() if joseki19.gtp is missing, so disable it explicitly instead of shipping the file.
    disable_joseki();

    engine_init_checks();
    engine_t *e = new_engine(E_UCT, (char *)kUctArgs, b);

    time_info_t ti[S_MAX];
    ti[S_BLACK] = ti_none;
    ti[S_WHITE] = ti_none;

    // Mirror of pachi.c main_loop(): each line is one GTP command; EOF ends the session.
    char buf[4096];
    while (fgets(buf, sizeof(buf), stdin))
        gtp_parse(&gtp, b, e, ti, buf);

    engine_teardown(&b, &e, &gtp);

    s->running = false;
    close(s->outFd[1]); s->outFd[1] = -1;
    close(s->errFd[1]); s->errFd[1] = -1;
    return NULL;
}

// -------------------------------------------------
// Demux thread: two pipe fds -> one line pipe

/// Accumulating reader state for one engine output fd.
typedef struct
{
    int   fd;
    char  partial[kChunkSize];
    size_t len;
    bool  eof;
} DemuxSource;

/// Push every complete line currently buffered for this source.
static void demux_flush_lines(DemuxSource *src, LinePipe *pipe)
{
    char *start = src->partial;
    char *nl;
    while ((nl = memchr(start, '\n', src->len - (size_t)(start - src->partial))) != NULL) {
        *nl = '\0';
        // Tolerate CRLF just in case.
        if (nl > start && nl[-1] == '\r')
            nl[-1] = '\0';
        if (nl > start)
            pipe_push(pipe, start);
        start = nl + 1;
    }
    size_t rest = src->len - (size_t)(start - src->partial);
    memmove(src->partial, start, rest);
    src->len = rest;
}

static void *demux_main(void *arg)
{
    Session *s = (Session *)arg;
    DemuxSource srcs[2] = {
        { .fd = s->outFd[0], .len = 0, .eof = false },
        { .fd = s->errFd[0], .len = 0, .eof = false },
    };

    for (;;) {
        struct pollfd pfds[2];
        int alive = 0;
        for (int i = 0; i < 2; i++) {
            pfds[i].fd = srcs[i].eof ? -1 : srcs[i].fd;
            pfds[i].events = POLLIN;
            pfds[i].revents = 0;
            if (!srcs[i].eof)
                alive++;
        }
        if (!alive)
            break;

        int r = poll(pfds, 2, -1);
        if (r < 0) {
            if (errno == EINTR)
                continue;
            break;
        }
        for (int i = 0; i < 2; i++) {
            if (pfds[i].fd < 0 || pfds[i].revents == 0)
                continue;
            if (pfds[i].revents & (POLLIN | POLLHUP)) {
                char chunk[kChunkSize];
                ssize_t n = read(pfds[i].fd, chunk, sizeof(chunk));
                if (n < 0 && errno == EINTR)
                    continue;
                if (n <= 0) {
                    srcs[i].eof = true;
                    // A trailing unterminated line is still worth forwarding.
                    if (srcs[i].len > 0) {
                        srcs[i].partial[srcs[i].len] = '\0';
                        pipe_push(&s->pipe, srcs[i].partial);
                        srcs[i].len = 0;
                    }
                    continue;
                }
                // Reserve one byte so the terminating '\0' above always fits.
                size_t take = (size_t)n;
                if (srcs[i].len + take > sizeof(srcs[i].partial) - 1)
                    take = sizeof(srcs[i].partial) - 1 - srcs[i].len;
                memcpy(srcs[i].partial + srcs[i].len, chunk, take);
                srcs[i].len += take;
                demux_flush_lines(&srcs[i], &s->pipe);
            } else if (pfds[i].revents & (POLLERR | POLLNVAL)) {
                srcs[i].eof = true;
            }
        }
    }

    pipe_close(&s->pipe);
    s->demuxFinished = true;
    return NULL;
}

// -------------------------------------------------
// Pump thread: line pipe -> Java queue

static void pump_to_java(Session *s)
{
    if (!gBridge.jvm || !gBridge.queue) {
        s->pumpFinished = true;
        return;
    }

    JNIEnv *env = NULL;
    bool attached = false;

    // The only place the bridge enters the JVM from a non-Java thread: attach once, detach on
    // exit, so repeated sessions do not accumulate JVM Thread objects.
    if ((*gBridge.jvm)->GetEnv(gBridge.jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*gBridge.jvm)->AttachCurrentThreadAsDaemon(gBridge.jvm, (void **)&env, NULL) != JNI_OK) {
            s->pumpFinished = true;
            return;
        }
        attached = true;
    }

    char line[kChunkSize];
    for (;;) {
        int r = pipe_pop(&s->pipe, line, sizeof(line), 50);
        if (r < 0)
            break;
        if (r == 0)
            continue;

        jstring text = (*env)->NewStringUTF(env, line);
        if (text) {
            (*env)->CallBooleanMethod(env, gBridge.queue, gBridge.queueAdd, text);
            (*env)->DeleteLocalRef(env, text);
        }
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }

    if (attached)
        (*gBridge.jvm)->DetachCurrentThread(gBridge.jvm);

    s->pumpFinished = true;
}

static void *pump_main(void *arg)
{
    pump_to_java((Session *)arg);
    return NULL;
}

// -------------------------------------------------
// Helpers

static void drain_java_queue(JNIEnv *env)
{
    if (!gBridge.queue)
        return;
    for (;;) {
        jobject line = (*env)->CallObjectMethod(env, gBridge.queue, gBridge.queuePoll,
                                                (jlong)0, gBridge.timeUnit);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            return;
        }
        if (!line)
            return;
        (*env)->DeleteLocalRef(env, line);
    }
}

static bool init_java_bridge(JNIEnv *env)
{
    pthread_mutex_lock(&gBridge.initMutex);
    if (gBridge.initialized) {
        pthread_mutex_unlock(&gBridge.initMutex);
        return true;
    }

    if ((*env)->GetJavaVM(env, &gBridge.jvm) != JNI_OK) {
        snprintf(gBridge.initError, sizeof(gBridge.initError), "cannot obtain the JavaVM handle");
        pthread_mutex_unlock(&gBridge.initMutex);
        return false;
    }

    jclass clsQueue = (*env)->FindClass(env, "java/util/concurrent/LinkedBlockingQueue");
    jclass clsUnit  = (*env)->FindClass(env, "java/util/concurrent/TimeUnit");
    if (!clsQueue || !clsUnit) {
        snprintf(gBridge.initError, sizeof(gBridge.initError), "java.util.concurrent classes not found");
        pthread_mutex_unlock(&gBridge.initMutex);
        return false;
    }

    jmethodID queueCtor = (*env)->GetMethodID(env, clsQueue, "<init>", "()V");
    jobject queue = queueCtor ? (*env)->NewObject(env, clsQueue, queueCtor) : NULL;

    gBridge.queueAdd  = (*env)->GetMethodID(env, clsQueue, "add", "(Ljava/lang/Object;)Z");
    gBridge.queuePoll = (*env)->GetMethodID(env, clsQueue, "poll",
                                            "(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;");
    jfieldID fieldMillis = (*env)->GetStaticFieldID(env, clsUnit, "MILLISECONDS",
                                                    "Ljava/util/concurrent/TimeUnit;");
    jobject unitMillis = fieldMillis ? (*env)->GetStaticObjectField(env, clsUnit, fieldMillis) : NULL;

    if (!queue || !gBridge.queueAdd || !gBridge.queuePoll || !unitMillis) {
        snprintf(gBridge.initError, sizeof(gBridge.initError), "cannot create the output queue");
        pthread_mutex_unlock(&gBridge.initMutex);
        return false;
    }

    gBridge.queue    = (*env)->NewGlobalRef(env, queue);
    gBridge.timeUnit = (*env)->NewGlobalRef(env, unitMillis);

    (*env)->DeleteLocalRef(env, queue);
    (*env)->DeleteLocalRef(env, unitMillis);
    (*env)->DeleteLocalRef(env, clsQueue);
    (*env)->DeleteLocalRef(env, clsUnit);

    gBridge.initialized = true;
    pthread_mutex_unlock(&gBridge.initMutex);
    return true;
}

static void throw_java(JNIEnv *env, const char *className, const char *message)
{
    jclass cls = (*env)->FindClass(env, className);
    if (cls)
        (*env)->ThrowNew(env, cls, message);
}

/// Close both ends of a pipe fd pair, tolerating already-closed fds.
static void close_pair(int *r, int *w)
{
    if (*r >= 0) { close(*r); *r = -1; }
    if (*w >= 0) { close(*w); *w = -1; }
}

/// Destroy a finished or still-running session: cut stdin (EOF ends the GTP loop), join the
/// engine thread, then the demux/pump pipeline, and finally restore the process stdio.
static void destroy_session(Session *s)
{
    if (!s)
        return;

    // If the engine loop is still inside a search it will not read stdin until the search
    // finishes; closing the write end is still the right signal — the next fgets() sees EOF.
    close_pair(&s->cmdFd[0], &s->cmdFd[1]);

    if (s->engineThread)
        pthread_join(s->engineThread, NULL);

    // Restore the process stdio BEFORE waiting on the demux thread: fd 1/fd 2 are still
    // dup'ed copies of the pipes' write ends, and the demux thread only sees pipe EOF once
    // every copy of the write end is gone. After the engine thread has joined no engine
    // output can arrive, so restoring early is safe.
    if (gStdioSaved) {
        dup2(gSavedStdio[0], STDIN_FILENO);
        dup2(gSavedStdio[1], STDOUT_FILENO);
        dup2(gSavedStdio[2], STDERR_FILENO);
    }
    // Engine thread closed out/err write ends on exit; make sure they are gone either way.
    close_pair(&s->outFd[0], &s->outFd[1]);
    close_pair(&s->errFd[0], &s->errFd[1]);

    if (s->demuxThread)
        pthread_join(s->demuxThread, NULL);
    if (s->pumpThread)
        pthread_join(s->pumpThread, NULL);

    pipe_destroy(&s->pipe);
    pthread_mutex_destroy(&s->writeMutex);
    free(s);
}


// -------------------------------------------------
// JNI surface: com.qwara.go.engine.PachiNative

/// Start a new engine session in {@code workDir} (engine data files are resolved against
/// it). Any previous session is destroyed first; afterwards at most one GTP loop is live.
JNIEXPORT void JNICALL
Java_com_qwara_go_engine_PachiNative_start(JNIEnv *env, jclass, jstring workDir)
{
    const char *dir = workDir ? (*env)->GetStringUTFChars(env, workDir, NULL) : NULL;
    if (!dir || !*dir) {
        if (dir)
            (*env)->ReleaseStringUTFChars(env, workDir, dir);
        throw_java(env, "java/lang/IllegalArgumentException", "start(): workDir must not be empty");
        return;
    }

    // Resolve the shared Java handles before taking the session lock: the lookups below run a
    // little Java code, which must not happen while another thread is blocked on that lock.
    if (!init_java_bridge(env)) {
        (*env)->ReleaseStringUTFChars(env, workDir, dir);
        throw_java(env, "java/lang/IllegalStateException", gBridge.initError);
        return;
    }

    pthread_mutex_lock(&gSessionMutex);

    destroy_session(gSession);
    gSession = NULL;

    // Drop anything the previous game left in the queue so the new session starts clean.
    drain_java_queue(env);

    // Engine data files (patterns, opening book) resolve relative to the working directory.
    // chdir is process-wide and is deliberately left in place for the session lifetime.
    if (chdir(dir) != 0) {
        pthread_mutex_unlock(&gSessionMutex);
        (*env)->ReleaseStringUTFChars(env, workDir, dir);
        throw_java(env, "java/lang/IllegalStateException", "start(): cannot enter workDir");
        return;
    }
    (*env)->ReleaseStringUTFChars(env, workDir, dir);

    Session *s = calloc(1, sizeof(Session));
    pthread_mutex_init(&s->writeMutex, NULL);
    pipe_init(&s->pipe);
    s->cmdFd[0] = s->cmdFd[1] = -1;
    s->outFd[0] = s->outFd[1] = -1;
    s->errFd[0] = s->errFd[1] = -1;

    if (pipe(s->cmdFd) != 0 || pipe(s->outFd) != 0 || pipe(s->errFd) != 0) {
        pthread_mutex_unlock(&gSessionMutex);
        destroy_session(s);
        throw_java(env, "java/lang/IllegalStateException", "start(): pipe() failed");
        return;
    }

    // Capture the process stdio once, then point the standard fds at this session's pipes.
    // Everything the engine prints via printf/fprintf lands in out/errPipe; fgets(stdin)
    // reads cmdPipe. Other JVM writers to fd 1/2 are rare and harmless: the Java parser
    // only reacts to GTP-shaped lines.
    if (!gStdioSaved) {
        gSavedStdio[0] = dup(STDIN_FILENO);
        gSavedStdio[1] = dup(STDOUT_FILENO);
        gSavedStdio[2] = dup(STDERR_FILENO);
        gStdioSaved = true;
    }
    dup2(s->cmdFd[0], STDIN_FILENO);
    dup2(s->outFd[1], STDOUT_FILENO);
    dup2(s->errFd[1], STDERR_FILENO);
    // Writes to a pipe whose read end disappears must raise EPIPE, not kill the process.
    signal(SIGPIPE, SIG_IGN);

    s->running = true;
    gSession = s;

    pthread_create(&s->demuxThread, NULL, demux_main, s);
    pthread_create(&s->engineThread, NULL, engine_main, s);
    pthread_create(&s->pumpThread, NULL, pump_main, s);

    pthread_mutex_unlock(&gSessionMutex);
}

/// Duplicate the process stdout fd before the first session hijacks it. The host harness
/// uses this to repoint System.out/System.err at the duplicate (via /proc/self/fd/<n>) so
/// its own diagnostics do not feed back into the engine output pipe; the app never prints
/// to System.out and does not need this.
JNIEXPORT jint JNICALL
Java_com_qwara_go_engine_PachiNative_dupStdout(JNIEnv *, jclass)
{
    int fd = dup(STDOUT_FILENO);
    return fd >= 0 ? fd : -1;
}

/// Queue one GTP command line for the engine. Safe to call from any thread; a dead session
/// silently swallows the write (the reader side already failed its waiters).
JNIEXPORT void JNICALL
Java_com_qwara_go_engine_PachiNative_write(JNIEnv *env, jclass, jstring line)
{
    const char *text = line ? (*env)->GetStringUTFChars(env, line, NULL) : NULL;
    if (!text)
        return;

    pthread_mutex_lock(&gSessionMutex);
    Session *s = gSession;
    if (s) {
        pthread_mutex_lock(&s->writeMutex);
        size_t len = strlen(text);
        ssize_t ignored = write(s->cmdFd[1], text, len);
        (void)ignored;
        ignored = write(s->cmdFd[1], "\n", 1);
        (void)ignored;
        pthread_mutex_unlock(&s->writeMutex);
    }
    pthread_mutex_unlock(&gSessionMutex);

    (*env)->ReleaseStringUTFChars(env, line, text);
}

/// Block until one engine output line is available, then return it. Returns null once the
/// session has ended and the pump has flushed, which is the reader thread's exit condition.
JNIEXPORT jstring JNICALL
Java_com_qwara_go_engine_PachiNative_readLine(JNIEnv *env, jclass)
{
    jobject queue = NULL;
    jobject unit = NULL;
    jmethodID poll = NULL;
    {
        pthread_mutex_lock(&gSessionMutex);
        if (gSession && gBridge.queue) {
            queue = gBridge.queue;
            unit = gBridge.timeUnit;
            poll = gBridge.queuePoll;
        }
        pthread_mutex_unlock(&gSessionMutex);
    }
    if (!queue)
        return NULL;

    for (;;) {
        // Wake up periodically rather than waiting once for the whole timeout, so a session
        // that ended without another line still lets the reader observe the shutdown promptly.
        jobject line = (*env)->CallObjectMethod(env, queue, poll, (jlong)100, unit);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            return NULL;
        }
        if (line)
            return (jstring)line;

        pthread_mutex_lock(&gSessionMutex);
        Session *s = gSession;
        bool dead = !s || (!s->running && s->pumpFinished);
        pthread_mutex_unlock(&gSessionMutex);
        if (dead)
            return NULL;
    }
}

/// Whether the GTP loop thread is still alive. After stdin hits EOF the output queue may
/// still hold lines that were produced before the loop returned.
JNIEXPORT jboolean JNICALL
Java_com_qwara_go_engine_PachiNative_isRunning(JNIEnv *, jclass)
{
    pthread_mutex_lock(&gSessionMutex);
    Session *s = gSession;
    bool running = s && s->running;
    pthread_mutex_unlock(&gSessionMutex);
    return running ? JNI_TRUE : JNI_FALSE;
}

/// Destroy the current session (if any): EOF on the engine's stdin ends the GTP loop, the
/// engine and its search threads are joined, stdio is restored. The app calls this from
/// onCleared(); host tests call it before System.exit so the JVM can actually shut down.
JNIEXPORT void JNICALL
Java_com_qwara_go_engine_PachiNative_shutdown(JNIEnv *, jclass)
{
    pthread_mutex_lock(&gSessionMutex);
    destroy_session(gSession);
    gSession = NULL;
    pthread_mutex_unlock(&gSessionMutex);
}

