package com.dsmod.probe.qwen;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Runtime symbol probe for the supported Qwen Studio generation.
 *
 * <p>Mirrors the shape of {@code com.dsmod.probe.HostCompat} — a one-shot {@code initialize},
 * {@code generationName()}, {@code isSupported()}, {@code diagnosticSummary()} — but is kept in a
 * separate class on purpose. Qwen shares no obfuscated symbols with DeepSeek, so every DeepSeek
 * generation table would resolve to a wrong or missing class here, and merging the two would make
 * one host's re-obfuscation silently switch the other host's hooks.</p>
 *
 * <p><strong>Channel detection is deliberately not inherited.</strong> {@code HostCompat} infers
 * "Google Play" from the presence of {@code com.pairip.licensecheck.LicenseActivity}, but Qwen
 * Studio 2.0.0 ships that class even though its manifest is the only analysed flavour, so the
 * inherited rule would classify every Qwen install as Google Play and pick the wrong branch.
 * The Qwen adapter therefore resolves the channel from an explicit marker only, defaults to a
 * single flavour, and reports the PairIP presence as diagnostic noise instead.</p>
 */
public final class QwenCompat {
    /** Single-flavour host: the only distribution analysed so far. */
    public static final String CHANNEL_SINGLE = "single";

    /** Explicitly marked Google Play flavour; never inferred from PairIP. */
    public static final String CHANNEL_GOOGLE_PLAY = "qwen-gp";

    private static final String PAIRIP_MARKER = "com.pairip.licensecheck.LicenseActivity";

    private static volatile boolean initialized;
    private static volatile boolean v200;
    private static volatile boolean mainActivityPresent;
    private static volatile boolean applicationClassPresent;
    private static volatile boolean pairipPresent;

    /**
     * Logical symbol table. A {@code null} value means "not analysed for this host", which every
     * caller must treat as a hard stop rather than as a signal to try reflection.
     */
    private static volatile String mainActivityClass;
    private static volatile String settingsEntryClass;

    private QwenCompat() {}

    /**
     * Probes the host once.
     *
     * <p>{@code Class.forName(name, false, loader)} never runs static initialisers, so probing
     * cannot execute host code this adapter has not been tested against. The whole body is
     * guarded: reflection on a hostile or partially loaded class loader can throw
     * {@code LinkageError}, which is an {@code Error} and would otherwise escape into the Xposed
     * callback and take the host down.</p>
     */
    public static synchronized void initialize(ClassLoader loader) {
        if (initialized) return;
        v200 = true;
        pairipPresent = classExists(loader, PAIRIP_MARKER);
        mainActivityClass = classExists(loader, QwenHost.MAIN_ACTIVITY)
                ? QwenHost.MAIN_ACTIVITY : null;
        mainActivityPresent = mainActivityClass != null;
        applicationClassPresent = classExists(loader, QwenHost.APPLICATION_CLASS);
        settingsEntryClass = probeSettingsEntry(loader);
        initialized = true;
    }

    /** True once {@link #initialize(ClassLoader)} has run to completion. */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * True when the probed host is a generation this adapter has a contract for.
     *
     * <p>Qwen 2.0.0 is the only generation analysed. A future build that keeps the package name
     * but renames its launcher must not silently inherit this contract, so a missing
     * {@code MainActivity} disqualifies the host even though the package name still matches.</p>
     */
    public static boolean isSupported() {
        return v200 && mainActivityPresent;
    }

    /** True when the launcher activity resolved; the cheapest positive signal for "this is Qwen". */
    public static boolean hasMainActivity() {
        return mainActivityPresent;
    }

    /** True when the host Application class resolved; used only for diagnostics. */
    public static boolean hasApplicationClass() {
        return applicationClassPresent;
    }

    /**
     * Resolves the distribution channel without consulting PairIP.
     *
     * <p>Order matters: an explicit user marker wins, because a repackaged host may carry any
     * subset of the store metadata. Only the marker can produce {@link #CHANNEL_GOOGLE_PLAY};
     * everything else is {@link #CHANNEL_SINGLE}.</p>
     */
    public static String resolveChannel() {
        try {
            String marker = QwenPaths.readFile(QwenPaths.GOOGLE_PLAY_MARKER_FILE);
            if (marker != null && marker.trim().length() > 0) return CHANNEL_GOOGLE_PLAY;
        } catch (Throwable ignored) {}
        return CHANNEL_SINGLE;
    }

    /** Always {@code false}; kept so callers written against {@code HostCompat} compile. */
    public static boolean isGooglePlay() {
        return false;
    }

