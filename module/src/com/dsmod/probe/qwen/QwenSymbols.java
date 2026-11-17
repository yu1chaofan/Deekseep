package com.dsmod.probe.qwen;

/**
 * Every host symbol the Tier B chat pipeline is allowed to touch, in one auditable table.
 *
 * <p>All names below were read out of the shipped {@code Qwen_Studio 2.0.0} (versionCode
 * 2000000) dex, not guessed. They are kept as plain strings with no {@code Class} references so
 * this file compiles on a bare JDK and so a symbol mismatch always degrades to "hook not
 * installed" instead of a {@code NoClassDefFoundError} thrown from a static initialiser.</p>
 *
 * <p><strong>What actually shipped is partly obfuscated.</strong> The network data layer keeps its
 * names ({@code QwenResponse}, {@code QwenResponseKt}) because it carries
 * {@code androidx.annotation.Keep} and kotlinx-serialization metadata, but the message handlers do
 * <em>not</em>: {@code SseMessageHandler}, {@code NativeSseMessageHandler} and
 * {@code NonSseMessageHandler} survive only as {@code $inlined$} synthetic lambda class names,
 * and their real bodies are {@code msg/e}, {@code msg/f}, {@code msg/a}. Recording both the
 * annotated name and the shrinker name matters because whoever writes the Tier C symbol
 * comparison run will otherwise search for a class that is not in the dex at all.</p>
 *
 * <p>Signature strings use the same descriptor text smali prints, because the runtime probe
 * compares them against {@link Class#getDeclaredMethods()} by walking parameter types, not by
 * string equality against reflection output. That keeps one spelling in the table and one place —
 * {@link QwenChatPipeline} — where a descriptor becomes a {@code Class[]}.</p>
 */
public final class QwenSymbols {
    /** Unified response type produced by all three API call channels. */
    public static final String RESPONSE_CLASS =
            "ai.qwenlm.chat.android.clean.core.network.data.QwenResponse";

    /** Mutator for the only non-final data field; the streaming backfill point. */
    public static final String RESPONSE_SET_RAW_BODY = "setRawBody";

    /** {@code setRawBody} descriptor. */
    public static final String RESPONSE_SET_RAW_BODY_DESC = "(Ljava/lang/String;)V";

    /** Reader for the same field; used only for diagnostics after a write is observed. */
    public static final String RESPONSE_GET_RAW_BODY = "getRawBody";

    /** {@code getRawBody} descriptor. */
    public static final String RESPONSE_GET_RAW_BODY_DESC = "()Ljava/lang/String;";

    /**
     * Primary 8-argument constructor.
     *
     * <p>The last parameter is {@code rawBody}. Hooking the constructor is the fallback for a host
     * build that inlines {@code setRawBody} away: an immutable-property Kotlin class compiled
     * without the synthetic default-args body only ever receives the body here.</p>
     */
    public static final String RESPONSE_CTOR_DESC =
            "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;"
            + "ILjava/util/Map;Ljava/lang/String;)V";

    /** Index of {@code rawBody} in {@link #RESPONSE_CTOR_DESC}. Verified against the dex. */
    public static final int RESPONSE_CTOR_RAW_BODY_INDEX = 7;

    /**
     * Kotlin default-args constructor.
     *
     * <p>Never hooked: it delegates to the primary constructor in the same frame, so hooking both
     * would double-report every response. Listed so the probe can log which shape the host uses.
     * </p>
     */
    public static final String RESPONSE_CTOR_DEFAULT_ARGS_DESC =
            "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;"
            + "ILjava/util/Map;Ljava/lang/String;ILobf/VL;)V";

    /**
     * Serialization constructor.
     *
     * <p>Also never hooked. It writes the fields directly and never calls the primary constructor,
     * so a resumed chat session restored from local storage would otherwise be reported as a
     * fresh server response.</p>
     */
    public static final String RESPONSE_CTOR_SERIALIZATION_DESC =
            "(IZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Lobf/aJ0;)V";

