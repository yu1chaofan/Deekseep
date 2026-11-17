package com.dsmod.probe.qwen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Load marker writer for the Qwen adapter.
 *
 * <p>Exists so the module can prove from outside the process that it reached Qwen, matching what
 * {@code Main} does for DeepSeek at its {@code LOADED_MARK_EXT} write. Kept separate from the log
 * sink because the two files answer different questions: the log answers "what happened", the
 * marker answers "did it get here at all" and must survive even when logging is truncated on
 * attach.</p>
 */
final class QwenLoadMarker {
    private QwenLoadMarker() {}

    /**
     * Writes the same status line to the sandbox marker and to the external mirror.
     *
     * <p>The sandbox copy is authoritative; the external copy is best effort because storage
     * permission is commonly missing. Both are attempted so a device with no readable sandbox
     * (no root, no adb) still leaves a trace in {@code /storage/emulated/0}.</p>
     */
    static void write(String reason) {
        try {
            String line = timestamp() + "  " + reason + "  pid=" + currentPid() + "\n";
            QwenPaths.writeFile(QwenPaths.LOADED_MARK_PATH, line);
            QwenPaths.writeFile(QwenPaths.LOADED_MARK_EXT, line);
        } catch (Throwable ignored) {}
    }

    private static String timestamp() {
        try {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable ignored) {
            return "??:??:??.???";
        }
    }

    private static String currentPid() {
        try {
            return String.valueOf(android.os.Process.myPid());
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
