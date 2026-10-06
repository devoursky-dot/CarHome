package com.example.carhome;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class MacroAccessibilityService extends AccessibilityService {

    private static final String TAG = "MacroAccessibility";
    public static MacroAccessibilityService instance;
    private Handler macroHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "MacroAccessibilityService Connected successfully");
        Toast.makeText(this, "CarHome 자동화 서비스 준비 완료! 🟢", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 창 상태 변경 이벤트 수신
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

    @Override
    public void onDestroy() {
        instance = null;
        if (macroHandler != null) {
            macroHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    // 화면 잠금(화면 끄기 / 절전 모드) 수행
    public void lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            boolean success = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            Log.d(TAG, "performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) result: " + success);
        }
    }

    // 최근 실행 앱 화면을 열고 모두 닫기 버튼을 클릭하는 매크로
    public void closeAllRecentApps() {
        closeAllRecentAppsAndLock(false);
    }

    // 최근 실행 앱 모두 닫기 및 화면 잠금(절전 모드) 연계 매크로 (AccessibilityNodeInfo 메모리 누수 방지 리사이클 적용)
    public void closeAllRecentAppsAndLock(boolean andLockScreen) {
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
                            } else if (node.getParent() != null && node.getParent().isClickable()) {
                                node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                clicked = true;
                            }
                            node.recycle();
                            if (clicked) break;
                        }
                    }
                    if (clicked) break;
                }
                rootNode.recycle();
            }

            if (clicked) {
                Toast.makeText(this, "매크로: 모두 닫기 완료! ✨", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "모두 닫기 버튼을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            }

            // 앱 닫기 완료 후 5초 대기 후 화면 잠금 (애니메이션 딜레이 방지 및 확실한 잠금 보장)
            if (andLockScreen) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                    if (bm != null && bm.isCharging()) {
                        Toast.makeText(this, "전원 복구됨: 화면 잠금을 취소합니다.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "💤 절전 모드: 화면을 잠급니다.", Toast.LENGTH_SHORT).show();
                        lockScreen();
                        // 1초 뒤 한번 더 잠금 (확인 사살)
                        new Handler(Looper.getMainLooper()).postDelayed(this::lockScreen, 1000);
                    }
                }, 5000);
            }
        }, 3000);
    }
}