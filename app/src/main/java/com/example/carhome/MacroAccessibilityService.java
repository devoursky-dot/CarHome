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
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class MacroAccessibilityService extends AccessibilityService {

    private static final String TAG = "MacroAccessibility";
    public static MacroAccessibilityService instance;
    private Handler macroHandler = new Handler(Looper.getMainLooper());
    private View activeIndicatorView = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "MacroAccessibilityService Connected successfully");
        Toast.makeText(this, "CarHome 접근성 매크로 준비 완료! 🟢", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "MacroAccessibilityService Interrupted");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        if (macroHandler != null) {
            macroHandler.removeCallbacksAndMessages(null);
        }
        return super.onUnbind(intent);
    }

    // 안드로이드 표준 제스처 클릭 (Path 길이 오류 완벽 수정: moveTo + lineTo)
    public void performClick(float x, float y) {
        if (x < 0 || y < 0) return;

        Path path = new Path();
        path.moveTo(x, y);
        // 안드로이드 제스처 시스템은 길이 0인 패스를 무시하므로, lineTo(x, y + 1)을 추가하여 완벽한 클릭 제스처 생성
        path.lineTo(x, y + 1);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 50ms 탭 제스처
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        GestureDescription gesture = builder.build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Gesture completed successfully at (" + x + ", " + y + ")");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "Gesture was cancelled at (" + x + ", " + y + ")");
            }
        }, null);

        Log.d(TAG, "dispatchGesture result: " + dispatched + " at (" + x + ", " + y + ")");

        // 클릭 위치에 시각적 표적 원 표시
        showClickIndicator(x, y, 600);
    }

    public void performHumanClick(float x, float y) {
        performClick(x, y);
    }

    // 티맵 실행 시 지정된 시간에 확실하게 닫기/확인 버튼을 누르는 자동 매크로 스케줄러
    public void scheduleTmapMacro(float x, float y) {
        scheduleTmapMacro(x, y, 6, 3);
    }

    public void scheduleTmapMacro(float x, float y, int count, int intervalSec) {
        if (macroHandler == null) {
            macroHandler = new Handler(Looper.getMainLooper());
        }
        macroHandler.removeCallbacksAndMessages(null);

        if (count <= 0) {
            Log.d(TAG, "Macro count is 0, skipping Tmap macro");
            return;
        }

        int intervalMs = Math.max(500, intervalSec * 1000);

        for (int i = 1; i <= count; i++) {
            final int currentAttempt = i;
            long delay = (long) i * intervalMs;
            macroHandler.postDelayed(() -> {
                Log.d(TAG, "Executing scheduled Tmap click #" + currentAttempt + "/" + count + " at (" + x + ", " + y + ")");
                performClick(x, y);
            }, delay);
        }

        macroHandler.postDelayed(() -> {
            Toast.makeText(this, "티맵 자동확인 매크로 완료! (" + count + "회 실행됨) 🤖", Toast.LENGTH_SHORT).show();
        }, (long) count * intervalMs + 500);
    }

    // 화면 위에 시각적인 표적(빨간색 원)을 실시간으로 그려주는 메서드
    public void showClickIndicator(float x, float y, int durationMs) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (activeIndicatorView != null) {
                    try {
                        wm.removeView(activeIndicatorView);
                    } catch (Exception ignored) {}
                    activeIndicatorView = null;
                }

                View indicator = new View(this);
                int size = 90; // 표적 원 크기

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
                Log.e(TAG, "Error showing click indicator", e);
            }
        });
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