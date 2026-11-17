package com.dsmod.probe.qwen;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Every file path the adapter is allowed to touch inside the Qwen Studio sandbox.
 *
 * <p>The DeepSeek branch hard-codes {@code /data/data/com.deepseek.chat/...} at its call sites.
 * Re-pointing those literals at a second host would mean editing dozens of unrelated lines and
 * would make the DeepSeek storage contract regress together with any Qwen change. Instead the
 * Qwen branch owns this class and never reads a host path from anywhere else: one file to audit,
 * one file to roll back.</p>
 *
 * <p>File names deliberately keep the {@code deekseep_} prefix. Users already recognise those
 * names, the log and prompt formats are shared with the DeepSeek branch, and a side-by-side
 * comparison between the two hosts stays possible without translation.</p>
 */
public final class QwenPaths {
    /** Host {@code files/} directory; the only writable root the adapter uses. */
    public static final String FILES_DIR = QwenHost.FILES_DIR;

    /** Main module log, mirroring the DeepSeek branch's {@code dsprobe.log}. */
    public static final String LOG_PATH = FILES_DIR + "/dsprobe.log";

    /** Crash dump written by the adapter's uncaught-exception handler. */
    public static final String CRASH_LOG_PATH = FILES_DIR + "/dsprobe_crash.log";

    /** Server-response diagnostic log, mirroring {@code deekseep_srv.log}. */
    public static final String SRV_LOG_PATH = FILES_DIR + "/deekseep_srv.log";

    /** Prompt text consumed by the (future) dialogue hook. */
    public static final String PROMPT_FILE = FILES_DIR + "/deekseep_prompt.txt";

    /** Editable source copy of {@link #PROMPT_FILE}. */
    public static final String PROMPT_SOURCE_FILE = FILES_DIR + "/deekseep_prompt_source.txt";

    /** Toggle that records whether the user enabled server-response logging. */
    public static final String SRVLOG_FILE = FILES_DIR + "/deekseep_srvlog";

    /** Automatic chat backup switch. */
    public static final String AUTO_BACKUP_FILE = FILES_DIR + "/deekseep_auto_backup";

    /** First-run notice acknowledgement marker. */
    public static final String DISCLAIMER_FILE = FILES_DIR + "/deekseep_disclaimer_ok";

    /** Master switch for the whole Qwen adapter; absence means enabled. */
    public static final String MODULE_ENABLED_FILE = FILES_DIR + "/deekseep_qwen_module_enabled";

    /** Marker that pins the host channel when auto-detection would be wrong. */
    public static final String GOOGLE_PLAY_MARKER_FILE = FILES_DIR + "/deekseep_qwen_google_play";

    /**
     * In-sandbox load marker.
     *
     * <p>Separate from the external marker because the external one is shared storage and may be
     * unreadable when the host holds no storage permission.</p>
     */
    public static final String LOADED_MARK_PATH = FILES_DIR + "/deekseep_loaded";

    /**
     * External load marker visible from Termux and other media_rw readers.
     *
     * <p>{@code /storage/emulated/0} is not uid-isolated, so the Qwen copy carries
     * {@link QwenHost#EXTERNAL_SUFFIX}; otherwise both hosts would overwrite one file and the
     * marker could no longer answer "which host loaded last".</p>
     */
    public static final String LOADED_MARK_EXT =
            "/storage/emulated/0/deekseep_loaded_m" + QwenHost.EXTERNAL_SUFFIX + ".txt";

    /** External mirror of the main log, suffixed for the same reason as the marker. */
    public static final String EXT_MAIN_LOG =
            "/storage/emulated/0/dsprobe_m" + QwenHost.EXTERNAL_SUFFIX + ".log";

    /** External mirror of the server-response log. */
    public static final String SRV_LOG_EXT =
            "/storage/emulated/0/deekseep_srv" + QwenHost.EXTERNAL_SUFFIX + ".log";

    private QwenPaths() {}

    /**
     * Builds a path below {@link #FILES_DIR} from a bare file name.
     *
     * <p>Returns {@code null} for anything that could escape the sandbox. A future Tier B call
     * site will receive user-controlled settings keys, and a single {@code ..} would let the
     * adapter write into another application's data directory; rejecting at the boundary is
     * cheaper than auditing every caller.</p>
     */
    public static String file(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.length() == 0) return null;
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) return null;
        if (trimmed.indexOf("..") >= 0) return null;
        return FILES_DIR + "/" + trimmed;
    }

    /** Returns {@code true} when the path resolves below the host {@code files/} directory. */
    public static boolean isInsideSandbox(String absolutePath) {
        return absolutePath != null && absolutePath.startsWith(FILES_DIR + "/");
    }

    /** Thin wrapper so callers never depend on {@link File} construction directly. */
    public static File asFile(String absolutePath) {
        return absolutePath == null ? null : new File(absolutePath);
    }

    /**
     * Creates {@code files/} when missing.
     *
     * <p>Called once during attach. A failure is reported to the caller instead of thrown: without
     * a writable directory the adapter still runs, it merely loses persistence, and killing the
     * host over an unwritable sandbox would be far worse than a degraded adapter.</p>
     */
    public static boolean ensureFilesDir() {
        try {
            File dir = new File(FILES_DIR);
            if (dir.isDirectory()) return true;
            return dir.mkdirs() || dir.isDirectory();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Truncates {@code path} and recreates it, mirroring the DeepSeek log reset on attach. */
    public static boolean resetFile(String absolutePath) {
        if (absolutePath == null) return false;
        try {
            FileWriter writer = new FileWriter(absolutePath, false);
            writer.close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Appends one already-formatted line; silent on failure by design. */
    public static boolean appendLine(String absolutePath, String line) {
        if (absolutePath == null || line == null) return false;
        try {
            FileWriter writer = new FileWriter(absolutePath, true);
            writer.write(line);
            writer.close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Writes {@code line} to {@code path}, replacing any previous content. */
    public static boolean writeFile(String absolutePath, String line) {
        if (absolutePath == null || line == null) return false;
        try {
            FileWriter writer = new FileWriter(absolutePath, false);
            writer.write(line);
            writer.close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Reads a small file fully; returns {@code null} when it does not exist or cannot be read.
     *
     * <p>Bounded to 64 KiB and decoded as UTF-8 explicitly. The implicit
     * {@code new String(byte[])} constructor uses the platform charset, which on Android is UTF-8
     * today but is not a contract worth depending on for a file users may edit from Termux.</p>
     */
    public static String readFile(String absolutePath) {
        if (absolutePath == null) return null;
        File file = new File(absolutePath);
        if (!file.isFile()) return null;
        try {
            long length = file.length();
            if (length <= 0 || length > 65536L) return null;
            byte[] buffer = new byte[(int) length];
            FileInputStream in = new FileInputStream(file);
            try {
                int read = 0;
                while (read < buffer.length) {
                    int step = in.read(buffer, read, buffer.length - read);
                    if (step < 0) break;
                    read += step;
                }
                return new String(buffer, 0, read, "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Diagnostic one-liner used by {@link QwenAdapterEntry#diagnosticSummary()}. */
    public static String describe() {
        return "filesDir=" + FILES_DIR
                + "\nlog=" + LOG_PATH
                + "\nexternalLog=" + EXT_MAIN_LOG
                + "\nloadedMark=" + LOADED_MARK_EXT;
    }
}
