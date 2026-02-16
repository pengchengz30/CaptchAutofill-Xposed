package uk.co.pzhang.autofill;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "PZhangHook";
    public static final String ACTION_CODE_RECEIVED = "uk.co.pzhang.autofill.CODE_RECEIVED";

    // --- Performance Optimization: Pre-compiled Patterns ---
    private static final Pattern CODE_PATTERN = Pattern.compile("(?<![¥$￥£₩€.\\d#])\\b\\d{4,6}\\b(?!\\d|(\\.\\d+))");
    private static final Pattern INTENT_PATTERN = Pattern.compile(".*(code|otp|verify|auth|验证|認証|인증|校验|确认|动态|密码|码|번호|コード).*");
    private static final Pattern NOISE_PATTERN = Pattern.compile(".*(ref|id|no|amount|bal|订单|编号|金额|余额|合计|リファレンス|金額|금액|번호).*");
    private static final Pattern COPULA_PATTERN = Pattern.compile(".*(\\b(is)\\b[:：]?\\s*$|(是|为|為|爲|は)[:：]?\\s*$|[:：]\\s*$)");

    // --- Performance Optimization: O(1) App Whitelist ---
    private static final Set<String> TARGET_APPS = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "com.google.android.apps.googlevoice",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.android.shell"
//          ,  "com.google.android.apps.messaging"
    ));

    private static String lastProcessedCode = "";
    private static long lastProcessedTime = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("android")) return;

        Class<?> nmsClass = XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService",
                lpparam.classLoader
        );

        XposedBridge.hookAllMethods(nmsClass, "enqueueNotificationInternal", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // 1. Fast Package Filter
                String pkg = (String) param.args[0];
                if (!TARGET_APPS.contains(pkg)) return;

                // 2. Extract Notification Object
                Notification notification = null;
                for (Object arg : param.args) {
                    if (arg instanceof Notification) {
                        notification = (Notification) arg;
                        break;
                    }
                }

                if (notification != null) {
                    // 3. FLAG_ONGOING_EVENT Filter (Performance & Accuracy)
                    // Skip persistent notifications like downloads, calls, or media playback
                    if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                        return;
                    }

                    Bundle extras = notification.extras;
                    CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (text == null) text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                    if (text != null && text.length() >= 4) {
                        String code = extractCode(text.toString());
                        if (code != null) {
                            long currentTime = System.currentTimeMillis();

                            // Deduplication logic
                            if (code.equals(lastProcessedCode) && (currentTime - lastProcessedTime < 5000)) {
                                return;
                            }

                            lastProcessedCode = code;
                            lastProcessedTime = currentTime;

                            Log.w(TAG, "Code found: " + code + " | Source: " + pkg);

                            Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                            if (context != null) {
                                sendVerificationBroadcast(context, code);
                            }
                        }
                    }
                }
            }
        });
    }

    private void sendVerificationBroadcast(Context context, String code) {
        Intent intent = new Intent(ACTION_CODE_RECEIVED);
        intent.putExtra("captcha_code", code);
        intent.setPackage("uk.co.pzhang.autofill");
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
    }

    /**
     * High Performance Code Extraction
     * Principle: Better to return null than return the wrong code.
     */
    private String extractCode(String content) {
        if (content == null || content.isEmpty()) return null;

        Matcher matcher = CODE_PATTERN.matcher(content);

        // --- Fast Path: If no numbers found, exit immediately without heavy logic ---
        if (!matcher.find()) return null;
        matcher.reset();

        String intentCandidate = null;
        String copulaCandidate = null;
        String genericCandidate = null;

        int intentMatchCount = 0;

        while (matcher.find()) {
            String code = matcher.group();
            int start = matcher.start();
            int end = matcher.end();

            // Lazy windowing: only create substrings when a numeric candidate is actually found
            String prefix = content.substring(Math.max(0, start - 25), start).toLowerCase();
            String closePrefix = content.substring(Math.max(0, start - 12), start).toLowerCase();
            String suffix = content.substring(end, Math.min(content.length(), end + 15)).toLowerCase();

            // --- A. Physical Filtering (Years, Currency, etc.) ---
            if (code.startsWith("20") && suffix.matches("^\\s*[年号號년/\\-].*")) continue;
            if (suffix.matches("^\\s*(元|圆|圓|円|원|won|euros|yen|dollars|pounds|pts|krw|cny|jpy|usd|eur|gbp|块|塊).*")) continue;
            if (prefix.matches(".*\\b(usd|cny|eur|gbp|jpy|krw|hkd|baht|vnd)\\s+$")) continue;

            // --- B. Near-field Noise Suppression (Ref ID / Amount) ---
            if (NOISE_PATTERN.matcher(closePrefix).matches()) {
                continue;
            }

            // --- C. Intent Recognition (Safety First) ---
            if (INTENT_PATTERN.matcher(prefix).matches()) {
                // AMBIGUITY CHECK: If multiple different codes have intent, abort both.
                if (intentCandidate != null && !intentCandidate.equals(code)) {
                    Log.d(TAG, "Ambiguity detected: " + intentCandidate + " vs " + code);
                    return null;
                }
                intentCandidate = code;
                intentMatchCount++;
                continue;
            }

            // --- D. Copula Fallback ---
            if (COPULA_PATTERN.matcher(prefix).matches() || suffix.matches("^\\s*(です|입니다).*")) {
                if (copulaCandidate == null) copulaCandidate = code;
                continue;
            }

            // --- E. Generic Fallback ---
            if (code.length() >= 5 && genericCandidate == null) {
                genericCandidate = code;
            }
        }

        // Return the best candidate found
        if (intentCandidate != null) return intentCandidate;
        if (copulaCandidate != null) return copulaCandidate;
        return genericCandidate;
    }
}