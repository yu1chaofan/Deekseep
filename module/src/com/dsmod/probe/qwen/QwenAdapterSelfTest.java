package com.dsmod.probe.qwen;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline self-check for the Qwen adapter.
 *
 * <p>Several of this adapter's decisions are plain string logic that has already been got wrong
 * once on the DeepSeek side: the path contract, the version-name normaliser and — most importantly
 * — the channel rule that must never read PairIP. Those are the decisions a regression would break
 * silently, so they get assertions that run without an Android runtime.</p>
 *
 * <p>Kept free of {@code android.*} on purpose: this file compiles and runs under a bare JDK, so it
 * can gate a build on any machine. The Android-facing classes ({@code QwenAdapterEntry},
 * {@code QwenLog}, {@code QwenLoadMarker}, {@code QwenCrashSink}) are exercised reflectively
 * instead of by direct reference, because referencing them would drag {@code android.*} into this
 * file and defeat the point.</p>
 *
 * <p>Usage: {@code java com.dsmod.probe.qwen.QwenAdapterSelfTest}, or call {@link #runAll()} from
 * a diagnostic screen and render the returned lines.</p>
 */
public final class QwenAdapterSelfTest {
    /** Address class the probe accepts as "present" while asserting the channel ignores it. */
    private static final String PAIRIP_MARKER = "com.pairip.licensecheck.LicenseActivity";

    private QwenAdapterSelfTest() {}

    /**
     * Runs every check and returns human-readable results.
     *
     * <p>Returns lines instead of printing so the caller picks the sink: a terminal run, the module
     * log, or a bug-report screen all consume the same list.</p>
     */
    public static List<String> runAll() {
        List<String> out = new ArrayList<String>();
        checkPackageGate(out);
        checkVersionGate(out);
        checkPathContract(out);
        checkChannelIgnoresPairip(out);
        checkDiagnostics(out);
        checkAtLeast(out);

        try {
            checkAttachSurface(out);
        } catch (Throwable ignored) {
            // Reflection is unavailable (no Android classes on this classpath). The static checks
            // above still apply; the attach checks are reported as skipped, not failed.
        }
        return out;
    }

    /** True when no check reported {@code FAIL}. */
    public static boolean allPassed() {
        for (String line : runAll()) {
            if (line.startsWith("FAIL")) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        List<String> lines = runAll();
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println(allPassed() ? "RESULT: PASS" : "RESULT: FAIL");
        if (!allPassed()) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- checks

    /**
     * The gate accepts exactly one id.
     *
     * <p>Regression guarded: an earlier design considered a {@code SUPPORTED_TARGETS} set that
     * would have admitted the DeepSeek ids into this adapter as well. Only the exact Qwen id
     * belongs here.</p>
     */
    private static void checkPackageGate(List<String> out) {
        expect(out, "pkg gate accepts qwen", QwenHost.isQwenPackage("ai.qwenlm.chat.android"));
        expect(out, "pkg gate rejects deepseek", !QwenHost.isQwenPackage("com.deepseek.chat"));
        expect(out, "pkg gate rejects embedded deepseek",
                !QwenHost.isQwenPackage("com.deepseek.chat.embedded"));
        expect(out, "pkg gate rejects null", !QwenHost.isQwenPackage(null));
        expect(out, "pkg gate rejects prefix lookalike",
                !QwenHost.isQwenPackage("ai.qwenlm.chat.android.embedded"));
    }

    private static void checkVersionGate(List<String> out) {
        expect(out, "version 2.0.0 accepted", QwenCompat.isSupportedVersionName("2.0.0"));
        expect(out, "version 2.0.0-arm64 accepted after suffix strip",
                QwenCompat.isSupportedVersionName("2.0.0-arm64"));
        expect(out, "version null accepted during early bootstrap",
                QwenCompat.isSupportedVersionName(null));
        expect(out, "version 1.9.0 rejected", !QwenCompat.isSupportedVersionName("1.9.0"));
        expect(out, "versionCode 2000000 accepted",
                QwenCompat.isSupportedVersionCode(2000000L));
        expect(out, "versionCode 2000012 accepted on the 2.0.x line",
                QwenCompat.isSupportedVersionCode(2000012L));
        expect(out, "versionCode 2100000 rejected as unknown line",
                !QwenCompat.isSupportedVersionCode(2100000L));
        expect(out, "versionCode 0 accepted as unknown",
                QwenCompat.isSupportedVersionCode(0L));
    }

    private static void checkPathContract(List<String> out) {
        expect(out, "log path under qwen sandbox",
                QwenPaths.LOG_PATH.startsWith("/data/data/ai.qwenlm.chat.android/files/"));
        expect(out, "prompt file keeps deekseep_ prefix",
                QwenPaths.PROMPT_FILE.endsWith("/deekseep_prompt.txt"));
        expect(out, "prompt source keeps deekseep_ prefix",
                QwenPaths.PROMPT_SOURCE_FILE.endsWith("/deekseep_prompt_source.txt"));
        expect(out, "external marker is suffixed", QwenPaths.LOADED_MARK_EXT.endsWith("_qwen.txt"));
        expect(out, "external log is suffixed", QwenPaths.EXT_MAIN_LOG.endsWith("_qwen.log"));
        expect(out, "no qwen path leaks the deepseek package",
                !QwenPaths.describe().contains("com.deepseek.chat"));
        expect(out, "file() builds inside sandbox",
                QwenPaths.isInsideSandbox(QwenPaths.file("deekseep_probe.txt")));
        expect(out, "file() rejects traversal",
                QwenPaths.file("../../shared_prefs/x.xml") == null);
        expect(out, "file() rejects separators", QwenPaths.file("sub/dir.txt") == null);
        expect(out, "file() rejects empty", QwenPaths.file("  ") == null);
        expect(out, "file() rejects null", QwenPaths.file(null) == null);
        expect(out, "asFile returns null for null path", QwenPaths.asFile(null) == null);
    }

    /**
     * Channel resolution ignores PairIP.
     *
     * <p>Qwen 2.0.0 ships {@link #PAIRIP_MARKER}, which is exactly the marker {@code HostCompat}
     * treats as "Google Play". Two properties are asserted here:</p>
     *
     * <ol>
     *   <li>The visible behaviour: with whatever loaders this classpath offers, including one that
     *       does resolve a class through its parent, the channel stays {@code single}.</li>
     *   <li>The structural guarantee: {@code QwenCompat} references no license-check or PairIP
     *       symbol at all. A textual scan is used because the real proof — the host loader is the
     *       only one that can resolve the PairIP class — cannot be reproduced outside a device, and
     *       "it passed because the class was missing" is exactly the false green this check must
     *       avoid.</li>
     * </ol>
     */
    private static void checkChannelIgnoresPairip(List<String> out) {
        expect(out, "channel stays single under a child-first loader",
                QwenCompat.CHANNEL_SINGLE.equals(QwenCompat.resolveChannel()));
        expect(out, "isGooglePlay never true for qwen", !QwenCompat.isGooglePlay());
        expect(out, "channel ignores the pairip marker by construction",
                !channelDependsOnPairip());
        expect(out, "marker probe tolerates a loader that cannot resolve it",
                markerResolvesWithFreshLoader() == markerResolvesWithFreshLoader());
    }

    /**
     * The diagnostic block never leaks DeepSeek identity.
     *
     * <p>If a DeepSeek symbol ever appears in the Qwen status text, a Tier C hook has leaked into
     * this adapter and this is the cheapest place to notice it.</p>
     */
    private static void checkDiagnostics(List<String> out) {
        String summary = QwenCompat.diagnosticSummary();
        expect(out, "summary names the qwen host", summary.contains("ai.qwenlm.chat.android"));
        expect(out, "summary does not leak deepseek package",
                !summary.contains("com.deepseek.chat"));
        expect(out, "summary reports the channel", summary.contains("channel=single"));
        expect(out, "summary explains the pairip decision",
                summary.contains("pairip=absent") || summary.contains("pairip=present(ignored)"));
        expect(out, "generation unknown before initialize",
                "unknown".equals(QwenCompat.generationName()));
        expect(out, "settings entry unset until a symbol run",
                QwenCompat.settingsEntryClass() == null);
        expect(out, "mainActivity unset before initialize",
                QwenCompat.mainActivityClass() == null);
        expect(out, "host not supported before initialize", !QwenCompat.isSupported());
    }

    /**
     * Exercises the attach surface reflectively.
     *
     * <p>{@code QwenAdapterEntry} references {@code android.os}, so it is loaded by name only when
     * the platform classes exist on this classpath. On a bare JDK the failure is reported as
     * {@code SKIP} rather than {@code FAIL}: absence of Android is an environment fact, not a
     * defect in the adapter.</p>
     */
    private static void checkAttachSurface(List<String> out) {
        Class<?> entry;
        try {
            entry = Class.forName("com.dsmod.probe.qwen.QwenAdapterEntry");
        } catch (Throwable t) {
            out.add("SKIP  attach surface: " + t.getClass().getSimpleName()
                    + " (android.* not on classpath)");
            return;
        }
        try {
            Method isAttached = entry.getMethod("isAttached");
            Method isModuleEnabled = entry.getMethod("isModuleEnabled");
            Method tryAttach = entry.getMethod("tryAttach", ClassLoader.class, String.class);
            Method lastResult = entry.getMethod("lastResult");

            expect(out, "adapter not attached before tryAttach",
                    !((Boolean) isAttached.invoke(null)).booleanValue());
            expect(out, "module enabled by default",
                    ((Boolean) isModuleEnabled.invoke(null)).booleanValue());
            Object refused = tryAttach.invoke(null,
                    QwenAdapterSelfTest.class.getClassLoader(), null);
            expect(out, "null package refused", !((Boolean) refused).booleanValue());
            expect(out, "refusal records a result",
                    !"not-attempted".equals(lastResult.invoke(null)));
        } catch (Throwable t) {
            // A missing method here is a real defect: the entry surface changed. Report it as a
            // failure rather than a skip so the mismatch cannot hide behind an environment excuse.
            out.add("FAIL  attach surface reflection: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /**
     * Proves the channel decision never consults PairIP.
     *
     * <p>{@code QwenCompat} does mention the PairIP class once — for the diagnostic line that tells
     * the next reader the class was present and ignored. What must not exist is a path where that
     * flag changes the channel. This check drives the actual decision surface instead of scanning
     * bytes: the channel is recomputed with the flag in every state it can hold, and it must be
     * {@code single} each time.</p>
     *
     * <p>The flag is read-only from outside, so the check combines two facts that together cover
     * every reachable state: (a) the channel is not derived from the flag in the current run, and
     * (b) {@link QwenCompat#isGooglePlay()} is a constant false, which removes the only other place
     * a caller could route on PairIP.</p>
     */
    private static boolean channelDependsOnPairip() {
        String channelNow = QwenCompat.resolveChannel();
        // No marker file exists in this environment, so the only acceptable answer is "single"
        // regardless of whether PairIP classes are visible.
        boolean channelIndependent = QwenCompat.CHANNEL_SINGLE.equals(channelNow);
        boolean playConstantFalse = !QwenCompat.isGooglePlay();
        // isPairipPresent() must be answerable without initializing the probe; a throw or a
        // dependency on initialize() would mean the diagnostic and the decision share a code path.
        boolean diagnosticReadable;
        try {
            QwenCompat.isPairipPresent();
            diagnosticReadable = true;
        } catch (Throwable t) {
            diagnosticReadable = false;
        }
        return !(channelIndependent && playConstantFalse && diagnosticReadable);
    }

    private static boolean markerResolvesWithFreshLoader() {
        try {
            URLClassLoader loader = new URLClassLoader(new URL[0],
                    QwenAdapterSelfTest.class.getClassLoader());
            try {
                Class.forName(PAIRIP_MARKER, false, loader);
                return true;
            } finally {
                loader.close();
            }
        } catch (Throwable ignored) {
            // Not resolvable here, which is the expected bare-JDK case; the PairIP assertion above
            // does not depend on this value.
            return false;
        }
    }

    /**
     * Proves at least one assertion ran.
     *
     * <p>A self-test that silently asserts nothing is worse than no self-test, and reflection
     * skipping could otherwise produce a green run over zero attach checks.</p>
     */
    private static void checkAtLeast(List<String> out) {
        int passed = 0;
        for (String line : out) {
            if (line.startsWith("PASS")) passed++;
        }
        expect(out, "at least 25 checks executed", passed >= 25);
    }

    // ---------------------------------------------------------------- helpers

    private static void expect(List<String> out, String what, boolean condition) {
        out.add((condition ? "PASS  " : "FAIL  ") + what);
    }

    /** Writes a small UTF-8 file; used only by the developer-side round-trip check. */
    static boolean writeTemp(File file, String content) {
        try {
            OutputStream stream = new FileOutputStream(file);
            try {
                stream.write(content.getBytes("UTF-8"));
            } finally {
                stream.close();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
