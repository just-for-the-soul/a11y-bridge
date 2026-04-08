package com.openclaw.a11y;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Простой layout программно
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 80, 48, 48);

        TextView title = new TextView(this);
        title.setText("OpenClaw A11y Bridge");
        title.setTextSize(24);
        layout.addView(title);

        TextView status = new TextView(this);
        status.setPadding(0, 24, 0, 24);
        layout.addView(status);

        // Поле для адреса ВПС
        TextView vpsLabel = new TextView(this);
        vpsLabel.setText("VPS адрес (host:port):");
        layout.addView(vpsLabel);

        EditText vpsInput = new EditText(this);
        vpsInput.setHint("example.com:7334");
        String saved = getSharedPreferences("cfg", 0).getString("vps", "");
        vpsInput.setText(saved);
        layout.addView(vpsInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("Сохранить и подключиться");
        saveBtn.setOnClickListener(v -> {
            String addr = vpsInput.getText().toString().trim();
            getSharedPreferences("cfg", 0).edit().putString("vps", addr).apply();
            // Перезапустить сервис
            Intent i = new Intent(this, ClawAccessibilityService.class);
            Toast.makeText(this, "Сохранено. Переключите сервис.", Toast.LENGTH_SHORT).show();
        });
        layout.addView(saveBtn);

        // Кнопка включения Accessibility
        Button a11yBtn = new Button(this);
        a11yBtn.setText("⚙️ Включить Accessibility Service");
        a11yBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            // На Android 12+ можно открыть сразу страницу нашего сервиса
            intent.putExtra(":settings:show_fragment_args",
                "com.openclaw.a11y/.ClawAccessibilityService");
            startActivity(intent);
        });
        layout.addView(a11yBtn);

        // Статус
        updateStatus(status);

        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // обновить статус после возврата из настроек
        LinearLayout layout = (LinearLayout) ((ScrollView) getWindow()
            .getDecorView().getRootView()).getChildAt(0);
        // проще — просто показать Toast
        if (isAccessibilityEnabled()) {
            Toast.makeText(this, "✅ Accessibility включён!", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains("com.openclaw.a11y");
    }

    private void updateStatus(TextView tv) {
        boolean ok = isAccessibilityEnabled();
        tv.setText(ok
            ? "✅ Сервис активен"
            : "❌ Сервис не включён — нажмите кнопку ниже");
    }
}

