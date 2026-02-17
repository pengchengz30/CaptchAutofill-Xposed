package uk.co.pzhang.autofill;

public final class AppId {
    private AppId() {}
    public static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    public static final String ACTION_CODE_RECEIVED = PACKAGE_NAME + ".CODE_RECEIVED";
    public static final String INFO_TAG = "AUTOFILL_INFO";
    public static final String DEBUG_TAG = "AUTOFILL_DEBUG";
    public static final String WARNING_TAG = "AUTOFILL_WARNING";
    public static final String ERROR_TAG = "AUTOFILL_ERROR";
}
