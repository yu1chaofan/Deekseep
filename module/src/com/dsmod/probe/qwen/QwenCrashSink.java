package com.dsmod.probe.qwen;

/**
 * Crash sink for the Qwen adapter.
 *
 * <p>The DeepSeek branch installs its own handler inside {@code Main.installCrashHandler} and
 * writes {@code dsprobe_crash.log} under the DeepSeek sandbox. The Qwen branch cannot call that
 * method — it would import the whole DeepSeek class graph into a process that has none of it — so
 * the handler is re-created here against {@link QwenPaths}.</p>
 *
 * <p>The reason this matters: a device with no root and no adb has no logcat, so a crash file in
 * the host sandbox is the only record of why the adapter failed to attach.</p>
 */
final class QwenCrashSink {
    private static volatile boolean installed;

    private QwenCrashSink() {}

    /** Chains a handler in front of the current default one; idempotent. */
    static synchronized void install() {
        if (installed) return;
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        record(thread, error);
                    } catch (Throwable ignored) {}
                    // Always delegate: the platform dialog and the loader's own reporting must
                    // keep working, the adapter only observes.
                    if (previous != null) previous.uncaughtException(thread, error);
                }
            });
            installed = true;
        } catch (Throwable ignored) {}
    }

    private static void record(Thread thread, Throwable error) {
        if (error == null) return;
        StringBuilder body = new StringBuilder();
        body.append("qwen adapter crash: thread=")
                .append(thread == null ? "?" : thread.getName())
                .append('\n');
        body.append(describe(error)).append('\n');
        StackTraceElement[] frames = error.getStackTrace();
        if (frames != null) {
            for (StackTraceElement frame : frames) {
                body.append("    at ").append(frame).append('\n');
            }
        }
        Throwable cause = error.getCause();
        if (cause != null) body.append("caused by: ").append(describe(cause)).append('\n');
        QwenPaths.appendLine(QwenPaths.CRASH_LOG_PATH, body.toString());
    }

    /**
     * Renders a throwable as {@code Class: message}.
     *
     * <p>{@code getMessage()} on a badly serialised host throwable can itself throw, so the
     * rendering is guarded; an unprintable exception must still leave a usable trace.</p>
     */
    static String describe(Throwable error) {
        if (error == null) return "null";
        try {
            String message = error.getMessage();
            return message == null
                    ? error.getClass().getName()
                    : error.getClass().getName() + ": " + message;
        } catch (Throwable ignored) {
            return "unprintable-throwable";
        }
    }
}
