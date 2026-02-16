package uk.co.pzhang.autofill;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

public class CodeReceiver extends BroadcastReceiver {
    private static final String TAG = "PZhangAutofill";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Check if the broadcast action matches
        if (MainHook.ACTION_CODE_RECEIVED.equals(intent.getAction())) {
            String code = intent.getStringExtra("captcha_code");
            if (code != null) {
                // Get the system clipboard service
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("captcha", code);

                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Log.d(TAG, "Verification code written to clipboard: " + code);

                    // Optional: Show a brief toast notification at the bottom
                    Toast.makeText(context, "Code " + code + " copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}