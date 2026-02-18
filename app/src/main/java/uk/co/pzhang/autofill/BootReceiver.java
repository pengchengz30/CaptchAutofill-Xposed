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

        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || (AppId.PACKAGE_NAME + ".REQUEST_SYNC").equals(action)) {

            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Log.i(AppId.INFO_TAG, "System Boot Completed. Syncing whitelist...");
            } else {
                Log.i(AppId.INFO_TAG, "REQUEST_SYNC Received!!!");
            }

            SharedPreferences prefs = context.getSharedPreferences("config_apps", Context.MODE_PRIVATE);
            Set<String> whitelist = prefs.getStringSet("whitelist", new HashSet<>());

            Intent syncIntent = new Intent(AppId.PACKAGE_NAME + ".CONFIG_UPDATED");
            syncIntent.putStringArrayListExtra("whitelist_data", new ArrayList<>(whitelist));

            syncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

            context.sendBroadcast(syncIntent);
            Log.d(AppId.DEBUG_TAG, "Boot sync sent: " + whitelist.size() + " apps.");
        }
    }
}