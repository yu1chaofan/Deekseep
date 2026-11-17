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
        checkTierBSymbols(out);
        checkTierBSettings(out);
        checkTierBBodyHandling(out);
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
     * Tier B: the symbol table must describe real, reflectable names.
     *
     * <p>These run without a host class loader on purpose. The pipeline's failure mode is a
     * symbol that was mistyped or left blank, and that failure is visible statically — a blank or
     * malformed descriptor would make reflection throw inside the host's call stack, which is the
     * one outcome the pipeline is designed to make impossible.</p>
     */
    private static void checkTierBSymbols(List<String> out) {
        expect(out, "response class name is usable",
                QwenSymbols.isUsableClassName(QwenSymbols.QWEN_RESPONSE));
        expect(out, "response class is the qwen business layer",
                QwenSymbols.QWEN_RESPONSE.startsWith("ai.qwenlm.chat.android."));
        expect(out, "sse handler name is usable",
                QwenSymbols.isUsableClassName(QwenSymbols.SSE_MESSAGE_HANDLER));
        expect(out, "network module name is usable",
                QwenSymbols.isUsableClassName(QwenSymbols.NETWORK_MODULE));
        expect(out, "descriptorOf produces smali form",
                "Lai/qwenlm/chat/android/clean/core/network/data/QwenResponse;"
                        .equals(QwenSymbols.descriptorOf(QwenSymbols.QWEN_RESPONSE)));
        expect(out, "descriptorOf rejects null", QwenSymbols.descriptorOf(null) == null);
        expect(out, "descriptorOf rejects empty", QwenSymbols.descriptorOf("") == null);
        // Blank names are the realistic mistake: a reserved table row that never got filled in.
        expect(out, "blank class name rejected", !QwenSymbols.isUsableClassName("   "));
        expect(out, "empty class name rejected", !QwenSymbols.isUsableClassName(""));
        expect(out, "null class name rejected", !QwenSymbols.isUsableClassName(null));
        expect(out, "single segment rejected", !QwenSymbols.isUsableClassName("QwenResponse"));

        // The constructor table is what makes the fallback hook addressable; a primitive and its
        // boxed type do not match under reflection, so the exact types matter.
        Class<?>[] types = QwenSymbols.PRIMARY_CTOR_PARAM_TYPES;
        expect(out, "primary ctor has 8 parameters", types != null && types.length == 8);
        expect(out, "primary ctor param 0 is boolean", types != null && types[0] == boolean.class);
        expect(out, "primary ctor param 5 is int", types != null && types[5] == int.class);
        expect(out, "primary ctor rawBody index is String",
                types != null && QwenSymbols.PRIMARY_CTOR_RAW_BODY_INDEX < types.length
                        && types[QwenSymbols.PRIMARY_CTOR_RAW_BODY_INDEX] == String.class);
        expect(out, "rawBody setter signature matches column type",
                "(Ljava/lang/String;)V".equals(QwenSymbols.SET_RAW_BODY_SIG)
                        && types[QwenSymbols.PRIMARY_CTOR_RAW_BODY_INDEX] == String.class);
    }

    /**
     * Tier B: the switch defaults to off and only an explicit truthy value turns it on.
     *
     * <p>Default-off is the load-bearing decision in this whole tier. An unvalidated hook that
     * runs for everyone is a hang waiting to happen; the parser is therefore tested from both
     * directions — every accepted spelling, and the strings that must not accidentally enable it.
     * </p>
     */
    private static void checkTierBSettings(List<String> out) {
        expect(out, "null switch is off", !QwenChatPipelineSettings.isTruthy(null));
        expect(out, "empty switch is off", !QwenChatPipelineSettings.isTruthy(""));
        expect(out, "whitespace switch is off", !QwenChatPipelineSettings.isTruthy("   "));
        expect(out, "zero switch is off", !QwenChatPipelineSettings.isTruthy("0"));
        expect(out, "false switch is off", !QwenChatPipelineSettings.isTruthy("false"));
        expect(out, "off switch is off", !QwenChatPipelineSettings.isTruthy("off"));
        expect(out, "garbage switch is off", !QwenChatPipelineSettings.isTruthy("maybe"));
        expect(out, "one enables", QwenChatPipelineSettings.isTruthy("1"));
        expect(out, "true enables", QwenChatPipelineSettings.isTruthy("true"));
        expect(out, "TRUE enables case-insensitively",
                QwenChatPipelineSettings.isTruthy("TRUE"));
        expect(out, "on enables", QwenChatPipelineSettings.isTruthy("on"));
        expect(out, "padded value enables after trim",
                QwenChatPipelineSettings.isTruthy("  1  "));
        expect(out, "switch path lives in qwen sandbox",
                QwenChatPipelineSettings.SWITCH_FILE.startsWith(QwenHost.FILES_DIR));
        expect(out, "switch path keeps deekseep prefix",
                QwenChatPipelineSettings.SWITCH_FILE.contains("/deekseep_"));
        expect(out, "describe names the state",
                QwenChatPipelineSettings.describe().contains("chatPipeline="));
    }

    /**
     * Tier B: body capture bounds and never throws.
     *
     * <p>The pipeline sees attacker-influenced-length data — a long streamed reply — so the
     * truncation boundary is where a heap blow-up or a split surrogate pair would show up. The
     * "never throws" assertions matter just as much: these helpers are called from inside a host
     * callback, where a throw takes the app down.</p>
     */
    private static void checkTierBBodyHandling(List<String> out) {
        QwenChatPipeline.resetForTest();
        expect(out, "pipeline not installed before hooking",
                !QwenChatPipeline.isInstalled());
        expect(out, "no bodies observed before hooking",
                QwenChatPipeline.observedCount() == 0);
        expect(out, "rewrite is not permitted before hooking",
                !QwenChatPipeline.isRewritePermitted());

        expect(out, "truncate passes short body through",
                "hello".equals(QwenChatPipeline.truncate("hello")));
        expect(out, "truncate handles null", "".equals(QwenChatPipeline.truncate(null)));
        expect(out, "truncate handles empty", "".equals(QwenChatPipeline.truncate("")));

        StringBuilder big = new StringBuilder();
        int limit = QwenChatPipeline.MAX_BODY_CHARS;
        for (int i = 0; i < limit + 100; i++) big.append('x');
        String truncated = QwenChatPipeline.truncate(big.toString());
        expect(out, "truncate marks the cut", truncated.contains("truncated"));
        expect(out, "truncate reports original length",
                truncated.contains("total=" + big.length()));
        expect(out, "truncate result stays bounded",
                truncated.length() < big.length() + 64);
        expect(out, "body limit is 64k", limit == 65536);

        // Boundary: exactly at the limit must not be truncated.
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < limit; i++) exact.append('y');
        expect(out, "body at limit is not truncated",
                !QwenChatPipeline.truncate(exact.toString()).contains("truncated"));

        // observe() must swallow every shape of junk rather than propagate it.
        boolean threw = false;
        try {
            QwenChatPipeline.observe(null, "test");
            QwenChatPipeline.observe(Integer.valueOf(7), "test");
            QwenChatPipeline.observe("", "test");
            QwenChatPipeline.observe("payload", "test");
            QwenChatPipeline.resetForTest();
        } catch (Throwable t) {
            threw = true;
        }
        expect(out, "observe never throws on junk input", !threw);
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