    /** Streaming channel; carries {@code resumeSseWithGET}. */
    public static final String HANDLER_SSE_CLASS =
            "ai.qwenlm.chat.android.clean.feature.chat.data.msg.SseMessageHandler";

    /** Native simulated reply channel. */
    public static final String HANDLER_NATIVE_SSE_CLASS =
            "ai.qwenlm.chat.android.clean.feature.chat.data.msg.NativeSseMessageHandler";

    /** Non-streaming channel. */
    public static final String HANDLER_NON_SSE_CLASS =
            "ai.qwenlm.chat.android.clean.feature.chat.data.msg.NonSseMessageHandler";

    /**
     * Shrinker name of the streaming handler body ({@code SseMessageHandler}).
     *
     * <p>Only the {@code $inlined$} lambda shells of the annotated names survive in the dex; the
     * class that actually performs the SSE read is {@code msg.e}. Diagnostic only — the pipeline
     * never hooks here, it only reports which of these resolved so a support log can tell a
     * re-obfuscated build from an unchanged one.</p>
     */
    public static final String HANDLER_SSE_IMPL = "ai.qwenlm.chat.android.clean.feature.chat.data.msg.e";

    /** Shrinker name of the native simulated handler body. */
    public static final String HANDLER_NATIVE_SSE_IMPL =
            "ai.qwenlm.chat.android.clean.feature.chat.data.msg.f";

    /** Shrinker name of the non-streaming handler body. */
    public static final String HANDLER_NON_SSE_IMPL =
            "ai.qwenlm.chat.android.clean.feature.chat.data.msg.a";

    /**
     * API call facade.
     *
     * <p>Not read in 2.0.0: the dex ships {@code QwenResponseKt$qwenApiCall$4},
     * {@code QwenResponseKt$qwenApiCallForResult$2} and
     * {@code QwenResponseKt$qwenApiCallForRawBody$2} but no {@code QwenResponseKt} outer class,
     * because every facade member is {@code inline} and the file-facade class was removed by the
     * shrinker. The constant stays so a future build that keeps the facade is recognised without
     * editing this class.</p>
     */
    public static final String API_CALL_CLASS =
            "ai.qwenlm.chat.android.clean.core.network.data.QwenResponseKt";

    /** Response wrapper for the streaming raw-body channel; diagnostic only. */
    public static final String API_CALL_RAW_BODY_LAMBDA =
            "ai.qwenlm.chat.android.clean.core.network.data.QwenResponseKt$qwenApiCallForRawBody$2";

    /** Response wrapper for the suspend-result channel; diagnostic only. */
    public static final String API_CALL_RESULT_LAMBDA =
            "ai.qwenlm.chat.android.clean.core.network.data.QwenResponseKt$qwenApiCallForResult$2";

    /**
     * Network DI module.
     *
     * <p>Same shrinker situation: only {@code NetworkModule$provideApiClientFactory$1$1} and
     * {@code NetworkModule$provideUnauthorizedCallback$1$onUnauthorized$2} remain, so the
     * {@code "Bearer " + token} insertion cannot be intercepted on a live build by class name.
     * Listed for diagnostics and for the Tier C hook that would target the surviving lambda.</p>
     */
    public static final String NETWORK_MODULE_CLASS =
            "ai.qwenlm.chat.android.clean.core.network.di.NetworkModule";

    /** Surviving client-factory lambda; the only reachable bearer-injection site. */
    public static final String NETWORK_MODULE_CLIENT_LAMBDA =
            "ai.qwenlm.chat.android.clean.core.network.di.NetworkModule$provideApiClientFactory$1$1";

    /** Marker used by {@link #isTypeDescriptor(String)}; spelled out to keep the check readable. */
    private static final char DESCRIPTOR_HEAD = 'L';

    private QwenSymbols() {}

