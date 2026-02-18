package uk.co.pzhang.autofill;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MainActivity extends Activity {

    private AppAdapter appAdapter;
    private List<AppListItem> allApps = new ArrayList<>();
    private SharedPreferences prefs;

    private boolean isSystemShowing = false;
    private String currentSearchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("config_apps", MODE_PRIVATE);

        appAdapter = new AppAdapter(new ArrayList<>(), this::updateWhitelist);

        new Thread(() -> {
            loadApps();
            runOnUiThread(() -> {
                filterAndDisplayApps(isSystemShowing, currentSearchQuery);
                updateSelectedCount();
                notifyHookProcess();
            });
            Log.i(AppId.INFO_TAG, "UI started");
        }).start();

        Button manageBtn = findViewById(R.id.btn_manage_apps);
        if (manageBtn != null) manageBtn.setOnClickListener(v -> showAppListDialog());

        Switch hideIconSwitch = findViewById(R.id.switch_hide_icon);
        if (hideIconSwitch != null) {
            hideIconSwitch.setChecked(isIconHidden());
            hideIconSwitch.setOnCheckedChangeListener((v, isChecked) -> toggleIcon(!isChecked));
        }

        findViewById(R.id.btn_contact_author).setOnClickListener(v -> openGitHub());

        setupWindowInsets();

        TextView versionText = findViewById(R.id.tv_version_info);
        if (versionText != null) {
            versionText.setText("Version: " + BuildConfig.VERSION_NAME);
        }
    }

    private void updateWhitelist(String pkg, boolean add) {
        for (AppListItem app : allApps) {
            if (app.packageName.equals(pkg)) {
                app.isSelected = add;
                break;
            }
        }

        Set<String> whitelist = new HashSet<>(prefs.getStringSet("whitelist", new HashSet<>()));
        if (add) whitelist.add(pkg);
        else whitelist.remove(pkg);

        prefs.edit().putStringSet("whitelist", whitelist).apply();

        updateSelectedCount();
        notifyHookProcess();
    }

    private void notifyHookProcess() {
        Set<String> whitelist = prefs.getStringSet("whitelist", new HashSet<>());
        ArrayList<String> dataToSend = new ArrayList<>(whitelist);

        Intent intent = new Intent(AppId.PACKAGE_NAME + ".CONFIG_UPDATED");
        intent.putStringArrayListExtra("whitelist_data", dataToSend);

        intent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

        sendBroadcast(intent);
        Log.d(AppId.DEBUG_TAG, "Sent sync broadcast. Size: " + dataToSend.size());
    }

    private void showAppListDialog() {
        if (allApps.isEmpty()) {
            Toast.makeText(this, "Still loading apps...", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_app_selection, null);
        dialog.setContentView(dialogView);

        RecyclerView rv = dialogView.findViewById(R.id.dialog_rv_apps);

        Switch systemSwitch = dialogView.findViewById(R.id.dialog_switch_system);
        EditText searchEt = dialogView.findViewById(R.id.et_search_app);

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(appAdapter);
        }

        if (systemSwitch != null) {
            systemSwitch.setOnCheckedChangeListener(null);
            systemSwitch.setChecked(this.isSystemShowing);

            systemSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                this.isSystemShowing = isChecked;
                Log.d(AppId.DEBUG_TAG, "UI TRIGGER: System Switch is now " + isChecked);

                filterAndDisplayApps(this.isSystemShowing, this.currentSearchQuery);
            });
        } else {
            Log.e(AppId.ERROR_TAG, "CRITICAL: dialog_switch_system NOT FOUND in layout!");
        }

        if (searchEt != null) {
            searchEt.setText(this.currentSearchQuery);
            searchEt.addTextChangedListener(new TextWatcher() {
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentSearchQuery = s.toString();
                    filterAndDisplayApps(isSystemShowing, currentSearchQuery);
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        filterAndDisplayApps(this.isSystemShowing, this.currentSearchQuery);
        dialog.show();
    }

    private synchronized void filterAndDisplayApps(boolean showSystem, String query) {
        if (allApps == null || appAdapter == null) return;

        List<AppListItem> filtered = allApps.stream()
                .filter(app -> {

                    if (app.isSystem) return showSystem;
                    return true;
                })
                .filter(app -> {
                    if (query == null || query.isEmpty()) return true;
                    String q = query.toLowerCase();
                    return app.name.toLowerCase().contains(q) || app.packageName.toLowerCase().contains(q);
                })
                .sorted((a, b) -> {

                    if (a.isSelected != b.isSelected) return a.isSelected ? -1 : 1;

                    return a.name.compareToIgnoreCase(b.name);
                })
                .collect(Collectors.toList());

        Log.d(AppId.DEBUG_TAG, "Filter: showSystem=" + showSystem + " | count=" + filtered.size());

        runOnUiThread(() -> {
            appAdapter.updateData(filtered);

            appAdapter.notifyDataSetChanged();
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> whitelist = prefs.getStringSet("whitelist", new HashSet<>());
        List<AppListItem> tempList = new ArrayList<>();

        try {
            List<android.content.pm.ApplicationInfo> installedApps = pm.getInstalledApplications(0);

            for (android.content.pm.ApplicationInfo info : installedApps) {
                if (info.packageName.equals(getPackageName())) continue;

                String label;
                try {
                    label = info.loadLabel(pm).toString();
                } catch (Exception e) {
                    label = info.packageName; // Fallback to package name
                }

                android.graphics.drawable.Drawable icon;
                try {
                    icon = info.loadIcon(pm);
                } catch (Exception e) {
                    icon = pm.getDefaultActivityIcon(); // Fallback to default icon
                }

                boolean isSys = (info.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM |
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;

                tempList.add(new AppListItem(
                        label,
                        info.packageName,
                        icon,
                        isSys,
                        whitelist.contains(info.packageName)
                ));
            }
            allApps = tempList;
            Log.d(AppId.DEBUG_TAG, "Successfully loaded total apps: " + allApps.size());
        } catch (Exception e) {
            Log.e(AppId.ERROR_TAG, "FATAL: Could not get installed applications", e);
        }
    }

    private void updateSelectedCount() {
        runOnUiThread(() -> {
            TextView countText = findViewById(R.id.tv_selected_count);
            if (countText != null) {
                long count = allApps.stream().filter(app -> app.isSelected).count();
                countText.setText(count + " apps selected");
            }
        });
    }

    private void setupWindowInsets() {
        View main = findViewById(R.id.main_layout);
        if (main != null) {
            main.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left + 60, bars.top + 60, bars.right + 60, bars.bottom + 60);
                return android.view.WindowInsets.CONSUMED;
            });
        }
    }

    private boolean isIconHidden() {
        ComponentName cn = new ComponentName(this, getPackageName() + ".LauncherAlias");
        return getPackageManager().getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void toggleIcon(boolean show) {
        ComponentName cn = new ComponentName(this, getPackageName() + ".LauncherAlias");
        getPackageManager().setComponentEnabledSetting(cn, show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        Toast.makeText(this, (show ? "Icon Visible" : "Icon Hidden"), Toast.LENGTH_SHORT).show();
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pengchengz30")));
        } catch (Exception ignored) {
        }
    }

    public static class AppListItem {
        public String name, packageName;
        public android.graphics.drawable.Drawable icon;
        public boolean isSystem, isSelected;

        public AppListItem(String n, String p, android.graphics.drawable.Drawable i, boolean s, boolean sel) {
            name = n;
            packageName = p;
            icon = i;
            isSystem = s;
            isSelected = sel;
        }
    }


}