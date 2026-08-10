package com.example.carhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
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

        setupSeekBar(R.id.seekFloatingY, R.id.tvFloatingY, "floating_y", prefs.getInt("floating_y", 132), " px");
        setupSeekBar(R.id.seekPopupY, R.id.tvPopupY, "popup_y", prefs.getInt("popup_y", 200), " px");
        setupSeekBar(R.id.seekAutoClose, R.id.tvAutoClose, "auto_close", prefs.getInt("auto_close", 5), " 초");
        setupSeekBar(R.id.seekTmapX, R.id.tvTmapX, "tmap_x", prefs.getInt("tmap_x", 1130), "");
        setupSeekBar(R.id.seekTmapY, R.id.tvTmapY, "tmap_y", prefs.getInt("tmap_y", 70), "");

        // 새로 추가된 크기 설정 3종 (직접 ID를 연결하여 완벽하게 작동하도록 수정)
        setupSeekBar(R.id.seekHandleClockSize, R.id.tvHandleClockSize, "handle_clock_size", prefs.getInt("handle_clock_size", 20), " sp");
        setupSeekBar(R.id.seekPopupClockSize, R.id.tvPopupClockSize, "popup_clock_size", prefs.getInt("popup_clock_size", 50), " sp");
        setupSeekBar(R.id.seekPopupIconSize, R.id.tvPopupIconSize, "popup_icon_size", prefs.getInt("popup_icon_size", 100), " dp");
    }

    // 슬라이더 초기화 및 실시간 저장 헬퍼 메서드
    private void setupSeekBar(int seekId, int tvId, String prefKey, int initialValue, String suffix) {
        SeekBar seekBar = findViewById(seekId);
        TextView textView = findViewById(tvId);

        seekBar.setProgress(initialValue);
        textView.setText(initialValue + suffix);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 최솟값 방어 (시간 설정일 경우 최소 1초)
                if (prefKey.equals("auto_close") && progress < 1) progress = 1;
                
                textView.setText(progress + suffix);
                
                SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
                prefs.edit().putInt(prefKey, progress).apply();

                // 높이 및 크기가 변경될 경우 실시간 피드백을 위해 플로팅 서비스로 무전 전송
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