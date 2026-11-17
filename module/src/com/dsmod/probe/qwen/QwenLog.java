package com.dsmod.probe.qwen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Log sink for the Qwen adapter.
 *
 * <p>Split out of {@code QwenAdapterEntry} so the attach sequence stays a single readable chain.
 * The DeepSeek branch funnels everything through {@code Main.log}, but that method writes
 * {@code /data/data/com.deepseek.chat/files/dsprobe.log} and mirrors to the shared external
 * {@code dsprobe_m.log} — both wrong for this host and both would cross-contaminate if reused.
 * One small class owns the Qwen-side sinks instead.</p>
 */
final class QwenLog {
    /** Logcat tag, distinct from the DeepSeek branch's {@code DSPROBE}. */
    static final String TAG = "DSPROBE-QWEN";

    private QwenLog() {}

    /**
     * Writes one line to logcat, the sandbox log and the external mirror.
     *
     * <p>The three sinks are attempted independently. External storage is frequently absent or
     * permission-denied and a freshly created sandbox can be read-only, yet logcat remains
     * reachable in both cases, so one failed sink must never suppress the others.</p>
     */
    static synchronized void write(String message) {
        if (message == null) return;
        String line;
        try {
            SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            line = format.format(new Date()) + "  " + message + "\n";
        } catch (Throwable ignored) {
            line = message + "\n";
        }
        try {
            android.util.Log.i(TAG, message);
        } catch (Throwable ignored) {}
        QwenPaths.appendLine(QwenPaths.LOG_PATH, line);
        QwenPaths.appendLine(QwenPaths.EXT_MAIN_LOG, line);
    }

    /**
     * Logging that may never unwind into the caller.
     *
     * <p>Used on every attach path including the failure ones: a throw from a diagnostic must not
     * turn a clean "return false" into a host crash.</p>
     */
    static void safe(String message) {
        try {
            write(message);
        } catch (Throwable ignored) {}
    }
}
