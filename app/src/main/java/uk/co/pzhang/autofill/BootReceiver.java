package uk.co.pzhang.autofill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(AppId.INFO_TAG, "System Boot Completed. Syncing whitelist...");

            SharedPreferences prefs = context.getSharedPreferences("config_apps", Context.MODE_PRIVATE);
            Set<String> whitelist = prefs.getStringSet("whitelist", new HashSet<>());

            Intent syncIntent = new Intent(AppId.PACKAGE_NAME + ".CONFIG_UPDATED");
            syncIntent.putStringArrayListExtra("whitelist_data", new ArrayList<>(whitelist));

            syncIntent.addFlags(0x01000000 | 0x10000000 | 0x00000020);

            context.sendBroadcast(syncIntent);
            Log.d(AppId.DEBUG_TAG, "Boot sync sent: " + whitelist.size() + " apps.");
        }
    }
}