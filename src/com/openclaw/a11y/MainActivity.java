package com.openclaw.a11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 80, 48, 48);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("OpenClaw A11y Bridge");
        title.setTextSize(24);
        layout.addView(title);

        statusView = new TextView(this);
        statusView.setPadding(0, 24, 0, 24);
        layout.addView(statusView);

        // Поле для адреса ВПС
        TextView vpsLabel = new TextView(this);
        vpsLabel.setText("VPS адрес (host:port):");
        vpsLabel.setPadding(0, 24, 0, 4);
        layout.addView(vpsLabel);

        EditText vpsInput = new EditText(this);
        vpsInput.setHint("example.com:7334");
        String saved = getSharedPreferences("cfg", MODE_PRIVATE).getString("vps", "");
        vpsInput.setText(saved);
        layout.addView(vpsInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("Сохранить адрес ВПС");
        saveBtn.setOnClickListener(v -> {
            String addr = vpsInput.getText().toString().trim();
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("vps", addr).apply();
            Toast.makeText(this, "Сохранено: " + addr, Toast.LENGTH_SHORT).show();
        });
        layout.addView(saveBtn);

        // Кнопка включения Accessibility
        Button a11yBtn = new Button(this);
        a11yBtn.setText("Включить Accessibility Service");
        a11yBtn.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        layout.addView(a11yBtn);

        updateStatus();
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains("com.openclaw.a11y");
    }

    private void updateStatus() {
        boolean ok = isAccessibilityEnabled();
        String vps = getSharedPreferences("cfg", MODE_PRIVATE).getString("vps", "");
        String vpsStatus = vps.isEmpty() ? "  VPS: не настроен" : "  VPS: " + vps;
        statusView.setText(
            (ok ? "✅ Accessibility активен" : "❌ Accessibility выключен — нажмите кнопку ниже")
            + "\n" + vpsStatus
        );
    }
}