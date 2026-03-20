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
import android.view.accessibility.AccessibilityNodeInfo;
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

    // 시스템 UI(모두 닫기 등)가 너무 빠른 터치를 무시하는 것을 방지하기 위한 '사람 손가락' 속도의 클릭
    public void performHumanClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 누르고 있는 시간을 100ms -> 250ms로 늘려서 확실하게 '클릭'으로 인식시킴
        GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 250)).build();
        dispatchGesture(gestureDescription, null, null);

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

        // 2. 최근 앱 화면 전환 애니메이션을 고려하여 넉넉하게 2.5초(2500ms) 대기
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Toast.makeText(this, "매크로: '모두 닫기' 버튼 탐색 중... 🔍", Toast.LENGTH_SHORT).show();
            
            boolean clicked = false;
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            
            if (rootNode != null) {
                // 화면 전체에서 "모두 닫기"라는 글자를 가진 버튼(노드)을 싹 다 검색합니다.
                java.util.List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText("모두 닫기");
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK); // 시스템 차원에서 버튼을 강제 클릭!
                        clicked = true;
                        break;
                    } else if (node.getParent() != null && node.getParent().isClickable()) {
                        node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK); // 글자의 부모 영역이 버튼인 경우
                        clicked = true;
                        break;
                    }
                }
            }
            
            if (clicked) {
                Toast.makeText(this, "매크로: 모두 닫기 완료! ✨", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "모두 닫기 버튼을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
        }, 3500); // 1.5초 -> 2.5초로 연장
    }
}