    /** True when PairIP classes are present in the host APK. Diagnostic only, never a channel. */
    public static boolean isPairipPresent() {
        return pairipPresent;
    }

    /** Human readable generation label, shaped like {@code HostCompat.generationName()}. */
    public static String generationName() {
        if (!initialized) return "unknown";
        if (!v200) return "unsupported";
        return "2.0.0/code2000000-" + (CHANNEL_GOOGLE_PLAY.equals(resolveChannel()) ? "gp" : "single");
    }

    /**
     * Accepts the verified version name.
     *
     * <p>A null name is accepted on purpose, matching {@code HostCompat}: callers read the name
     * from {@code PackageManager}, which can legitimately return null during early bootstrap, and
     * rejecting the host there would make the adapter look broken on valid installs.</p>
     */
    public static boolean isSupportedVersionName(String versionName) {
        if (versionName == null) return true;
        return QwenHost.SUPPORTED_VERSION_NAME
                .equals(QwenHost.normalizedVersionName(versionName));
    }

    /**
     * Accepts the verified version code, or any code of the verified major/minor line.
     *
     * <p>Qwen encodes {@code 2.0.0} as {@code 2000000}; patch releases of the same line arrive as
     * {@code 20000xx}. Those are the most likely hotfix targets and reusing the 2.0.0 contract for
     * them is a bounded risk, unlike accepting an unknown major line.</p>
     */
    public static boolean isSupportedVersionCode(long versionCode) {
        if (versionCode <= 0L) return true;
        return versionCode / 10000L == QwenHost.SUPPORTED_VERSION_CODE / 10000L;
    }

    /** Combined gate used by the attach path. */
    public static boolean isVersionAccepted(String versionName, long versionCode) {
        return isSupportedVersionName(versionName) && isSupportedVersionCode(versionCode);
    }

    /**
     * Drops stray whitespace out of a reflected symbol name.
     *
     * <p>Kept for parity with the DeepSeek probe, where {@code Method.getName()} on a
     * smali-level synthetic could return a padded name; on Qwen the host is not minified, so this
     * is defensive only. Java identifiers cannot contain NUL, so trimming is the whole job.</p>
     */
    public static String trimSymbol(String symbol) {
        if (symbol == null) return "";
        return symbol.trim();
    }

    /** Returns the Qwen launcher activity name, or {@code null} when it did not resolve. */
    public static String mainActivityClass() {
        return mainActivityClass;
    }

    /**
     * Returns the class that currently provides the in-app settings entry, or {@code null}.
     *
     * <p>Populated only by {@link #probeSettingsEntry(ClassLoader)}, which accepts a single
     * conservative shape: a static zero-argument method named {@code settings} on the launcher.
     * Anything else leaves this {@code null}, and a {@code null} here is a hard stop for the
     * settings hook, not a reason to guess a name. Qwen's settings surface itself is a Compose
     * composable, so the real Tier B entry still needs a symbol comparison run first.</p>
     */
    public static String settingsEntryClass() {
        return settingsEntryClass;
    }

    /**
     * Multi-line status block written to the module log on every attach.
     *
     * <p>The {@code pairip=present(ignored)} line exists so the next person reading a bug report
     * sees immediately that the class is present and that the channel decision ignored it, instead
     * of re-deriving the DeepSeek rule and re-introducing the misclassification.</p>
     */
    public static String diagnosticSummary() {
        return "host=" + QwenHost.PACKAGE
                + "\ngeneration=" + generationName()
                + "\nchannel=" + resolveChannel()
                + "\npairip=" + (pairipPresent ? "present(ignored)" : "absent")
                + "\nmainActivity=" + (mainActivityPresent ? QwenHost.MAIN_ACTIVITY : "missing")
                + "\napplication=" + (applicationClassPresent
                        ? QwenHost.APPLICATION_CLASS : "missing")
                + "\nsettingsEntry=" + (settingsEntryClass == null ? "not-analysed" : settingsEntryClass);
    }

    private static boolean classExists(ClassLoader loader, String name) {
        if (loader == null || name == null) return false;
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Looks for a static, zero-argument {@code settings} entry on the launcher.
     *
     * <p>This is the only reflective shape the adapter is willing to accept without a symbol
     * comparison run: a static no-arg method on an already loaded public class cannot capture
     * {@code this} and cannot corrupt host state if the guess is wrong. Everything else stays
     * {@code null} and therefore Tier C.</p>
     */
    private static String probeSettingsEntry(ClassLoader loader) {
        if (loader == null) return null;
        try {
            Class<?> type = Class.forName(QwenHost.MAIN_ACTIVITY, false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() != 0) continue;
                if (!"settings".equals(method.getName())) continue;
                return type.getName();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
