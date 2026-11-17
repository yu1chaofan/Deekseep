package com.dsmod.probe.qwen;

/**
 * User-facing switch for the Tier B dialogue pipeline.
 *
 * <p>The host exposes no settings surface we can inject into yet — that is the Tier B UI work
 * item, still open — so the switch is a file inside the host sandbox. This mirrors how the
 * DeepSeek branch has always handled early-stage flags ({@code files/deekseep_srvlog},
 * {@code files/dq0_*}), which means users already know the pattern.</p>
 *
 * <p><strong>Default is off.</strong> The pipeline observes every assistant reply; that is fine
 * to ship, but it has never run on a real device, and a hook that misbehaves inside the host's
 * call stack is the one class of bug that can take the app down. Off by default means an
 * unvalidated hook cannot affect anyone who did not ask for it.</p>
 */
public final class QwenChatPipelineSettings {
    /** Presence of this file with a truthy value turns the pipeline on. */
    public static final String SWITCH_FILE = QwenPaths.FILES_DIR + "/deekseep_qwen_chat_pipeline";

    /** Extra note written next to the switch on first attach, so the path is discoverable. */
    public static final String HINT_FILE = QwenPaths.FILES_DIR + "/deekseep_qwen_chat_pipeline.readme";

    private QwenChatPipelineSettings() {}

    /**
     * True when the user enabled the pipeline.
     *
     * <p>An absent file means off, not "reset to default": the default is off and only an explicit
     * write turns it on. Truthy values match the DeepSeek branch's convention so a user moving
     * between hosts does not have to learn a second vocabulary.</p>
     */
    public static boolean isEnabled() {
        String raw = QwenPaths.readFile(SWITCH_FILE);
        return isTruthy(raw);
    }

    /** Parses a raw switch value; package-private so the self-test can drive every branch. */
    static boolean isTruthy(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(java.util.Locale.US);
        if (value.length() == 0) return false;
        return "1".equals(value) || "true".equals(value) || "on".equals(value)
                || "yes".equals(value) || "enable".equals(value) || "enabled".equals(value);
    }

    /**
     * Writes the switch file once, plus a hint, when the adapter attaches.
     *
     * <p>Only creates what is missing. Rewriting an existing switch would let a module update
     * silently flip a user's choice, which is exactly the kind of thing that makes people stop
     * trusting a module.</p>
     */
    static void seedIfMissing() {
        try {
            if (QwenPaths.readFile(SWITCH_FILE) == null) {
                QwenPaths.writeFile(SWITCH_FILE,
                        "0\n"
                        + "# Set to 1 to enable the Qwen chat pipeline (Tier B).\n"
                        + "# Default 0: the pipeline observes assistant replies only.\n");
            }
            if (QwenPaths.readFile(HINT_FILE) == null) {
                QwenPaths.writeFile(HINT_FILE,
                        "Qwen Studio chat pipeline (Tier B)\n"
                        + "\n"
                        + "switch : " + SWITCH_FILE + "\n"
                        + "  value 1 / true / on  -> enabled\n"
                        + "  value 0 or absent    -> disabled (default)\n"
                        + "\n"
                        + "What it does when enabled:\n"
                        + "  - observes every assistant response body as it leaves the network layer\n"
                        + "  - writes a truncated copy to the module log\n"
                        + "  - does NOT modify, delay, or reorder any reply\n"
                        + "\n"
                        + "Log: " + QwenPaths.LOG_PATH + "\n");
            }
        } catch (Throwable ignored) {
            // A sandbox we cannot write to costs persistence, not correctness.
        }
    }

    /** Diagnostic one-liner for the adapter summary. */
    public static String describe() {
        return "chatPipeline=" + (isEnabled() ? "enabled" : "disabled")
                + " switch=" + SWITCH_FILE;
    }
}
