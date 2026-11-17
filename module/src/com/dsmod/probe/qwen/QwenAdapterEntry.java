package com.dsmod.probe.qwen;

import java.util.Locale;

/**
 * Attach entry point for the Qwen Studio adapter.
 *
 * <p>{@code Main.handleLoadPackage} branches here before its DeepSeek gate, calls
 * {@link #tryAttach} and returns, so nothing in the DeepSeek bootstrap (its {@code LOG_PATH}
 * reset, its {@code HostCompat} probe, its retired-host notice, its
 * {@code installAllFeatureHooks}) ever runs for a Qwen package. The DeepSeek chain stays
 * byte-identical for its own hosts, which is the entire point of branching rather than widening
 * its target list.</p>
 *
 * <p>This class does one thing: gate, probe, attach, or fail safely. Logging, load markers and
 * crash capture live in {@link QwenLog}, {@link QwenLoadMarker} and {@link QwenCrashSink} so the
 * sequence below stays linear and auditable.</p>
 *
 * <p><strong>Return contract:</strong> {@code true} means "the adapter itself attached and wrote
 * its diagnostics". It does <em>not</em> mean any DeepSeek feature works under Qwen — every
 * DeepSeek hook is Tier C and is never installed here.</p>
 *
 * <p><strong>Failure contract:</strong> no path throws. An exception escaping an Xposed
 * {@code handleLoadPackage} callback kills the host process, so every failure returns
 * {@code false} and reconstructs nothing that was not already there.</p>
 */
public final class QwenAdapterEntry {
    private static final String INJECTED_PID_PROPERTY = "deekseep.module.injected.pid";

    /** Set before any work so a re-entered callback cannot attach twice. */
    private static volatile boolean attached;

    /** True when attach completed but a non-essential capability was lost. */
    private static volatile boolean degraded;

    private static volatile String lastResult = "not-attempted";

    private QwenAdapterEntry() {}

    /**
     * Attempts to attach the adapter to the current Qwen process.
     *
     * @param loader      host class loader from the Xposed callback; may be null
     * @param packageName host package name from the callback; may be null
     * @return {@code true} only when the attach sequence completed
     */
    public static boolean tryAttach(ClassLoader loader, String packageName) {
        if (attached) {
            lastResult = "already-attached";
            return true;
        }
        if (!QwenHost.isQwenPackage(packageName)) {
            lastResult = "package-mismatch";
            return false;
        }
        if (loader == null) {
            lastResult = "null-classloader";
            return false;
        }
        if (!claimCurrentProcess()) {
            lastResult = "duplicate-injection";
            return false;
        }
        attached = true;
        try {
            return attach(loader);
        } catch (Throwable t) {
            // Unwinding into the Xposed callback would kill the host; declare failure instead.
            attached = false;
            degraded = true;
            lastResult = "attach-failed:" + QwenCrashSink.describe(t);
            QwenLog.safe("attach failed: " + QwenCrashSink.describe(t));
            return false;
        }
    }

    /** True when the attach sequence completed in this process. */
    public static boolean isAttached() {
        return attached;
    }

    /** True when the adapter attached but lost a non-essential capability. */
    public static boolean isDegraded() {
        return degraded;
    }

    /** One-line outcome of the last attempt, safe to render in any UI. */
    public static String lastResult() {
        return lastResult;
    }

    /**
     * Module master switch; absence of the marker file means enabled.
     *
     * <p>A fresh install must work without the user creating any file, so "missing" cannot mean
     * disabled. The DeepSeek branch uses the same convention for its own switches.</p>
     */
    public static boolean isModuleEnabled() {
        try {
            String value = QwenPaths.readFile(QwenPaths.MODULE_ENABLED_FILE);
            if (value == null) return true;
            String normalized = value.trim().toLowerCase(Locale.US);
            return !("0".equals(normalized) || "false".equals(normalized)
                    || "off".equals(normalized) || "disabled".equals(normalized));
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Full status block for bug reports.
     *
     * <p>Never triggers a probe: before attach the {@link QwenCompat} fields are simply
     * uninitialised, and probing from a UI thread outside the host loader would answer a different
     * question than the one being reported.</p>
     */
    public static String diagnosticSummary() {
        StringBuilder out = new StringBuilder();
        out.append(QwenCompat.diagnosticSummary()).append('\n');
        out.append(QwenPaths.describe()).append('\n');
        out.append("attached=").append(attached).append('\n');
        out.append("degraded=").append(degraded).append('\n');
        out.append("lastResult=").append(lastResult).append('\n');
        out.append("prefs=moduleEnabled:").append(isModuleEnabled());
        return out.toString();
    }

    private static boolean attach(ClassLoader loader) {
        QwenCompat.initialize(loader);
        if (!QwenCompat.isSupported()) {
            lastResult = "unsupported-host";
            QwenLog.safe("host probe failed; adapter idle. " + QwenCompat.diagnosticSummary());
            QwenLoadMarker.write("unsupported-host " + QwenCompat.generationName());
            return false;
        }
        // The version name is not available on this branch yet (see QwenHost: the manifest read
        // lives in DeepSeek-side code). Symbol support above is the operative gate; the string
        // gate activates as soon as the bootstrap hands over appInfo.sourceDir.
        if (!isModuleEnabled()) {
            lastResult = "module-disabled";
            QwenLoadMarker.write("module-disabled");
            return false;
        }
        boolean sandboxReady = QwenPaths.ensureFilesDir();
        if (sandboxReady) {
            QwenPaths.resetFile(QwenPaths.LOG_PATH);
        } else {
            // Refusing to attach over an unwritable sandbox would hide the adapter on exactly the
            // devices that most need its diagnostics; degrade instead.
            degraded = true;
        }
        QwenLog.safe("module loaded (qwen adapter), package=" + QwenHost.PACKAGE
                + ", hostGeneration=" + QwenCompat.generationName()
                + ", sandbox=" + (sandboxReady ? "ready" : "read-only"));
        QwenLog.safe(QwenCompat.diagnosticSummary());
        QwenLoadMarker.write("loaded hostGeneration=" + QwenCompat.generationName());
        QwenCrashSink.install();
        // Tier A ends here: attached and self-describing, with no host hook installed. Tier B needs
        // a symbol comparison table that does not exist yet; Tier C is never reachable from here.
        lastResult = degraded ? "attached-degraded" : "attached";
        return true;
    }

    /**
     * Claims the current process via a system property.
     *
     * <p>Guarded end to end: a {@code SecurityException} from a locked-down host must degrade to
     * "attach anyway" rather than block the adapter entirely.</p>
     */
    private static boolean claimCurrentProcess() {
        try {
            String pid = String.valueOf(android.os.Process.myPid());
            if (pid.equals(System.getProperty(INJECTED_PID_PROPERTY))) return false;
            System.setProperty(INJECTED_PID_PROPERTY, pid);
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
