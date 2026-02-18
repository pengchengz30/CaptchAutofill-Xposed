package uk.co.pzhang.autofill;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
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


    // --- Performance Optimization: Pre-compiled Patterns ---
    private static final Pattern CODE_PATTERN = Pattern.compile("(?<![¥$￥£₩€.\\d#])\\b\\d{4,6}\\b(?!\\d|(\\.\\d+))");
    private static final Pattern INTENT_PATTERN = Pattern.compile(".*(code|otp|verify|auth|验证|認証|인증|校验|确认|动态|密码|码|번호|コード).*");
    private static final Pattern NOISE_PATTERN = Pattern.compile(".*(ref|id|no|amount|bal|订单|编号|金额|余额|合计|リファレンス|金額|금액|번호).*");
    private static final Pattern COPULA_PATTERN = Pattern.compile(".*(\\b(is)\\b[:：]?\\s*$|(是|为|為|爲|は)[:：]?\\s*$|[:：]\\s*$)");

    private static Set<String> mWhitelistCache = new HashSet<>();
    private static boolean isReceiverRegistered = false;
    private static String lastProcessedCode = "";
    private static long lastProcessedTime = 0;

//    private int intentMatchCount = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("android")) return;

        XposedBridge.log(AppId.DEBUG_TAG + ": Hook Initialized on Pixel 10 Pro.");

        Class<?> nmsClass = XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService",
                lpparam.classLoader
        );

        XposedBridge.hookAllMethods(nmsClass, "enqueueNotificationInternal", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isReceiverRegistered) {
                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    if (context != null) {
                        registerConfigReceiver(context);
                        isReceiverRegistered = true;
                    }
                }

                String pkg = (String) param.args[0];
                Log.d(AppId.DEBUG_TAG, "==> Notification from: " + pkg + " | Current Cache Size: " + mWhitelistCache.size());

                if (mWhitelistCache == null) {
                    Log.e(AppId.ERROR_TAG, "Whitelist is null");
                    return;
                }

                if (mWhitelistCache.isEmpty()) {
                    Log.w(AppId.WARNING_TAG, "Whitelist is empty");
                    return;
                }

                if (!mWhitelistCache.contains(pkg)) {
                    return;
                }

                Log.d(AppId.DEBUG_TAG, "!!! WHITELIST HIT: " + pkg + " !!!");


                Notification notification = (Notification) param.args[6];
                if (notification == null) {
                    for (Object arg : param.args) {
                        if (arg instanceof Notification) {
                            notification = (Notification) arg;
                            break;
                        }
                    }
                }

                if (notification != null && (notification.flags & Notification.FLAG_ONGOING_EVENT) == 0) {
                    Bundle extras = notification.extras;
                    CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (text == null) text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                    String fullContent = (title != null ? title : "") + " " + (text != null ? text : "");

                    String code = extractCode(fullContent);
                    if (code != null) {
                        long currentTime = System.currentTimeMillis();
                        if (code.equals(lastProcessedCode) && (currentTime - lastProcessedTime < 5000))
                            return;

                        lastProcessedCode = code;
                        lastProcessedTime = currentTime;

                        Log.d(AppId.DEBUG_TAG, "!!! Captured Code: " + code + " from " + pkg + " !!!");

                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (context != null) sendVerificationBroadcast(context, code);
                    }
                }
            }
        });
    }

    private void registerConfigReceiver(Context context) {
        IntentFilter filter = new IntentFilter(AppId.PACKAGE_NAME + ".CONFIG_UPDATED");
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(AppId.DEBUG_TAG, "!!! RECEIVED BROADCAST IN HOOK PROCESS !!!");
                ArrayList<String> data = intent.getStringArrayListExtra("whitelist_data");
                if (data != null) {
                    mWhitelistCache = new HashSet<>(data);
                    Log.d(AppId.DEBUG_TAG, "SUCCESS: Whitelist Size updated to " + mWhitelistCache.size());
                } else {
                    Log.e(AppId.ERROR_TAG, "RECEIVED BROADCAST BUT DATA WAS NULL!");
                }
            }
        }, filter, Context.RECEIVER_EXPORTED);

        Intent intent = new Intent(AppId.PACKAGE_NAME + ".REQUEST_SYNC");
        intent.setClassName(AppId.PACKAGE_NAME, AppId.PACKAGE_NAME + ".BootReceiver");
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);

        try {
            Class<?> userHandleClass = XposedHelpers.findClass("android.os.UserHandle", context.getClassLoader());
            Object userHandleCurrent = XposedHelpers.getStaticObjectField(userHandleClass, "CURRENT");

            XposedHelpers.callMethod(context, "sendBroadcastAsUser", intent, userHandleCurrent);
            Log.d(AppId.DEBUG_TAG, "!!! Explicit Kickstart broadcast sent from system_server !!!");
        } catch (Exception e) {
            context.sendBroadcast(intent);
        }
    }

    private String extractCode(String content) {
        if (content == null || content.isEmpty()) return null;

        Matcher matcher = CODE_PATTERN.matcher(content);

        // --- Fast Path: If no numbers found, exit immediately without heavy logic ---
        if (!matcher.find()) return null;
        matcher.reset();

        String intentCandidate = null;
        String copulaCandidate = null;
        String genericCandidate = null;

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
            if (suffix.matches("^\\s*(元|圆|圓|円|원|won|euros|yen|dollars|pounds|pts|krw|cny|jpy|usd|eur|gbp|块|塊).*"))
                continue;
            if (prefix.matches(".*\\b(usd|cny|eur|gbp|jpy|krw|hkd|baht|vnd)\\s+$")) continue;

            // --- B. Near-field Noise Suppression (Ref ID / Amount) ---
            if (NOISE_PATTERN.matcher(closePrefix).matches()) {
                continue;
            }

            // --- C. Intent Recognition (Safety First) ---
            if (INTENT_PATTERN.matcher(prefix).matches()) {
                // AMBIGUITY CHECK: If multiple different codes have intent, abort both.
                if (intentCandidate != null && !intentCandidate.equals(code)) {
                    Log.d(AppId.DEBUG_TAG, "Ambiguity detected: " + intentCandidate + " vs " + code);
                    return null;
                }
                intentCandidate = code;
//                intentMatchCount++;
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

    private void sendVerificationBroadcast(Context context, String code) {
        Intent intent = new Intent(AppId.ACTION_CODE_RECEIVED);
        intent.putExtra("captcha_code", code);
        intent.setPackage(AppId.PACKAGE_NAME);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
    }
}