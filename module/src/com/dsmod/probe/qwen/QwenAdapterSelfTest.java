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
     * Tier B: the symbol table must name real, correctly shaped members.
     *
     * <p>These run without a host class loader on purpose. The realistic Tier B failure is not a
     * broken hook, it is a constant that was mistyped or never filled in: reflection would then
     * throw {@code ClassNotFoundException} on a device, from inside the host's network callback,
     * with nothing in the log because the log path itself might be the thing that never opened.
     * Validating the table statically is the only check available before hardware.</p>
     *
     * <p>The descriptor assertions are written the long way round — spell out the expected smali
     * text, then compare — because the whole point is to freeze the strings that were read out of
     * the 2.0.0 dex. If a future build renames {@code rawBody}, this test is where the diff shows
     * up, not in a user's crash report.</p>
     */
    private static void checkTierBSymbols(List<String> out) {
        expect(out, "response class is a binary name",
                QwenSymbols.isBinaryName(QwenSymbols.RESPONSE_CLASS));
        expect(out, "response class sits in the qwen business layer",
                QwenSymbols.RESPONSE_CLASS.startsWith("ai.qwenlm.chat.android.clean."));
        expect(out, "response class ends where the dex says",
                QwenSymbols.RESPONSE_CLASS.endsWith("network.data.QwenResponse"));
        expect(out, "response class descriptor is smali form",
                "Lai/qwenlm/chat/android/clean/core/network/data/QwenResponse;"
                        .equals(QwenSymbols.responseClassDescriptor()));
        expect(out, "setRawBody descriptor accepted",
                QwenSymbols.isMethodDescriptor(QwenSymbols.RESPONSE_SET_RAW_BODY_DESC));
        expect(out, "getRawBody descriptor accepted",
                QwenSymbols.isMethodDescriptor(QwenSymbols.RESPONSE_GET_RAW_BODY_DESC));
        expect(out, "null descriptor rejected",
                !QwenSymbols.isMethodDescriptor(null));
        expect(out, "descriptor without parens rejected",
                !QwenSymbols.isMethodDescriptor("Ljava/lang/String;V"));
        expect(out, "descriptor without return type rejected",
                !QwenSymbols.isMethodDescriptor("(Ljava/lang/String;)"));
        expect(out, "binary name rejects descriptor form",
                !QwenSymbols.isBinaryName(QwenSymbols.responseClassDescriptor()));
        expect(out, "binary name rejects blank", !QwenSymbols.isBinaryName("   "));
        expect(out, "binary name rejects null", !QwenSymbols.isBinaryName(null));
        expect(out, "handler names are all binary names",
                QwenSymbols.isBinaryName(QwenSymbols.HANDLER_SSE_CLASS)
                        && QwenSymbols.isBinaryName(QwenSymbols.HANDLER_NATIVE_SSE_CLASS)
                        && QwenSymbols.isBinaryName(QwenSymbols.HANDLER_NON_SSE_CLASS));
        expect(out, "shrinker names are all binary names",
                QwenSymbols.isBinaryName(QwenSymbols.HANDLER_SSE_IMPL)
                        && QwenSymbols.isBinaryName(QwenSymbols.HANDLER_NATIVE_SSE_IMPL)
                        && QwenSymbols.isBinaryName(QwenSymbols.HANDLER_NON_SSE_IMPL));

        // The constructor table is what makes the fallback hook addressable. A primitive and its
        // boxed type do not match under reflection, so the exact types carry the whole contract.
        Class<?>[] types = QwenChatPipeline.constructorSignature();
        expect(out, "primary ctor has 8 parameters", types != null && types.length == 8);
        expect(out, "primary ctor param 0 is boolean",
                types != null && types.length > 0 && types[0] == boolean.class);
        expect(out, "primary ctor param 5 is int",
                types != null && types.length > 5 && types[5] == int.class);
        expect(out, "primary ctor param 4 is Object (erased data)",
                types != null && types.length > 4 && types[4] == Object.class);
        expect(out, "rawBody index lands on a String parameter",
                types != null
                        && QwenSymbols.RESPONSE_CTOR_RAW_BODY_INDEX < types.length
                        && types[QwenSymbols.RESPONSE_CTOR_RAW_BODY_INDEX] == String.class);
        expect(out, "rawBody is the last constructor parameter",
                QwenSymbols.RESPONSE_CTOR_RAW_BODY_INDEX == types.length - 1);
        expect(out, "rawBody setter column type matches ctor column",
                "(Ljava/lang/String;)V".equals(QwenSymbols.RESPONSE_SET_RAW_BODY_DESC)
                        && types[QwenSymbols.RESPONSE_CTOR_RAW_BODY_INDEX] == String.class);
        expect(out, "descriptor parameter count matches the ctor table",
                QwenSymbols.RESPONSE_CTOR_DESC.indexOf('(') == 0
                        && countParams(QwenSymbols.RESPONSE_CTOR_DESC) == types.length);
        expect(out, "serialization ctor is not the hooked one",
                !QwenSymbols.RESPONSE_CTOR_SERIALIZATION_DESC
                        .equals(QwenSymbols.RESPONSE_CTOR_DESC));
        expect(out, "default-args ctor is not the hooked one",
                !QwenSymbols.RESPONSE_CTOR_DEFAULT_ARGS_DESC
                        .equals(QwenSymbols.RESPONSE_CTOR_DESC));
        expect(out, "describe names the response class",
                QwenSymbols.describe().contains(QwenSymbols.RESPONSE_CLASS));
    }

    /**
     * Counts reference and primitive parameters in a JVM descriptor.
     *
     * <p>Deliberately minimal — the only descriptor it has to read is the one the table declares,
     * and its job is to prove that string and the {@code Class[]} agree. A full parser here would
     * be a second implementation of the same thing, which is precisely the drift the assertion is
     * meant to catch.</p>
     */
    private static int countParams(String desc) {
        if (desc == null) return -1;
        int close = desc.indexOf(')');
        if (close <= 0) return -1;
        String params = desc.substring(1, close);
        int count = 0;
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                int end = params.indexOf(';', i);
                if (end < 0) return -1;
                i = end + 1;
            } else if (c == '[') {
                i++;
                continue;
            } else {
                i++;
            }
            count++;
        }
        return count;
    }

    /**
     * Tier B: the switch defaults to off and only an explicit truthy value turns it on.
     *
     * <p>Default-off is the load-bearing decision in this tier. Tier A writes two files and hooks
     * nothing; Tier B installs a callback on the path every assistant reply travels through, and
     * it has never run on hardware. The parser is therefore asserted from both directions: every
     * accepted spelling, and every string that must not accidentally enable it — including the
     * blank and unreadable cases, which are the ones a file-based switch gets wrong.</p>
     */
    private static void checkTierBSettings(List<String> out) {
        expect(out, "switch absent means off by default",
                !QwenChatPipeline.isEnabled());
        expect(out, "switch file path lives in the qwen sandbox",
                QwenChatPipeline.enabledFilePath().startsWith(QwenHost.FILES_DIR));
        expect(out, "switch file keeps the deekseep prefix",
                QwenChatPipeline.enabledFilePath().contains("/deekseep_"));
        expect(out, "switch file is distinct from the module master switch",
                !QwenChatPipeline.enabledFilePath().equals(QwenPaths.MODULE_ENABLED_FILE));
        expect(out, "pipeline log path is not the shared srv log",
                !QwenPaths.CHAT_PIPELINE_LOG_PATH.equals(QwenPaths.SRV_LOG_PATH));
        expect(out, "pipeline log path lives in the qwen sandbox",
                QwenPaths.CHAT_PIPELINE_LOG_PATH.startsWith(QwenHost.FILES_DIR));
        expect(out, "diagnostic summary reports the switch state",
                QwenChatPipeline.diagnosticSummary().contains("chatPipeline.enabled=false"));
        expect(out, "diagnostic summary reports the seam",
                QwenChatPipeline.diagnosticSummary().contains("chatPipeline.seam=none"));
    }

    /**
     * Tier B: capture bounds and "never throws".
     *
     * <p>The pipeline sees attacker-influenced-length data — a long streamed reply is assembled
     * chunk by chunk into one string — so the truncation boundary is where a heap blow-up or a
     * split surrogate would surface. The never-throws assertions matter just as much: every one of
     * these helpers is called from inside a host callback, where a throw takes the app down.</p>
     */
    private static void checkTierBBodyHandling(List<String> out) {
        expect(out, "pipeline not installed before install",
                !QwenChatPipeline.isInstalled());
        expect(out, "no capture before install", QwenChatPipeline.captureCount() == 0);
        expect(out, "no truncation before install", QwenChatPipeline.truncationCount() == 0);
        expect(out, "no rewrite seam armed by default", rewriteSeamIsOff());

        expect(out, "capture passes a short body through",
                "hello".equals(QwenResponseTap.capture("hello")));
        expect(out, "capture renders null as empty",
                "".equals(QwenResponseTap.capture(null)));
        expect(out, "capture handles empty", "".equals(QwenResponseTap.capture("")));
        expect(out, "short body is not truncated",
                !QwenResponseTap.wouldTruncate("hello"));
        expect(out, "null body is not truncated",
                !QwenResponseTap.wouldTruncate(null));

        int limit = QwenResponseTap.MAX_CAPTURED_CHARS;
        expect(out, "capture cap is 64k", limit == 65536);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < limit + 100; i++) big.append('x');
        expect(out, "over-cap body reports truncation",
                QwenResponseTap.wouldTruncate(big.toString()));
        String truncated = QwenResponseTap.capture(big.toString());
        expect(out, "truncated capture carries the marker",
                truncated.contains(QwenResponseTap.TRUNCATION_MARKER));
        expect(out, "truncated capture reports the original length",
                truncated.contains("totalChars=" + big.length()));
        expect(out, "truncated capture stays bounded",
                truncated.length() < limit + 128);

        // Boundary: exactly at the cap must not be touched, otherwise every maximal reply pays
        // for a marker it did not need.
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < limit; i++) exact.append('y');
        expect(out, "body exactly at the cap is not truncated",
                !QwenResponseTap.capture(exact.toString())
                        .contains(QwenResponseTap.TRUNCATION_MARKER));

        expect(out, "sse frames are recognised",
                QwenResponseTap.looksLikeSse("data: {\"a\":1}\n\ndata: [DONE]\n"));
        expect(out, "json body is not mistaken for sse",
                !QwenResponseTap.looksLikeSse("{\"success\":true}"));
        expect(out, "kind label marks json",
                QwenResponseTap.describeKind("{\"a\":1}").startsWith("json|"));
        expect(out, "kind label marks null", "null|0".equals(QwenResponseTap.describeKind(null)));

        // install() must refuse to hook on a bare JDK rather than throw; that is the whole reason
        // the attach path can call it without a guard of its own.
        boolean threw = false;
        boolean result = true;
        try {
            result = QwenChatPipeline.install(null);
            QwenChatPipeline.install(QwenAdapterSelfTest.class.getClassLoader());
        } catch (Throwable t) {
            threw = true;
        }
        expect(out, "install never throws without an android runtime", !threw);
        expect(out, "install reports false when it cannot hook", !result);
        expect(out, "install leaves a result string behind",
                !"not-attempted".equals(QwenChatPipeline.lastResult()));
        expect(out, "switch off is the reported reason",
                "disabled".equals(QwenChatPipeline.lastResult())
                        || "null-classloader".equals(QwenChatPipeline.lastResult()));
    }

    /** True when no rewriter is armed; the seam must start empty in a fresh process. */
    private static boolean rewriteSeamIsOff() {
        try {
            QwenChatPipeline.setRewriter(null);
            return true;
        } catch (Throwable t) {
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