    /**
     * True when {@code name} is a smali class descriptor: starts with {@code L}, ends with
     * {@code ;}, and holds at least one package separator.
     *
     * <p>The check exists because a typo'd constant is the single most likely way to break this
     * pipeline: reflection would then fail with {@code ClassNotFoundException} at hook time, on a
     * device, with no stack trace to work from. Rejecting the constant at self-test time is
     * cheaper.</p>
     */
    public static boolean isTypeDescriptor(String name) {
        if (name == null) return false;
        String value = name.trim();
        if (value.length() < 3) return false;
        if (value.charAt(0) != DESCRIPTOR_HEAD) return false;
        if (value.charAt(value.length() - 1) != ';') return false;
        return value.indexOf('/') > 0;
    }

    /**
     * True when {@code name} is a Java binary name such as {@code a.b.C}.
     *
     * <p>This is the form {@link Class#forName(String, boolean, ClassLoader)} needs. Both forms
     * are kept because the probe reads Java names from {@code Class#getName()} and descriptors
     * from smali, and comparing one to the other is how the {@code QwenResponseKt} miss went
     * unnoticed on the first pass.</p>
     */
    public static boolean isBinaryName(String name) {
        if (name == null) return false;
        String value = name.trim();
        if (value.length() == 0) return false;
        if (value.indexOf('.') <= 0) return false;
        if (value.indexOf('/') >= 0 || value.indexOf(';') >= 0
                || value.indexOf('[') >= 0) return false;
        return value.charAt(0) != '.';
    }

    /**
     * True when {@code desc} looks like a JVM method descriptor.
     *
     * <p>Bounded shape check, not a parser: the pipeline only needs to catch a constant that lost
     * its parentheses while someone was editing the table. Return and parameter types are not
     * validated beyond the balanced-parenthesis rule.</p>
     */
    public static boolean isMethodDescriptor(String desc) {
        if (desc == null) return false;
        String value = desc.trim();
        if (value.length() < 3) return false;
        if (value.charAt(0) != '(') return false;
        int close = value.indexOf(')');
        if (close <= 0 || close == value.length() - 1) return false;
        return 'V' == value.charAt(value.length() - 1)
                || ';' == value.charAt(value.length() - 1)
                || '[' == value.charAt(value.length() - 1)
                || value.charAt(value.length() - 1) == 'Z'
                || value.charAt(value.length() - 1) == 'I'
                || value.charAt(value.length() - 1) == 'J'
                || value.charAt(value.length() - 1) == 'F'
                || value.charAt(value.length() - 1) == 'D'
                || value.charAt(value.length() - 1) == 'B'
                || value.charAt(value.length() - 1) == 'C'
                || value.charAt(value.length() - 1) == 'S';
    }

    /** Descriptor form of {@link #RESPONSE_CLASS}, built on demand so callers cannot drift. */
    public static String responseClassDescriptor() {
        return "L" + RESPONSE_CLASS.replace('.', '/') + ";";
    }

    /** Multi-line symbol table dump for the support log and the self-test. */
    public static String describe() {
        return "response=" + RESPONSE_CLASS
                + "\nresponseMutator=" + RESPONSE_SET_RAW_BODY + RESPONSE_SET_RAW_BODY_DESC
                + "\nresponseCtor=" + RESPONSE_CTOR_DESC
                + "\nresponseCtorRawBodyIndex=" + RESPONSE_CTOR_RAW_BODY_INDEX
                + "\nhandlers=" + HANDLER_SSE_CLASS + "," + HANDLER_NATIVE_SSE_CLASS
                + "," + HANDLER_NON_SSE_CLASS
                + "\nhandlerImpls=" + HANDLER_SSE_IMPL + "," + HANDLER_NATIVE_SSE_IMPL
                + "," + HANDLER_NON_SSE_IMPL
                + "\napiCall=" + API_CALL_CLASS
                + "\nnetworkModule=" + NETWORK_MODULE_CLASS;
    }
}
