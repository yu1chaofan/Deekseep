package com.dsmod.probe.qwen;

/**
 * Host facts for the Qwen Studio application ({@code ai.qwenlm.chat.android}).
 *
 * <p>Qwen Studio is not a DeepSeek generation: it shares no obfuscated symbol table with
 * {@code com.deepseek.chat}, it is built on Compose plus Ktor instead of DeepSeek's own request
 * model, and its package name never reaches the DeepSeek bootstrap gate in
 * {@code Main.handleLoadPackage}. Keeping its identity in one place lets the bootstrap branch on
 * a single constant instead of growing a second target list inside the DeepSeek chain.</p>
 *
 * <p>Every value below was read from the shipped AndroidManifest of Qwen Studio 2.0.0, not
 * guessed: {@code versionCode="2000000"}, {@code versionName="2.0.0"}, {@code minSdkVersion=30},
 * application {@code QwenChatApplication}, launcher {@code MainActivity}. The strings are
 * duplicated from that manifest on purpose, because the module must recognise the host before it
 * can reflect on any host class.</p>
 */
public final class QwenHost {
    /** Application id of the supported Qwen Studio build. */
    public static final String PACKAGE = "ai.qwenlm.chat.android";

    /** Data directory owned by the host uid; every adapter-side file lives below it. */
    public static final String DATA_DIR = "/data/data/" + PACKAGE;

    /** {@code files/} directory of the host; the module's own storage contract starts here. */
    public static final String FILES_DIR = DATA_DIR + "/files";

    /** Launcher activity, kept un-obfuscated by the host. */
    public static final String MAIN_ACTIVITY = PACKAGE + ".MainActivity";

    /** {@code android:name} of the host Application class. */
    public static final String APPLICATION_CLASS = PACKAGE + ".QwenChatApplication";

    /** Verified {@code versionName} of the only Qwen generation analysed so far. */
    public static final String SUPPORTED_VERSION_NAME = "2.0.0";

    /** Verified {@code versionCode} that pairs with {@link #SUPPORTED_VERSION_NAME}. */
    public static final long SUPPORTED_VERSION_CODE = 2000000L;

    /** {@code minSdkVersion} of the supported build; below this the host cannot run at all. */
    public static final int MIN_SDK = 30;

    /**
     * Suffix appended to files that live outside the host sandbox.
     *
     * <p>{@code /storage/emulated/0} is shared across applications, so the DeepSeek branch and the
     * Qwen branch would overwrite each other's load marker and interleave log lines if they kept
     * the same external file names. Files under {@code files/} need no suffix because the uid
     * already separates them.</p>
     */
    public static final String EXTERNAL_SUFFIX = "_qwen";

    private QwenHost() {}

    /**
     * True only for the exact production application id.
     *
     * <p>No embedded or suffixed coexistence variant is accepted, because none has been analysed
     * for this host: accepting an unverified id would install the adapter into a process whose
     * storage contract is unknown.</p>
     */
    public static boolean isQwenPackage(String packageName) {
        return PACKAGE.equals(packageName);
    }

    /**
     * Normalises a host {@code versionName} for comparison.
     *
     * <p>The manifest carries a bare semantic version today, but store builds sometimes append a
     * build suffix ({@code 2.0.0-arm64}), and the DeepSeek compat layer already had to strip such
     * suffixes to avoid rejecting valid installs. Applying the same rule here keeps the gate
     * honest across repackaged builds.</p>
     */
    public static String normalizedVersionName(String versionName) {
        if (versionName == null) return "";
        String value = versionName.trim();
        int suffix = value.indexOf('-');
        return suffix > 0 ? value.substring(0, suffix) : value;
    }
}
