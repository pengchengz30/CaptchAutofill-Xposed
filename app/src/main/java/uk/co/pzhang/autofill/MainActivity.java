package uk.co.pzhang.autofill;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 设置基础窗口属性
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // 将内容视图设置放在前面，确保布局开始加载
        setContentView(R.layout.activity_main);

        TextView versionText = findViewById(R.id.tv_version_info);
        if (versionText != null) {
            // 自动获取 build.gradle 中的 versionName (如 "1.1")
            String versionName = BuildConfig.VERSION_NAME;

            // 使用 getString 填充模板
            String displayString = getString(R.string.version_format, versionName);
            versionText.setText(displayString);
        }

        // 2. 修复闪退点：使用更安全的方式获取 InsetsController
        // 我们可以直接从 DecorView 获取，或者通过 View 的 Post 机制
        View decorView = window.getDecorView();

        // 处理状态栏颜色适配逻辑
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isLightMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_NO);

        // 使用兼容性更好的 API 处理状态栏图标
        WindowInsetsController controller = decorView.getWindowInsetsController();
        if (controller != null) {
            if (isLightMode) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            } else {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }

        // 3. 处理 Window Insets (确保 ID 匹配)
        View mainLayout = findViewById(R.id.main_layout);
        if (mainLayout != null) {
            mainLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                // 获取系统栏的高度
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left + 60, bars.top + 60, bars.right + 60, bars.bottom + 60);
                return WindowInsets.CONSUMED;
            });
        }

        // 4. 开关逻辑
        Switch hideIconSwitch = findViewById(R.id.switch_hide_icon);
        if (hideIconSwitch != null) {
            hideIconSwitch.setChecked(isIconHidden());
            hideIconSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> toggleIcon(!isChecked));
        }

        Button contactBtn = findViewById(R.id.btn_contact_author);
        if (contactBtn != null) {
            contactBtn.setOnClickListener(v -> {
                // Your specific GitHub URL
                String githubUrl = "https://github.com/pengchengz30";
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(githubUrl));
                    // Opens in a new browser tab to prevent losing your app's state
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    // Error handling for edge cases where no browser is available
                    Toast.makeText(this, "No browser found to open GitHub.", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Checks if the LauncherAlias component is currently disabled (hidden from drawer).
     */
    private boolean isIconHidden() {
        ComponentName componentName = new ComponentName(this, getPackageName() + ".LauncherAlias");
        int setting = getPackageManager().getComponentEnabledSetting(componentName);
        return setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /**
     * Toggles the visibility of the app icon in the launcher by enabling/disabling the alias.
     * @param show True to enable the icon, False to hide it.
     */
    private void toggleIcon(boolean show) {
        ComponentName componentName = new ComponentName(this, getPackageName() + ".LauncherAlias");
        int newState = show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        getPackageManager().setComponentEnabledSetting(
                componentName,
                newState,
                PackageManager.DONT_KILL_APP
        );

        String status = show ? "Launcher Icon Enabled" : "Launcher Icon Hidden";
        Toast.makeText(this, status + ". System UI may take a moment to refresh.", Toast.LENGTH_SHORT).show();
    }
}