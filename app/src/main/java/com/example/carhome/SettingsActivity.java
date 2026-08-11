package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class SettingsActivity extends AppCompatActivity {

    private WindowManager windowManager;
    private View previewCircleView = null;
    private WindowManager.LayoutParams previewParams = null;
    private int currentX = 1130;
    private int currentY = 70;

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

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        ImageView btnClose = findViewById(R.id.btnCloseSettings);
        btnClose.setOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        currentX = prefs.getInt("tmap_x", 1130);
        currentY = prefs.getInt("tmap_y", 70);

        // 1. 티맵 실행 화면 모드 (전체화면 vs 팝업창 모드 선택)
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        Switch switchTmapPopupMode = findViewById(R.id.switchTmapPopupMode);
        TextView tvTmapModeDescription = findViewById(R.id.tvTmapModeDescription);
        View layoutTmapPopupSliders = findViewById(R.id.layoutTmapPopupSliders);

        boolean isPopup = prefs.getBoolean("tmap_popup_enabled", true);
        if (switchTmapPopupMode != null) {
            switchTmapPopupMode.setChecked(isPopup);
            updateTmapModeUI(isPopup, tvTmapModeDescription, layoutTmapPopupSliders);

            switchTmapPopupMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("tmap_popup_enabled", isChecked).apply();
                updateTmapModeUI(isChecked, tvTmapModeDescription, layoutTmapPopupSliders);
                Toast.makeText(this, isChecked ? "🚗 티맵 실행 모드: [팝업창(플로팅)]으로 설정됨" : "🚗 티맵 실행 모드: [전체화면(Full Screen)]으로 설정됨", Toast.LENGTH_SHORT).show();
            });
        }

        // 2. 티맵 팝업창 크기/위치 슬라이더 바인딩 (기본값: 우측 절반 팝업창 1050, 50, 840, 1100)
        setupSeekBar(R.id.seekTmapPopupX, R.id.tvTmapPopupX, "tmap_popup_x", prefs.getInt("tmap_popup_x", 1050), " px");
        setupStepButton(R.id.btnTmapPopupXMinus1, R.id.seekTmapPopupX, -1);
        setupStepButton(R.id.btnTmapPopupXPlus1, R.id.seekTmapPopupX, 1);

        setupSeekBar(R.id.seekTmapPopupY, R.id.tvTmapPopupY, "tmap_popup_y", prefs.getInt("tmap_popup_y", 50), " px");
        setupStepButton(R.id.btnTmapPopupYMinus1, R.id.seekTmapPopupY, -1);
        setupStepButton(R.id.btnTmapPopupYPlus1, R.id.seekTmapPopupY, 1);

        setupSeekBar(R.id.seekTmapPopupW, R.id.tvTmapPopupW, "tmap_popup_w", prefs.getInt("tmap_popup_w", 840), " px");
        setupStepButton(R.id.btnTmapPopupWMinus1, R.id.seekTmapPopupW, -1);
        setupStepButton(R.id.btnTmapPopupWPlus1, R.id.seekTmapPopupW, 1);

        setupSeekBar(R.id.seekTmapPopupH, R.id.tvTmapPopupH, "tmap_popup_h", prefs.getInt("tmap_popup_h", 1100), " px");
        setupStepButton(R.id.btnTmapPopupHMinus1, R.id.seekTmapPopupH, -1);
        setupStepButton(R.id.btnTmapPopupHPlus1, R.id.seekTmapPopupH, 1);

        // 3. 티맵 매크로 좌표 / 클릭 횟수 / 클릭 간격 슬라이더 및 1단위 세부 조절 버튼 연결
        setupSeekBar(R.id.seekTmapX, R.id.tvTmapX, "tmap_x", currentX, " px");
        setupStepButton(R.id.btnTmapXMinus1, R.id.seekTmapX, -1);
        setupStepButton(R.id.btnTmapXPlus1, R.id.seekTmapX, 1);

        setupSeekBar(R.id.seekTmapY, R.id.tvTmapY, "tmap_y", currentY, " px");
        setupStepButton(R.id.btnTmapYMinus1, R.id.seekTmapY, -1);
        setupStepButton(R.id.btnTmapYPlus1, R.id.seekTmapY, 1);

        setupSeekBar(R.id.seekTmapMacroCount, R.id.tvTmapMacroCount, "tmap_macro_count", prefs.getInt("tmap_macro_count", 6), " 회");
        setupStepButton(R.id.btnTmapMacroCountMinus1, R.id.seekTmapMacroCount, -1);
        setupStepButton(R.id.btnTmapMacroCountPlus1, R.id.seekTmapMacroCount, 1);

        setupSeekBar(R.id.seekTmapMacroInterval, R.id.tvTmapMacroInterval, "tmap_macro_interval", prefs.getInt("tmap_macro_interval", 3), " 초");
        setupStepButton(R.id.btnTmapMacroIntervalMinus1, R.id.seekTmapMacroInterval, -1);
        setupStepButton(R.id.btnTmapMacroIntervalPlus1, R.id.seekTmapMacroInterval, 1);

        // 4. 플로팅 위젯 & 팝업 메뉴 슬라이더 및 1단위 세부 조절 버튼 연결
        setupSeekBar(R.id.seekFloatingY, R.id.tvFloatingY, "floating_y", prefs.getInt("floating_y", 132), " px");
        setupStepButton(R.id.btnFloatingYMinus1, R.id.seekFloatingY, -1);
        setupStepButton(R.id.btnFloatingYPlus1, R.id.seekFloatingY, 1);

        setupSeekBar(R.id.seekPopupY, R.id.tvPopupY, "popup_y", prefs.getInt("popup_y", 200), " px");
        setupStepButton(R.id.btnPopupYMinus1, R.id.seekPopupY, -1);
        setupStepButton(R.id.btnPopupYPlus1, R.id.seekPopupY, 1);

        setupSeekBar(R.id.seekAutoClose, R.id.tvAutoClose, "auto_close", prefs.getInt("auto_close", 5), " 초");
        setupStepButton(R.id.btnAutoCloseMinus1, R.id.seekAutoClose, -1);
        setupStepButton(R.id.btnAutoClosePlus1, R.id.seekAutoClose, 1);

        setupSeekBar(R.id.seekHandleClockSize, R.id.tvHandleClockSize, "handle_clock_size", prefs.getInt("handle_clock_size", 18), " sp");
        setupStepButton(R.id.btnHandleClockSizeMinus1, R.id.seekHandleClockSize, -1);
        setupStepButton(R.id.btnHandleClockSizePlus1, R.id.seekHandleClockSize, 1);

        setupSeekBar(R.id.seekPopupIconSize, R.id.tvPopupIconSize, "popup_icon_size", prefs.getInt("popup_icon_size", 80), " dp");
        setupStepButton(R.id.btnPopupIconSizeMinus1, R.id.seekPopupIconSize, -1);
        setupStepButton(R.id.btnPopupIconSizePlus1, R.id.seekPopupIconSize, 1);

        // 5. 비주얼 매크로 좌표 피커(조준경) 실행 버튼
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
                        tvX.setText(x + " px");
                    }
                    if (seekY != null && tvY != null) {
                        seekY.setProgress(y);
                        tvY.setText(y + " px");
                    }
                    updatePreviewCircle(x, y);
                });
            });
        }

        // 6. 배터리 최적화 제외 요청 버튼
        View btnBatteryOpt = findViewById(R.id.btnBatteryOptimization);
        if (btnBatteryOpt != null) {
            btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimizationExemption());
        }

        // 7. 설정 화면 내 즉시 테스트 클릭 버튼
        View btnTestClickInSettings = findViewById(R.id.btnTestClickInSettings);
        if (btnTestClickInSettings != null) {
            btnTestClickInSettings.setOnClickListener(v -> {
                if (MacroAccessibilityService.instance != null) {
                    MacroAccessibilityService.instance.performClick(currentX, currentY);
                    Toast.makeText(this, "좌표 (X: " + currentX + ", Y: " + currentY + ") 테스트 클릭 발사! 🎯", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "접근성 서비스가 꺼져 있습니다. 메인 화면 상단 🔴을 눌러 켜주세요!", Toast.LENGTH_LONG).show();
                }
            });
        }

        // 설정 화면 열릴 때 빨간색 미리보기 조준원 초기 표시
        showPreviewCircle(currentX, currentY);
    }

    private void updateTmapModeUI(boolean isPopup, TextView tvDesc, View slidersLayout) {
        if (tvDesc != null) {
            if (isPopup) {
                tvDesc.setText("현재: 팝업창(플로팅) 모드로 실행 중");
                tvDesc.setTextColor(Color.parseColor("#3DDC84")); // 초록색
            } else {
                tvDesc.setText("현재: 전체화면(Full Screen) 모드로 실행 중");
                tvDesc.setTextColor(Color.parseColor("#64B5F6")); // 하늘색
            }
        }
        if (slidersLayout != null) {
            slidersLayout.setVisibility(isPopup ? View.VISIBLE : View.GONE);
        }
    }

    // 설정 화면 위에 실시간 빨간색 조준원 오버레이 띄우기 (독립 실행 보장)
    private void showPreviewCircle(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }

        try {
            if (previewCircleView != null) {
                windowManager.removeView(previewCircleView);
                previewCircleView = null;
            }

            int size = 90;
            previewCircleView = new View(this);

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.parseColor("#80FF0000")); // 반투명 빨간색
            shape.setStroke(4, Color.RED); // 진한 빨간 테두리
            previewCircleView.setBackground(shape);

            int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            previewParams = new WindowManager.LayoutParams(
                    size, size,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );

            previewParams.gravity = Gravity.TOP | Gravity.START;
            previewParams.x = x - (size / 2);
            previewParams.y = y - (size / 2);

            windowManager.addView(previewCircleView, previewParams);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 슬라이더를 움직일 때 실시간으로 빨간 원의 위치를 화면 위에서 즉시 이동
    private void updatePreviewCircle(int x, int y) {
        currentX = x;
        currentY = y;
        if (previewCircleView != null && previewParams != null && windowManager != null) {
            try {
                int size = 90;
                previewParams.x = x - (size / 2);
                previewParams.y = y - (size / 2);
                windowManager.updateViewLayout(previewCircleView, previewParams);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            showPreviewCircle(x, y);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (previewCircleView != null && windowManager != null) {
            try {
                windowManager.removeView(previewCircleView);
                previewCircleView = null;
            } catch (Exception ignored) {}
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
        if (prefKey.equals("tmap_macro_count")) {
            textView.setText(initialValue == 0 ? "0 회 (매크로 꺼짐)" : initialValue + " 회");
        } else {
            textView.setText(initialValue + suffix);
        }

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (prefKey.equals("auto_close") && progress < 1) progress = 1;
                if (prefKey.equals("tmap_macro_interval") && progress < 1) progress = 1;

                if (prefKey.equals("tmap_macro_count")) {
                    textView.setText(progress == 0 ? "0 회 (매크로 꺼짐)" : progress + " 회");
                } else {
                    textView.setText(progress + suffix);
                }

                SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
                prefs.edit().putInt(prefKey, progress).apply();

                // 티맵 매크로 X, Y 슬라이더 조절 시 실시간 빨간 원 위치 즉각 갱신
                if (prefKey.equals("tmap_x")) {
                    updatePreviewCircle(progress, prefs.getInt("tmap_y", 70));
                } else if (prefKey.equals("tmap_y")) {
                    updatePreviewCircle(prefs.getInt("tmap_x", 1130), progress);
                }

                if (prefKey.equals("floating_y") || prefKey.equals("popup_y") ||
                        prefKey.equals("handle_clock_size") ||
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

    private void setupStepButton(int btnId, int seekId, int delta) {
        View btn = findViewById(btnId);
        SeekBar seekBar = findViewById(seekId);
        if (btn != null && seekBar != null) {
            btn.setOnClickListener(v -> {
                int newProgress = Math.max(0, Math.min(seekBar.getMax(), seekBar.getProgress() + delta));
                seekBar.setProgress(newProgress);
            });
        }
    }
}