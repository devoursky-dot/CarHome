package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 전체 화면 모드 (상단 상태바 숨김)
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars());
        }

        setContentView(R.layout.activity_settings);

        ImageView btnClose = findViewById(R.id.btnCloseSettings);
        btnClose.setOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);

        // 1. 슬라이더 바인딩
        setupSeekBar(R.id.seekFloatingY, R.id.tvFloatingY, "floating_y", prefs.getInt("floating_y", 132), " px");
        setupSeekBar(R.id.seekPopupY, R.id.tvPopupY, "popup_y", prefs.getInt("popup_y", 200), " px");
        setupSeekBar(R.id.seekAutoClose, R.id.tvAutoClose, "auto_close", prefs.getInt("auto_close", 5), " 초");
        setupSeekBar(R.id.seekTmapX, R.id.tvTmapX, "tmap_x", prefs.getInt("tmap_x", 1130), "");
        setupSeekBar(R.id.seekTmapY, R.id.tvTmapY, "tmap_y", prefs.getInt("tmap_y", 70), "");

        setupSeekBar(R.id.seekHandleClockSize, R.id.tvHandleClockSize, "handle_clock_size", prefs.getInt("handle_clock_size", 18), " sp");
        setupSeekBar(R.id.seekPopupClockSize, R.id.tvPopupClockSize, "popup_clock_size", prefs.getInt("popup_clock_size", 50), " sp");
        setupSeekBar(R.id.seekPopupIconSize, R.id.tvPopupIconSize, "popup_icon_size", prefs.getInt("popup_icon_size", 80), " dp");

        // 2. 비주얼 매크로 좌표 피커(조준경) 실행 버튼
        View btnVisualPicker = findViewById(R.id.btnOpenVisualPicker);
        if (btnVisualPicker != null) {
            btnVisualPicker.setOnClickListener(v -> {
                CoordinatePickerOverlay.getInstance(this).show((x, y) -> {
                    SeekBar seekX = findViewById(R.id.seekTmapX);
                    TextView tvX = findViewById(R.id.tvTmapX);
                    SeekBar seekY = findViewById(R.id.seekTmapY);
                    TextView tvY = findViewById(R.id.tvTmapY);
                    if (seekX != null && tvX != null) {
                        seekX.setProgress(x);
                        tvX.setText(String.valueOf(x));
                    }
                    if (seekY != null && tvY != null) {
                        seekY.setProgress(y);
                        tvY.setText(String.valueOf(y));
                    }
                });
            });
        }

        // 3. 배터리 최적화 제외 요청 버튼
        View btnBatteryOpt = findViewById(R.id.btnBatteryOptimization);
        if (btnBatteryOpt != null) {
            btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimizationExemption());
        }
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "설정 화면으로 직접 이동해주세요.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "이미 배터리 최적화에서 제외되어 상시 실행이 보장됩니다. 👍", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "해당 안드로이드 버전은 배터리 제한이 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // 슬라이더 초기화 및 실시간 저장 헬퍼 메서드
    private void setupSeekBar(int seekId, int tvId, String prefKey, int initialValue, String suffix) {
        SeekBar seekBar = findViewById(seekId);
        TextView textView = findViewById(tvId);

        if (seekBar == null || textView == null) return;

        seekBar.setProgress(initialValue);
        textView.setText(initialValue + suffix);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (prefKey.equals("auto_close") && progress < 1) progress = 1;

                textView.setText(progress + suffix);

                SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
                prefs.edit().putInt(prefKey, progress).apply();

                if (prefKey.equals("floating_y") || prefKey.equals("popup_y") ||
                        prefKey.equals("handle_clock_size") || prefKey.equals("popup_clock_size") ||
                        prefKey.equals("popup_icon_size")) {
                    Intent intent = new Intent("com.example.carhome.UPDATE_SETTINGS");
                    intent.putExtra("key", prefKey);
                    sendBroadcast(intent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
}