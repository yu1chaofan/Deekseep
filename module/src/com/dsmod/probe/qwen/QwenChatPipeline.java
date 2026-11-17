package com.dsmod.probe.qwen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Tier B dialogue pipeline for Qwen Studio: one observation hook on the unified response object.
 *
 * <p><strong>Why {@code QwenResponse} and not the UI.</strong> Three reasons, in order of weight:</p>
 * <ol>
 *   <li>{@code rawBody} is the only mutable field on the type ({@code success}/{@code code}/
 *       {@code message}/{@code requestId}/{@code data} are {@code final}). A streaming reply is
 *       backfilled into that field on the object the caller keeps; a non-streaming reply is passed
 *       once through the constructor. Either way there is exactly one property to watch, and
 *       watching it cannot miss a channel — SSE, native simulation and non-SSE all build the same
 *       {@code QwenResponse}.</li>
 *   <li>The alternative — the message handlers and the API call facade — does not exist under those
 *       names on the shipped build. {@code SseMessageHandler}, {@code NativeSseMessageHandler},
 *       {@code NonSseMessageHandler}, {@code QwenResponseKt} and {@code NetworkModule} survive
 *       only as {@code $inlined$} lambda shells or as shrinker names {@code msg/e}, {@code msg/f},
 *       {@code msg/a}; see {@link QwenSymbols}. Any hook written against the pretty names would
 *       install nothing at all.</li>
 *   <li>The Compose UI has no stable node identity across re-obfuscation, so an observe-only hook
 *       there would trade a guaranteed-stable data seam for a per-build symbol hunt.</li>
 * </ol>
 *
 * <p><strong>Observe only.</strong> The hook never writes the argument back. A body rewrite would
 * either be a no-op (the caller keeps its own reference in the streaming path) or corrupt the chat
 * reply the user is reading, and Tier B has no on-device verification behind it yet. The filter
 * seam exists as {@link #setRewriter(Rewriter)} with no default implementation precisely so that
 * "no rewrite" is the shipped behaviour rather than a configuration a stale file can flip.</p>
 *
 * <p><strong>Never throw.</strong> An exception inside an Xposed callback unwinds into host code
 * that has no handler for it; every callback body here is wrapped, including the log call.</p>
 */
public final class QwenChatPipeline {
    /** Name of the switch file, without the sandbox prefix. Shared with the self-test. */
    static final String ENABLED_FILE_NAME = "deekseep_qwen_chat_pipeline";

    /**
     * Which member the pipeline attached to, for the support log.
     *
     * <p>Distinct values matter: "mutator" is the healthy case, "constructor" means the host build
     * inlined the setter away, and "none" means {@code QwenResponse} was found but neither seam
     * resolved, which is a re-obfuscation signal rather than a normal state.</p>
     */
    private static final String SEAM_MUTATOR = "mutator";
    private static final String SEAM_CONSTRUCTOR = "constructor";

    private static volatile boolean attempted;
    private static volatile boolean installed;
    private static volatile String seam = "none";
    private static volatile String lastResult = "not-attempted";
    private static volatile String probeSummary = "not-probed";
    private static volatile long captures;
    private static volatile long truncations;

    private static volatile Rewriter rewriter;

    private QwenChatPipeline() {}

    /**
     * Optional body filter seam.
     *
     * <p>Kept because the DeepSeek branch exposes the same facility and a user moving between hosts
     * will look for it. A rewriter runs after capture, on an already-truncated copy, and its return
     * value is logged instead of the capture; the object handed to the host is never touched, so a
     * throwing or slow rewriter cannot damage the reply. The default is {@code null} — observation
     * only.</p>
     */
    public interface Rewriter {
        String rewrite(String rawBody);
    }

    /**
     * Installs the response tap once per process.
     *
     * <p>Returns {@code false} for every non-fatal reason: switch off, host class missing, neither
     * seam resolvable, or an exception anywhere in between. The caller —
     * {@code QwenAdapterEntry.attach} — treats this as a degraded capability and keeps Tier A's
     * success status, because losing an observation hook must never look like losing the adapter.
     * </p>
     *
     * @param loader host class loader from the Xposed callback; may be null
     */
    public static boolean install(ClassLoader loader) {
        if (attempted) return installed;
        attempted = true;
        try {
            return installOnce(loader);
        } catch (Throwable t) {
            installed = false;
            lastResult = "install-failed:" + QwenCrashSink.describe(t);
            QwenLog.safe("chat pipeline install failed: " + QwenCrashSink.describe(t));
            return false;
        }
    }

    /** True when at least one response seam is hooked in this process. */
    public static boolean isInstalled() {
        return installed;
    }

    /** {@code mutator}, {@code constructor} or {@code none}. */
    public static String seam() {
        return seam;
    }

    /** One-line outcome of the last {@link #install(ClassLoader)}. */
    public static String lastResult() {
        return lastResult;
    }

    /** Number of bodies captured since process start. */
    public static long captureCount() {
        return captures;
    }

    /** Number of captures that hit {@link QwenResponseTap#MAX_CAPTURED_CHARS}. */
    public static long truncationCount() {
        return truncations;
    }

    /**
     * Whether the pipeline is allowed to run on this device.
     *
     * <p>Default is <strong>off</strong>, the opposite of {@link QwenAdapterEntry#isModuleEnabled}.
     * Tier A is a probe that writes two files and hooks nothing; Tier B installs a hook into a
     * network response path that has never been exercised on hardware. A user who installs a new
     * module build expecting a bug-fix must not silently receive an unverified hook on their chat
     * traffic, so the safe default has to be "absent means off" and the user opts in knowingly.
     * </p>
     *
     * <p>Accepted on-values are {@code 1/on/true/enabled/yes}; everything else, including an
     * unreadable file, is off. Unreadable therefore fails closed rather than open — the only place
     * in this adapter where that is the correct choice.</p>
     */
    public static boolean isEnabled() {
        try {
            String value = QwenPaths.readFile(enabledFilePath());
            if (value == null) return false;
            String normalized = value.trim().toLowerCase(java.util.Locale.US);
            return "1".equals(normalized) || "on".equals(normalized)
                    || "true".equals(normalized) || "enabled".equals(normalized)
                    || "yes".equals(normalized);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Absolute path of the switch file; also the path this class documents to the user. */
    public static String enabledFilePath() {
        return QwenPaths.CHAT_PIPELINE_ENABLED_FILE;
    }

    /**
     * Writes the switch file with an explanatory body when it is absent.
     *
     * <p>Deliberately writes {@code 0}, not {@code 1}: the file exists so a user can find the
     * entry point, and its content is what turns the hook on. Called once from the attach path, so
     * a user who has already edited the file keeps their value — existence is the guard, not a
     * version marker, because a user-set {@code 1} must survive a module update.</p>
     */
    static boolean ensureSwitchFile() {
        try {
            String path = enabledFilePath();
            if (path == null) return false;
            if (QwenPaths.readFile(path) != null) return true;
            String body = "# deekseep Qwen chat pipeline (Tier B)\n"
                    + "# off by default; set 1 and restart Qwen Studio to observe responses\n"
                    + "# observe-only: the body is logged, never rewritten\n"
                    + "0\n";
            return QwenPaths.writeFile(path, body);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Installs a body filter, or removes it when {@code filter} is null. */
    public static void setRewriter(Rewriter filter) {
        rewriter = filter;
    }

    /**
     * Symbol table dump plus counters, for the support log.
     *
     * <p>Never triggers a probe: the fields are already populated by {@link #install}, and asking
     * a UI thread to reflect into the host loader would answer a different question.</p>
     */
    public static String diagnosticSummary() {
        return "chatPipeline.enabled=" + isEnabled()
                + "\nchatPipeline.installed=" + installed
                + "\nchatPipeline.seam=" + seam
                + "\nchatPipeline.lastResult=" + lastResult
                + "\nchatPipeline.captures=" + captures
                + "\nchatPipeline.truncations=" + truncations
                + "\nchatPipeline.captureCap=" + QwenResponseTap.MAX_CAPTURED_CHARS
                + "\nchatPipeline.switchFile=" + enabledFilePath()
                + "\nchatPipeline.probe=" + probeSummary;
    }

    // ---------------------------------------------------------------- install

    private static boolean installOnce(ClassLoader loader) {
        if (loader == null) {
            lastResult = "null-classloader";
            return false;
        }
        probeSummary = probeSymbols(loader);
        if (!isEnabled()) {
            lastResult = "disabled";
            QwenLog.safe("chat pipeline idle (switch off): " + enabledFilePath());
            return false;
        }
        Class<?> responseType = resolveResponseType(loader);
        if (responseType == null) {
            lastResult = "response-class-missing";
            QwenLog.safe("chat pipeline idle: " + QwenSymbols.RESPONSE_CLASS
                    + " did not resolve on this host build");
            return false;
        }
        if (hookMutator(responseType)) {
            seam = SEAM_MUTATOR;
        } else if (hookConstructor(responseType)) {
            seam = SEAM_CONSTRUCTOR;
        } else {
            seam = "none";
        }
        installed = !"none".equals(seam);
        lastResult = installed ? "installed-" + seam : "no-seam";
        QwenLog.safe("chat pipeline " + lastResult + "; " + diagnosticSummary());
        return installed;
    }

    /**
     * Resolves the response type without running its static initialiser.
     *
     * <p>{@code Class.forName(name, false, loader)} matters here for a second reason beyond the
     * one {@code QwenCompat} documents: {@code QwenResponse}'s {@code <clinit>} builds the
     * kotlinx-serialization descriptor, and forcing that at attach time would run host code from
     * inside our own bootstrap before the host has finished starting up.</p>
     */
    private static Class<?> resolveResponseType(ClassLoader loader) {
        try {
            return Class.forName(QwenSymbols.RESPONSE_CLASS, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Hooks {@code setRawBody(String)} — the precise seam.
     *
     * <p>The method is matched by name and a one-argument {@code String} signature rather than by
     * a hard-coded {@code Method} lookup, so a Kotlin build that renames the property but keeps the
     * accessor shape ({@code setXxx(String)}) is still found. Only the public, single-argument,
     * non-static form is accepted: a static or multi-argument overload with the same name would be
     * a different member and hooking it would observe nothing.</p>
     */
    private static boolean hookMutator(Class<?> responseType) {
        try {
            Method target = null;
            for (Method method : responseType.getDeclaredMethods()) {
                if (!QwenSymbols.RESPONSE_SET_RAW_BODY.equals(method.getName())) continue;
                if (method.getParameterTypes().length != 1) continue;
                if (!String.class.equals(method.getParameterTypes()[0])) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                target = method;
                break;
            }
            if (target == null) return false;
            XposedBridge.hookMethod(target, new ResponseHook(-1));
            return true;
        } catch (Throwable t) {
            QwenLog.safe("chat pipeline: mutator hook failed: " + QwenCrashSink.describe(t));
            return false;
        }
    }

    /**
     * Hooks the primary 8-argument constructor as the fallback seam.
     *
     * <p>Matched by exact parameter list taken from {@link QwenSymbols#RESPONSE_CTOR_DESC}, not by
     * "any constructor whose last parameter is a String": the Kotlin default-args constructor ends
     * in {@code Lobf/VL;} and the serialization constructor ends in {@code Lobf/aJ0;}, so a loose
     * match would either miss the real one or double-report every response through the delegating
     * wrapper. The primary constructor is the only one that takes exactly these eight types.</p>
     *
     * <p>Read after the hook rather than before: {@code rawBody} is assigned inside the body, and
     * a before-hook would only see the caller's argument, which for the default-args path is the
     * placeholder that gets replaced one frame later.</p>
     */
    private static boolean hookConstructor(Class<?> responseType) {
        try {
            Class<?>[] signature = constructorSignature();
            Constructor<?> target = null;
            for (Constructor<?> ctor : responseType.getDeclaredConstructors()) {
                if (!matches(ctor.getParameterTypes(), signature)) continue;
                target = ctor;
                break;
            }
            if (target == null) return false;
            XposedBridge.hookMethod(target,
                    new ResponseHook(QwenSymbols.RESPONSE_CTOR_RAW_BODY_INDEX));
            return true;
        } catch (Throwable t) {
            QwenLog.safe("chat pipeline: constructor hook failed: " + QwenCrashSink.describe(t));
            return false;
        }
    }

    /**
     * Builds the primary-constructor parameter list.
     *
     * <p>Written out longhand instead of parsed from {@link QwenSymbols#RESPONSE_CTOR_DESC}. The
     * descriptor string is the audit record; this array is what actually has to be right, and a
     * typo in a hand-written parser would silently produce a wrong signature that matches nothing.
     * The self-test asserts the two agree in length and that the raw-body slot is the last one.</p>
     */
    static Class<?>[] constructorSignature() {
        return new Class<?>[] {
            boolean.class,   // success
            String.class,    // code
            String.class,    // message
            String.class,    // requestId
            Object.class,    // data (generic T, erased)
            int.class,       // httpCode
            java.util.Map.class, // headers
            String.class     // rawBody
        };
    }

    private static boolean matches(Class<?>[] actual, Class<?>[] expected) {
        if (actual == null || expected == null) return false;
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (!expected[i].equals(actual[i])) return false;
        }
        return true;
    }

    /**
     * Records which of the documented symbols this build actually still has.
     *
     * <p>Cheap and purely additive: the result goes into the support log so a bug report from a
     * re-obfuscated build can be triaged without re-pulling the APK. Resolution failures are
     * expected for the handler and DI names (see {@link QwenSymbols}) and are reported as plain
     * absence, not as errors.</p>
     */
    private static String probeSymbols(ClassLoader loader) {
        StringBuilder out = new StringBuilder();
        out.append("response=").append(resolves(loader, QwenSymbols.RESPONSE_CLASS) ? "yes" : "no");
        out.append(" sseHandler=").append(resolves(loader, QwenSymbols.HANDLER_SSE_CLASS) ? "yes" : "no");
        out.append(" sseImpl=").append(resolves(loader, QwenSymbols.HANDLER_SSE_IMPL) ? "yes" : "no");
        out.append(" apiFacade=").append(resolves(loader, QwenSymbols.API_CALL_CLASS) ? "yes" : "no");
        out.append(" networkModule=").append(resolves(loader, QwenSymbols.NETWORK_MODULE_CLASS) ? "yes" : "no");
        return out.toString();
    }

    private static boolean resolves(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------- callback

    /**
     * Shared callback for both seams.
     *
     * <p>{@code argIndex} is the position of {@code rawBody} in {@code MethodHookParam.args}, or
     * {@code -1} when the capture should come from the mutator's single argument. One class for
     * both keeps the truncation and rewrite rules in a single place; two copies would drift.</p>
     */
    private static final class ResponseHook extends XC_MethodHook {
        private final int argIndex;

        ResponseHook(int argIndex) {
            this.argIndex = argIndex;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                record(param);
            } catch (Throwable ignored) {
                // An exception here would propagate into the host's network path.
            }
        }

        private void record(MethodHookParam param) {
            Object[] args = param == null ? null : param.args;
            if (args == null) return;
            int index = argIndex < 0 ? 0 : argIndex;
            if (index >= args.length) return;
            Object value = args[index];
            String body = value instanceof String ? (String) value : null;
            if (body == null) return;

            captures++;
            if (QwenResponseTap.wouldTruncate(body)) truncations++;

            String logged = QwenResponseTap.capture(body);
            String rewritten = applyRewriter(body);
            if (rewritten != null) logged = QwenResponseTap.capture(rewritten);

            StringBuilder line = new StringBuilder(64 + logged.length());
            line.append("[chat] seam=").append(seam)
                    .append(" kind=").append(QwenResponseTap.describeKind(body))
                    .append(" capture=").append(captures)
                    .append(rewritten != null ? " rewritten" : "")
                    .append('\n').append(logged).append('\n');
            QwenPaths.appendLine(QwenPaths.CHAT_PIPELINE_LOG_PATH, line.toString());
        }

        /**
         * Runs the optional filter, defending against a hostile implementation.
         *
         * <p>Returns {@code null} both when no filter is installed and when the filter throws —
         * the caller only needs "did a rewrite happen", and collapsing the two keeps the hot path
         * branch-free. A filter that returns null or an unchanged string is treated as no rewrite.
         * </p>
         */
        private String applyRewriter(String body) {
            Rewriter current = rewriter;
            if (current == null) return null;
            try {
                String result = current.rewrite(body);
                if (result == null || result.equals(body)) return null;
                return result;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
