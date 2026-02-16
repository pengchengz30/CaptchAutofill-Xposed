package uk.co.pzhang.autofill;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "PZhangHook";

    private static String lastProcessedCode = "";
    private static long lastProcessedTime = 0;

    public static final String ACTION_CODE_RECEIVED = "uk.co.pzhang.autofill.CODE_RECEIVED";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Only work within the system framework process
        if (!lpparam.packageName.equals("android")) return;

        // Get the NotificationManagerService class
        Class<?> nmsClass = XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService",
                lpparam.classLoader
        );

        // Robust Hook for Android 16: Hook all methods named enqueueNotificationInternal
        XposedBridge.hookAllMethods(nmsClass, "enqueueNotificationInternal", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // 1. Get the calling package name, usually the first argument
                if (param.args.length < 1 || !(param.args[0] instanceof String)) return;
                String pkg = (String) param.args[0];

                // 2. Check if it belongs to the target apps we monitor
                if (pkg.equals("org.telegram.messenger") ||
                        pkg.equals("com.google.android.apps.googlevoice") || pkg.equals("com.whatsapp") || pkg.equals("com.whatsapp.w4b") ||
                        pkg.equals("com.android.shell")) {

                    Log.i(TAG, "===> Intercepted! Processing package: " + pkg);

                    // 3. Find the Notification object dynamically without relying on a fixed index
                    Notification notification = null;
                    for (Object arg : param.args) {
                        if (arg instanceof Notification) {
                            notification = (Notification) arg;
                            break;
                        }
                    }

                    if (notification != null) {
                        Bundle extras = notification.extras;
                        // Retrieve the notification text content
                        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                        if (text == null) text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                        if (text != null) {
                            String code = extractCode(text.toString());
                            if (code != null) {
                                long currentTime = System.currentTimeMillis();

                                // Core deduplication: if the code is the same and the interval is less than 5 seconds, consider it a duplicate
                                if (code.equals(lastProcessedCode) && (currentTime - lastProcessedTime < 5000)) {
                                    // Log.v(TAG, "Skipping duplicate code: " + code);
                                    return;
                                }

                                // Update the cache records
                                lastProcessedCode = code;
                                lastProcessedTime = currentTime;

                                Log.w(TAG, "Code found: " + code + " | Source: " + pkg);

                                // 4. Get the Context and send a broadcast to our plugin process
                                Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                                if (context != null) {
                                    sendVerificationBroadcast(context, code);
                                } else {
                                    Log.e(TAG, "Context not found, failed to send broadcast");
                                }
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
        intent.setPackage("uk.co.pzhang.autofill"); // Pointing to our App package
        // Critical: Add FLAG_RECEIVER_FOREGROUND to ensure it triggers immediately even in the background
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
    }


    /**
     * Advanced Verification Code Extraction - Enhanced Version
     * Supports: English, Chinese, Japanese, Korean
     * Features: Near-field noise suppression, multi-intent priority logic
     */
    private String extractCode(String content) {
        if (content == null || content.isEmpty()) return null;

        // 1. Enhanced Regex: Exclude prefixes like #, :, and currency symbols.
        // Captures independent 4-6 digit numbers using word boundaries (\\b) and negative lookbehind.
        Pattern pattern = Pattern.compile("(?<![¥$￥£₩€.\\d#])\\b\\d{4,6}\\b(?!\\d|(\\.\\d+))");
        Matcher matcher = pattern.matcher(content);

        String intentCandidate = null;
        String copulaCandidate = null;
        String genericCandidate = null;

        while (matcher.find()) {
            String code = matcher.group();
            int start = matcher.start();
            int end = matcher.end();

            // Define detection windows
            // Far window (25 chars): Used to capture the "verification code" intent.
            String prefix = content.substring(Math.max(0, start - 25), start).toLowerCase();
            // Near window (12 chars): Used to quickly identify "noise" (e.g., Ref IDs) right before the number.
            String closePrefix = content.substring(Math.max(0, start - 12), start).toLowerCase();
            // Suffix window (15 chars): Used to check for currency units or year markers.
            String suffix = content.substring(end, Math.min(content.length(), end + 15)).toLowerCase();

            // --- A. Physical Layer Filtering (Date, Currency, Phone) ---
            // Exclude years (20xx/19xx) followed by date markers (e.g., "Year" or "-").
            if (code.startsWith("20") || code.startsWith("19")) {
                if (suffix.matches("^\\s*[年号號년/\\-].*")) continue;
            }
            // Exclude amounts (suffix contains currency units).
            if (suffix.matches("^\\s*(元|圆|圓|円|원|won|euros|yen|dollars|pounds|bucks|pts|krw|cny|jpy|usd|eur|gbp|块|塊).*")) continue;
            // Exclude amounts (prefix contains currency codes).
            if (prefix.matches(".*\\b(usd|cny|eur|gbp|jpy|krw|hkd|baht|vnd)\\s+$")) continue;
            // Exclude customer service numbers (5-digit numbers preceded by "call/tel").
            if (code.length() == 5 && prefix.matches(".*(拨打|致电|电话|联系|call|tel|phone|contact|전화|연락).*")) continue;

            // --- B. Core Fix: Near-field Noise Suppression ---
            // If keywords like "Ref", "ID", or "Amount" appear within 12 chars of the number, it's likely noise.
            boolean isExplicitNoise = closePrefix.matches(".*(ref|id|no|amount|bal|订单|编号|金额|余额|合计|リファレンス|金額|금액|번호).*");
            if (isExplicitNoise) {
                continue; // Discard even if it's 6 digits long.
            }

            // --- C. Strong Intent Recognition ---
            // Matches "verification code/OTP" keywords across four languages.
            boolean hasIntent = prefix.matches(".*(code|otp|verify|auth|验证|認証|인증|校验|确认|动态|密码|码|번호|コード).*");
            if (hasIntent) {
                // Priority by length: 5-6 digits > 4 digits.
                if (intentCandidate == null || code.length() >= 5) {
                    intentCandidate = code;
                }
                continue;
            }

            // --- D. Copula/Assignment Recognition ---
            // Matches logic like "is:", "code is:", etc.
            boolean hasCopula = prefix.matches(".*\\b(is)\\b[:：]?\\s*$") ||
                    prefix.matches(".*(是|为|為|爲|は)[:：]?\\s*$") ||
                    prefix.matches(".*[:：]\\s*$") ||
                    suffix.matches("^\\s*(です|입니다).*");

            if (hasCopula) {
                if (copulaCandidate == null) copulaCandidate = code;
                continue;
            }

            // --- E. Generic Numeric Fallback ---
            if (code.length() >= 5 && genericCandidate == null) {
                genericCandidate = code;
            }
        }

        // Final Decision Hierarchy: Strong Intent > Copula Recognition > Generic Fallback
        if (intentCandidate != null) return intentCandidate;
        if (copulaCandidate != null) return copulaCandidate;
        return genericCandidate;
    }
}