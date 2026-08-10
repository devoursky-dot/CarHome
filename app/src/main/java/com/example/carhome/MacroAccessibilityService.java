package com.example.carhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class MacroAccessibilityService extends AccessibilityService {

    public static MacroAccessibilityService instance;
    private Handler macroHandler = new Handler(Looper.getMainLooper());
    private View activeIndicatorView = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Toast.makeText(this, "CarHome 접근성 매크로 연결됨 🟢", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        if (macroHandler != null) {
            macroHandler.removeCallbacksAndMessages(null);
        }
        return super.onUnbind(intent);
    }

    // 정확하고 빠른 탭(클릭) 제스처 실행 (안드로이드 표준 클릭 인식 시간 80ms)
    public void performClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 80ms 지속시간으로 정확한 '클릭(Tap)' 발생
        GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();

        dispatchGesture(gestureDescription, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
            }
        }, null);

        // 시각적 표적 원 표시 (0.6초간)
        showClickIndicator(x, y, 600);
    }

    public void performHumanClick(float x, float y) {
        performClick(x, y);
    }

    // 티맵 실행 시 지정된 시간에 확실하게 닫기/확인 버튼을 누르는 자동 매크로 스케줄러
    public void scheduleTmapMacro(float x, float y) {
        if (macroHandler == null) {
            macroHandler = new Handler(Looper.getMainLooper());
        }
        macroHandler.removeCallbacksAndMessages(null);

        // 5초, 10초, 15초, 20초, 30초 간격으로 연속 확인 클릭 발사 (로딩 지연 및 팝업 완벽 대응)
        int[] delays = {5000, 10000, 15000, 20000, 30000};

        for (int delay : delays) {
            macroHandler.postDelayed(() -> {
                performClick(x, y);
            }, delay);
        }

        macroHandler.postDelayed(() -> {
            Toast.makeText(this, "매크로: 티맵 안전주행 모드 확인 완료 🤖", Toast.LENGTH_SHORT).show();
        }, 30500);
    }

    // 화면 위에 시각적인 표적(빨간색 원)을 실시간으로 그려주는 메서드
    public void showClickIndicator(float x, float y, int durationMs) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        if (activeIndicatorView != null) {
            try {
                wm.removeView(activeIndicatorView);
            } catch (Exception ignored) {}
            activeIndicatorView = null;
        }

        View indicator = new View(this);
        int size = 90; // 표적 원 크기 (90픽셀)

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#70FF0000")); // 반투명 빨간색
        shape.setStroke(4, Color.parseColor("#FFFF0000")); // 진한 빨간색 테두리
        indicator.setBackground(shape);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (int) x - (size / 2);
        params.y = (int) y - (size / 2);

        try {
            wm.addView(indicator, params);
            activeIndicatorView = indicator;

            if (durationMs > 0) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (activeIndicatorView == indicator) {
                            wm.removeView(indicator);
                            activeIndicatorView = null;
                        }
                    } catch (Exception ignored) {}
                }, durationMs);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 최근 실행 앱 화면을 열고 모두 닫기 버튼을 클릭하는 매크로
    public void closeAllRecentApps() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Toast.makeText(this, "매크로: '모두 닫기' 버튼 탐색 중... 🔍", Toast.LENGTH_SHORT).show();

            boolean clicked = false;
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();

            if (rootNode != null) {
                String[] targetTexts = {"모두 닫기", "모두닫기", "모두 지우기", "모두지우기", "Clear all", "Close all", "지우기"};
                for (String targetText : targetTexts) {
                    List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(targetText);
                    if (nodes != null && !nodes.isEmpty()) {
                        for (AccessibilityNodeInfo node : nodes) {
                            if (node.isClickable()) {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                clicked = true;
                                break;
                            } else if (node.getParent() != null && node.getParent().isClickable()) {
                                node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                clicked = true;
                                break;
                            }
                        }
                    }
                    if (clicked) break;
                }
            }

            if (clicked) {
                Toast.makeText(this, "매크로: 모두 닫기 완료! ✨", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "모두 닫기 버튼을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
        }, 3500);
    }
}