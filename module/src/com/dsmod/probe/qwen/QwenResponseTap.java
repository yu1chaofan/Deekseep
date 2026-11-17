package com.dsmod.probe.qwen;

/**
 * Bounded, side-effect-free rendering of one captured response body.
 *
 * <p>Split out of {@link QwenChatPipeline} because this is the only part of Tier B whose behaviour
 * can be reasoned about without an Android runtime, and therefore the only part worth asserting in
 * the offline self-test. Keeping it separate also keeps the hook callbacks down to "call the tap,
 * never throw", which is the rule a future edit is most likely to break.</p>
 *
 * <p><strong>What it deliberately does not do:</strong> it never mutates the argument, never
 * touches the pipeline log itself, and never decides whether logging is on. The caller owns all
 * three, so a bug here can at worst produce a wrong line, never a broken chat reply.</p>
 */
final class QwenResponseTap {
    /**
     * Hard cap on captured body text.
     *
     * <p>68 KiB matches {@link QwenPaths#readFile}'s 64 KiB read bound with a little slack, so a
     * captured body can never be larger than something the settings UI is able to display back.
     * A streaming reply is delivered through the same {@code QwenResponse} object as many small
     * chunks while a non-streaming reply arrives as one large string, so the cap has to cover the
     * single-string case: without it, one large attachment-style reply would be copied into the
     * log file on the network thread.</p>
     */
    static final int MAX_CAPTURED_CHARS = 65536;

    /** Appended to a truncated capture so the reader knows the body did not simply end. */
    static final String TRUNCATION_MARKER = "...[truncated]";

    private QwenResponseTap() {}

    /**
     * Renders {@code body} for the pipeline log, truncating at {@link #MAX_CAPTURED_CHARS}.
     *
     * <p>Returns an empty string for {@code null}: the mutator is declared with a non-null Kotlin
     * parameter check, but a host that skips the check (or a synthetic constructor path) can still
     * pass null, and "null body" is a fact worth logging rather than a reason to skip the line.
     * </p>
     *
     * <p>Escape sequences are applied before truncation so the cap is a cap on what is written,
     * not on what was read. {@code \n} is preserved because SSE frames are line-oriented and a
     * one-line-per-frame log is what makes the file readable; {@code \r} is folded away because
     * CRLF framing would otherwise double every line.</p>
     */
    static String capture(String body) {
        if (body == null) return "";
        int length = body.length();
        boolean truncated = length > MAX_CAPTURED_CHARS;
        String source = truncated ? body.substring(0, MAX_CAPTURED_CHARS) : body;
        StringBuilder out = new StringBuilder(source.length() + TRUNCATION_MARKER.length() + 8);
        out.append(source);
        if (truncated) {
            out.append('\n').append(TRUNCATION_MARKER)
                    .append(" totalChars=").append(length)
                    .append(" kept=").append(MAX_CAPTURED_CHARS);
        }
        return out.toString();
    }

    /**
     * True when {@link #capture(String)} would have truncated the body.
     *
     * <p>Exposed so the caller can count truncations and warn once, instead of relying on a reader
     * to notice a marker buried in a log file.</p>
     */
    static boolean wouldTruncate(String body) {
        return body != null && body.length() > MAX_CAPTURED_CHARS;
    }

    /**
     * True when {@code body} is structurally an SSE stream rather than a JSON document.
     *
     * <p>Used only for the log label. Both shapes arrive on the same object and a support reader
     * needs to know which is which before trusting a capture as "one reply".</p>
     */
    static boolean looksLikeSse(String body) {
        if (body == null) return false;
        String head = body.length() > 4096 ? body.substring(0, 4096) : body;
        return head.indexOf("data: ") >= 0 || head.indexOf("\ndata:") >= 0;
    }

    /** Short label for the log line: {@code sse}, {@code json|empty|text} and body length. */
    static String describeKind(String body) {
        if (body == null) return "null|0";
        String kind;
        if (body.length() == 0) {
            kind = "empty";
        } else if (looksLikeSse(body)) {
            kind = "sse";
        } else if (body.charAt(0) == '{' || body.charAt(0) == '[') {
            kind = "json";
        } else {
            kind = "text";
        }
        return kind + "|" + body.length();
    }
}
