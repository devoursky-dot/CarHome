package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class CoordinatePickerOverlay {

    private static CoordinatePickerOverlay instance;
    private WindowManager windowManager;
    private View overlayView;
    private ImageView imgCrosshair;
    private TextView tvCoordinates;
    private Context context;

    private int currentX = 1130;
    private int currentY = 70;

    public interface OnCoordinateSavedListener {
        void onCoordinateSaved(int x, int y);
    }

    private OnCoordinateSavedListener listener;

    public static synchronized CoordinatePickerOverlay getInstance(Context context) {
        if (instance == null) {
            instance = new CoordinatePickerOverlay(context.getApplicationContext());
        }
        return instance;
    }

    private CoordinatePickerOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show(OnCoordinateSavedListener listener) {
        this.listener = listener;

        if (overlayView != null) {
            dismiss();
        }

        // 1. 티맵 앱을 전면에 띄워서 사용자가 티맵 화면을 보면서 조준할 수 있도록 실행
        try {
            Intent tmapIntent = context.getPackageManager().getLaunchIntentForPackage("com.skt.tmap.ku");
            if (tmapIntent != null) {
                tmapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(tmapIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        overlayView = inflater.inflate(R.layout.layout_coordinate_picker, null);

        SharedPreferences prefs = context.getSharedPreferences("CarHomePrefs", Context.MODE_PRIVATE);
        currentX = prefs.getInt("tmap_x", 1130);
        currentY = prefs.getInt("tmap_y", 70);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        imgCrosshair = overlayView.findViewById(R.id.imgCrosshairTarget);
        tvCoordinates = overlayView.findViewById(R.id.tvCoordinates);
        View btnTestClick = overlayView.findViewById(R.id.btnTestClick);
        View btnSave = overlayView.findViewById(R.id.btnSaveCoordinates);
        View btnClose = overlayView.findViewById(R.id.btnClosePicker);

        updateCoordinateText();

        // 초기 위치 설정
        overlayView.post(() -> updateCrosshairViewPosition());

        // 조준경 손가락 드래그 터치 리스너
        imgCrosshair.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;

                        v.setX(newX);
                        v.setY(newY);

                        // 중심 픽셀 절대 좌표 계산
                        int centerX = (int) (newX + (v.getWidth() / 2.0f));
                        int centerY = (int) (newY + (v.getHeight() / 2.0f));

                        currentX = Math.max(0, centerX);
                        currentY = Math.max(0, centerY);

                        updateCoordinateText();
                        return true;

                    case MotionEvent.ACTION_UP:
                        return true;
                }
                return false;
            }
        });

        // 1px 정밀 미세 조정 화살표 버튼
        View btnNudgeLeft1 = overlayView.findViewById(R.id.btnNudgeLeft1);
        View btnNudgeUp1 = overlayView.findViewById(R.id.btnNudgeUp1);
        View btnNudgeDown1 = overlayView.findViewById(R.id.btnNudgeDown1);
        View btnNudgeRight1 = overlayView.findViewById(R.id.btnNudgeRight1);

        if (btnNudgeLeft1 != null) btnNudgeLeft1.setOnClickListener(v -> nudge(-1, 0));
        if (btnNudgeRight1 != null) btnNudgeRight1.setOnClickListener(v -> nudge(1, 0));
        if (btnNudgeUp1 != null) btnNudgeUp1.setOnClickListener(v -> nudge(0, -1));
        if (btnNudgeDown1 != null) btnNudgeDown1.setOnClickListener(v -> nudge(0, 1));

        // 10px 이동 화살표 버튼
        View btnNudgeLeft10 = overlayView.findViewById(R.id.btnNudgeLeft10);
        View btnNudgeUp10 = overlayView.findViewById(R.id.btnNudgeUp10);
        View btnNudgeDown10 = overlayView.findViewById(R.id.btnNudgeDown10);
        View btnNudgeRight10 = overlayView.findViewById(R.id.btnNudgeRight10);

        if (btnNudgeLeft10 != null) btnNudgeLeft10.setOnClickListener(v -> nudge(-10, 0));
        if (btnNudgeRight10 != null) btnNudgeRight10.setOnClickListener(v -> nudge(10, 0));
        if (btnNudgeUp10 != null) btnNudgeUp10.setOnClickListener(v -> nudge(0, -10));
        if (btnNudgeDown10 != null) btnNudgeDown10.setOnClickListener(v -> nudge(0, 10));

        // 테스트 클릭
        btnTestClick.setOnClickListener(v -> performTestClick());

        // 저장
        btnSave.setOnClickListener(v -> {
            prefs.edit().putInt("tmap_x", currentX).putInt("tmap_y", currentY).apply();
            Toast.makeText(context, "좌표 (X: " + currentX + ", Y: " + currentY + ") 저장 완료! ✅", Toast.LENGTH_SHORT).show();
            if (this.listener != null) {
                this.listener.onCoordinateSaved(currentX, currentY);
            }
            dismiss();
        });

        // 닫기
        btnClose.setOnClickListener(v -> dismiss());

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "오버레이 표시 실패: 권한을 확인하세요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void nudge(int deltaX, int deltaY) {
        currentX = Math.max(0, currentX + deltaX);
        currentY = Math.max(0, currentY + deltaY);
        updateCoordinateText();
        updateCrosshairViewPosition();
    }

    private void updateCrosshairViewPosition() {
        if (imgCrosshair != null) {
            int halfW = imgCrosshair.getWidth() > 0 ? imgCrosshair.getWidth() / 2 : 40;
            int halfH = imgCrosshair.getHeight() > 0 ? imgCrosshair.getHeight() / 2 : 40;
            imgCrosshair.setX(currentX - halfW);
            imgCrosshair.setY(currentY - halfH);
        }
    }

    private void updateCoordinateText() {
        if (tvCoordinates != null) {
            tvCoordinates.setText("현재 조준 위치: X = " + currentX + ", Y = " + currentY);
        }
    }

    private void performTestClick() {
        if (MacroAccessibilityService.instance == null) {
            Toast.makeText(context, "접근성 서비스가 꺼져 있습니다. 설정에서 켜주세요! 🔴", Toast.LENGTH_LONG).show();
            return;
        }

        // 조준경이 터치를 가로채지 않도록 0.15초간 숨김 처리 후 클릭 발사
        if (imgCrosshair != null) imgCrosshair.setVisibility(View.INVISIBLE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (MacroAccessibilityService.instance != null) {
                MacroAccessibilityService.instance.performClick(currentX, currentY);
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (imgCrosshair != null) imgCrosshair.setVisibility(View.VISIBLE);
                Toast.makeText(context, "조준 위치 (X: " + currentX + ", Y: " + currentY + ") 테스트 클릭 발사! 🎯", Toast.LENGTH_SHORT).show();
            }, 300);
        }, 100);
    }

    public void dismiss() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                e.printStackTrace();
            }
            overlayView = null;
        }
    }
}
