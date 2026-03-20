package com.example.carhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class MacroAccessibilityService extends AccessibilityService {

    // 외부(FloatingService 등)에서 터치 명령을 내릴 수 있도록 스태틱(Static) 인스턴스 유지
    public static MacroAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // 서비스가 켜지면 안내 메시지를 띄웁니다.
        Toast.makeText(this, "접근성 매크로 준비 완료! 🤖", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 화면이 변할 때 시스템이 알려주는 곳입니다. 현재는 특정 글자 감지는 하지 않고 외부 명령(좌표 터치)만 대기합니다.
    }

    @Override
    public void onInterrupt() {
        // 시스템에 의해 서비스가 강제 중단되었을 때
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    // 전달받은 (x, y) 좌표를 0.1초 동안 콕! 누르는 제스처 실행 메서드
    public void performClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100)).build();
        dispatchGesture(gestureDescription, null, null);

        // 터치 지점에 반투명한 빨간색 원을 0.5초 동안 표시
        showClickIndicator(x, y);
    }

    // 화면 위에 시각적인 표적(원)을 그려주는 헬퍼 메서드
    private void showClickIndicator(float x, float y) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        View indicator = new View(this);
        int size = 100; // 표적 원의 크기 (100픽셀)

        // 빨간색 반투명 동그라미와 진한 테두리 모양 만들기
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#80FF0000")); // 반투명 빨간색
        shape.setStroke(5, Color.RED); // 두께 5의 진한 빨간색 테두리
        indicator.setBackground(shape);

        // 안드로이드 접근성 전용 오버레이 창으로 설정 (다른 앱들을 뚫고 무조건 최상단에 표시됨)
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        
        // x, y 좌표의 정중앙에 표적이 위치하도록 계산
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = (int) x - (size / 2);
        params.y = (int) y - (size / 2);

        try {
            wm.addView(indicator, params);
            
            // 0.5초(500ms) 뒤에 표적을 화면에서 지움
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { wm.removeView(indicator); } catch (Exception e) {}
            }, 500);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 최근 실행 앱 화면을 열고 모두 닫기 버튼을 클릭하는 매크로
    public void closeAllRecentApps() {
        // 1. 최근 실행 앱 화면(Recents) 띄우기
        performGlobalAction(GLOBAL_ACTION_RECENTS);

        // 2. 약간의 딜레이 후 '모두 닫기' 버튼 위치 터치
        // 최근 앱 화면이 뜨는 데 시간이 걸리므로 1~2초 대기
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // FIXME: 이 좌표(x, y)는 고객님의 기기 해상도와 '모두 닫기' 버튼 위치에 맞게 수정해야 합니다!
            // 앞서 안내해드린 adb shell wm size 명령어로 해상도를 확인하고 대략적인 위치를 찾아보세요.
            // 예: 하단 중앙에 있다면 (960f, 1100f) 등
            float closeAllX = 500f; 
            float closeAllY = 1000f; 
            
            performClick(closeAllX, closeAllY);
            
        }, 1500); // 1.5초(1500ms) 대기. 기기 속도에 따라 조절 필요
    }
}