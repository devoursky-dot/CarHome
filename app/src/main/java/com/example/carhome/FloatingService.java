package com.example.carhome;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FloatingService extends Service {

    public static FloatingService instance;

    private WindowManager windowManager;
    private View floatingView;
    private View handleBar;
    private View floatingContent;
    private Handler hideHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;
    private WindowManager.LayoutParams params;
    private Handler autoLaunchHandler = new Handler(Looper.getMainLooper());
    private Handler powerDisconnectDebounceHandler = new Handler(Looper.getMainLooper());
    private boolean isCountingDownToPowerOff = false;

    // 카홈 메인 홈 화면 진입 시 플로팅 위젯 중복 겹침 방지 가시성 제어
    public void setFloatingVisibility(boolean visible) {
        if (floatingView != null) {
            floatingView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // [수정 3] BrowserActivity가 포그라운드에 있는지 확인 (유튜브 시청 중 강제 종료 방지)
    private boolean isBrowserInForeground() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : processes) {
                        if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                && getPackageName().equals(proc.processName)) {
                            // 자기 앱이 포그라운드 → BrowserActivity 또는 MainActivity
                            // 추가로 최상위 Activity가 BrowserActivity인지 확인
                            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                            if (tasks != null && !tasks.isEmpty()) {
                                android.content.ComponentName topActivity = tasks.get(0).topActivity;
                                if (topActivity != null && topActivity.getClassName().contains("BrowserActivity")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // 실제 하드웨어 배터리 충전 상태 교차 검증 (순간 전압 강하/배터리 완충 깜빡임 필터링)
    private boolean isCurrentlyCharging() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                int plugged = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1);
                return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL ||
                        plugged > 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                // 1. 전원 차단 디바운스 타이머 즉시 취소
                powerDisconnectDebounceHandler.removeCallbacksAndMessages(null);

                boolean wasCountingDown = isCountingDownToPowerOff;
                isCountingDownToPowerOff = false;

                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                boolean isScreenOff = (pm != null && !pm.isInteractive());

                // [진짜 시동 ON일 때만 실행]
                // 화면이 꺼져 있던 상태(새로 시동 건 상태)이거나,
                // 직전에 전원이 끊겨서 실제로 카운트다운이 돌고 있었을 때만 화면을 켜고 티맵 자동 실행
                if (isScreenOff || wasCountingDown) {
                    // [문제 B 수정] 브라우저(유튜브) 사용 중이면 티맵 강제 실행을 차단하여 시청 중단 방지
                    if (isBrowserInForeground()) {
                        Toast.makeText(context, "⚡ 전원 복구됨 (브라우저 사용 중이므로 티맵 자동 실행을 건너뜁니다)", Toast.LENGTH_SHORT).show();
                    } else {
                        if (isScreenOff && pm != null) {
                            @SuppressWarnings("deprecation")
                            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                    "CarHome:PowerWakeLock"
                            );
                            wakeLock.acquire(3000);
                        }

                        Intent mainIntent = new Intent(context, MainActivity.class);
                        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(mainIntent);

                        autoLaunchHandler.removeCallbacksAndMessages(null);
                        autoLaunchHandler.postDelayed(() -> {
                            launchTmapSafeDriving();
                        }, 2000);
                    }
                }
                // 만약 이미 화면이 켜져서 브라우저나 유튜브 등을 사용 중이던 상태에서 0.5초 잠깐 전원이 튄 것은
                // 사용자를 절대 방해하지 않고 현재 보던 화면을 100% 그대로 유지합니다.
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                autoLaunchHandler.removeCallbacksAndMessages(null);
                isCountingDownToPowerOff = true;

                // 5초 대기 후 전원이 계속 들어오지 않으면 바로 모든 앱 닫기 실행
                powerDisconnectDebounceHandler.removeCallbacksAndMessages(null);
                powerDisconnectDebounceHandler.postDelayed(() -> {
                    isCountingDownToPowerOff = false;
                    if (!isCurrentlyCharging()) {
                        Toast.makeText(context, "전원 차단 5초 경과: 모든 앱 정리 후 5초 뒤 화면을 잠급니다 🧹💤", Toast.LENGTH_LONG).show();
                        cleanMemory();
                        executeCloseAllAppsAndLockMacro();
                    }
                }, 5000);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        if (floatingView != null && handleBar != null && floatingContent != null && params != null) {
            hideHandler.removeCallbacks(hideRunnable);
            floatingContent.setVisibility(View.GONE);
            handleBar.setVisibility(View.VISIBLE);

            SharedPreferences p = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = p.getInt("floating_x", 32);
            params.y = p.getInt("floating_y", 132);

            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (IllegalArgumentException e) {
                try { windowManager.addView(floatingView, params); } catch (Exception ex) { ex.printStackTrace(); }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 카홈 메인 홈 화면에 머물고 있다면 플로팅 위젯 숨김 유지
            floatingView.setVisibility(MainActivity.isMainActivityResumed ? View.GONE : View.VISIBLE);
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "carhome_floating_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "CarHome 상시 서비스",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("차량용 플로팅 독 위젯을 유지합니다.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CarHome 플로팅 런처 가동 중")
                .setContentText("차량용 플로팅 위젯 및 자동화 서비스가 활성화되어 있습니다.")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        startForeground(1001, builder.build());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        floatingView = inflater.inflate(R.layout.layout_floating, null);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);

        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = prefs.getInt("floating_x", 32);
        params.y = prefs.getInt("floating_y", 132);

        windowManager.addView(floatingView, params);

        // 카홈 메인 홈 화면이 열려 있다면 플로팅 위젯을 즉시 숨겨 UI 겹침 방지
        if (MainActivity.isMainActivityResumed) {
            floatingView.setVisibility(View.GONE);
        }

        handleBar = floatingView.findViewById(R.id.handleBar);
        floatingContent = floatingView.findViewById(R.id.floatingContent);

        hideRunnable = () -> {
            if (floatingView != null) {
                floatingView.setOnClickListener(null);
            }
            floatingContent.setVisibility(View.GONE);
            handleBar.setVisibility(View.VISIBLE);

            SharedPreferences p = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = p.getInt("floating_x", 32);
            params.y = p.getInt("floating_y", 132);
            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (Exception ignored) {}
        };

        // 플로팅 핸들바 터치: 1.5초 롱클릭 시 이동 모드 활성화 (테두리 굵어짐 + 진동), 일반 탭은 메뉴 토글
        handleBar.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isMoveMode = false;
            private final Handler longPressHandler = new Handler(Looper.getMainLooper());
            private final Runnable longPressRunnable = new Runnable() {
                @Override
                public void run() {
                    isMoveMode = true;
                    // 1. 4dp 굵기의 밝은 네온 테두리 드로어블로 변경하여 "이동 가능" 시각화
                    handleBar.setBackgroundResource(R.drawable.bg_floating_handle_moving);

                    // 2. 햅틱 진동 피드백 (손끝으로 즉각 체감)
                    try {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
                            } else {
                                vibrator.vibrate(60);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoveMode = false;

                        // 1.5초 롱클릭 타이머 시작
                        longPressHandler.postDelayed(longPressRunnable, 1500);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;

                        // 아직 이동 모드가 아닌데 손가락이 35px 이상 크게 벗어나면 롱클릭 취소
                        if (!isMoveMode && Math.hypot(dx, dy) > 35) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                        }

                        // 1.5초 롱클릭 후 '이동 모드'가 켜졌을 때만 위치 이동 수행!
                        if (isMoveMode) {
                            // 반드시 WRAP_CONTENT로 유지하여 Y축 세로 이동이 막히지 않도록 보장
                            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
                            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                            params.gravity = Gravity.BOTTOM | Gravity.END;

                            // Gravity.BOTTOM | Gravity.END 기준 위치 계산
                            int newY = (int) (initialY - dy);
                            int newX = (int) (initialX - dx);

                            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                            int maxY = Math.max(100, dm.heightPixels - 100);
                            int maxX = Math.max(100, dm.widthPixels - 100);

                            params.y = Math.max(0, Math.min(newY, maxY));
                            params.x = Math.max(0, Math.min(newX, maxX));
                            try {
                                windowManager.updateViewLayout(floatingView, params);
                            } catch (Exception ignored) {}
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacks(longPressRunnable);

                        if (isMoveMode) {
                            // 이동 모드 종료: 원래의 얇고 깔끔한 테두리로 즉시 복원
                            isMoveMode = false;
                            handleBar.setBackgroundResource(R.drawable.bg_floating_handle);

                            // 드래그 종료 시: 변경된 위치 영구 저장
                            SharedPreferences.Editor editor = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).edit();
                            editor.putInt("floating_y", params.y);
                            editor.putInt("floating_x", params.x);
                            editor.apply();
                        } else {
                            // 1.5초 미만 가벼운 탭(클릭): 100% 깔끔하게 플로팅 퀵 액션 메뉴 열기!
                            showFloatingMenu();
                        }
                        return true;
                }
                return false;
            }
        });

        ImageView btnTmap = floatingView.findViewById(R.id.btnFloatingTmap);
        ImageView btnVideo = floatingView.findViewById(R.id.btnFloatingVideo);
        ImageView btnBrave = floatingView.findViewById(R.id.btnFloatingBrave);
        ImageView btnAllApps = floatingView.findViewById(R.id.btnFloatingAllApps);
        ImageView btnClean = floatingView.findViewById(R.id.btnFloatingClean);

        setAppIcon(btnTmap, "com.skt.tmap.ku");
        setAppIcon(btnVideo, "com.samsung.android.videolist");
        setAppIcon(btnBrave, "com.android.chrome");

        btnTmap.setOnClickListener(v -> {
            launchAppFullScreen("com.skt.tmap.ku");
            hideHandler.post(hideRunnable);
        });

        btnVideo.setOnClickListener(v -> {
            launchAppFullScreen("com.samsung.android.videolist");
            hideHandler.post(hideRunnable);
        });

        btnBrave.setOnClickListener(v -> {
            launchAppFullScreen("com.android.chrome");
            hideHandler.post(hideRunnable);
        });

        if (btnAllApps != null) {
            btnAllApps.setOnClickListener(v -> {
                launchMainActivityFullScreen();
                hideHandler.post(hideRunnable);
            });
        }

        btnClean.setOnClickListener(v -> {
            cleanMemory();
            hideHandler.post(hideRunnable);
        });

        applySizesToViews(prefs);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerReceiver, filter);
    }

    private void showFloatingMenu() {
        if (handleBar != null) handleBar.setVisibility(View.GONE);
        if (floatingContent != null) {
            floatingContent.setVisibility(View.VISIBLE);
            // 팝업 메뉴 카드 내부 클릭은 바깥 닫기 이벤트로 넘어가지 않도록 방어
            floatingContent.setOnClickListener(v -> {});
        }

        // 팝업 표시 시: 화면 전체 크기로 확장하여 바깥 영역 터치 감지 가능하게 함
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.FILL;
        params.x = 0;
        params.y = 0;

        // 팝업 메뉴 카드의 크기 및 위치(하단 마진) 동적 배치
        if (floatingContent != null) {
            ViewGroup.LayoutParams lp = floatingContent.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams fParams = (FrameLayout.LayoutParams) lp;
                fParams.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
                fParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                fParams.bottomMargin = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("popup_y", 200);
                floatingContent.setLayoutParams(fParams);
            }
        }

        // 팝업 바깥쪽 아무 빈 공간 터치 시: 5초 대기 없이 즉시 닫기!
        if (floatingView != null) {
            floatingView.setOnClickListener(v -> {
                hideHandler.removeCallbacks(hideRunnable);
                hideRunnable.run();
            });
        }

        try {
            windowManager.updateViewLayout(floatingView, params);
        } catch (Exception ignored) {}

        hideHandler.removeCallbacks(hideRunnable);
        int autoCloseSec = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("auto_close", 5);
        hideHandler.postDelayed(hideRunnable, autoCloseSec * 1000L);
    }

    private void applySizesToViews(SharedPreferences prefs) {
        int clockSize = prefs.getInt("handle_clock_size", 18);
        TextView handleTime = floatingView.findViewById(R.id.handleTime);
        if (handleTime != null) {
            handleTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockSize);
        }

        TextView handleDate = floatingView.findViewById(R.id.handleDate);
        if (handleDate != null) {
            handleDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(14, (int) (clockSize * 0.82f)));
        }

        int iconSizePx = (int) (prefs.getInt("popup_icon_size", 80) * getResources().getDisplayMetrics().density);
        updateIconSize(floatingView.findViewById(R.id.btnFloatingTmap), iconSizePx);
        updateIconSize(floatingView.findViewById(R.id.btnFloatingVideo), iconSizePx);
        updateIconSize(floatingView.findViewById(R.id.btnFloatingBrave), iconSizePx);
        updateIconSize(floatingView.findViewById(R.id.btnFloatingAllApps), iconSizePx);
        updateIconSize(floatingView.findViewById(R.id.btnFloatingClean), iconSizePx);
        populateChannelButtons();
    }

    private void updateIconSize(View v, int sizePx) {
        if (v != null && v.getLayoutParams() != null) {
            v.getLayoutParams().width = sizePx;
            v.getLayoutParams().height = sizePx;
            v.requestLayout();
        }
    }

    private void cleanMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(0);

        ActivityManager.MemoryInfo beforeMem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(beforeMem);

        // [1. 절대 강제 종료하면 안 되는 필수 보호 화이트리스트]
        // [수정 4] WebView 렌더러 프로세스 및 핵심 필수 서비스 보호
        Set<String> protectedWhitelist = new HashSet<>(Arrays.asList(
                getPackageName(),
                "com.skt.tmap.ku",
                "com.android.systemui",
                "com.android.launcher3",
                "com.samsung.android.honeyboard",
                "com.google.android.inputmethod.korean",
                "com.google.android.inputmethod.latin",
                "com.android.phone",
                "com.sec.imsservice",
                "com.android.bluetooth",
                "com.sec.location.nsflp2",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.google.android.webview",
                "com.android.webview"
        ));

        // [2. 메모리를 많이 먹는 불필요 백그라운드 앱 우선 타겟팅]
        String[] aggressiveTargets = {
                "com.android.vending",                  // 구글 플레이 스토어 (약 200MB)
                "com.lguplus.appstore",                // U+ 스토어 (약 80MB)
                "com.skt.skaf.OA00018282",             // SKT 원스토어 서비스 (약 21MB)
                "com.skt.skaf.OA00412131",             // 원스토어 메인
                "com.samsung.android.video",           // 삼성 비디오 캐시 (약 31MB)
                "com.sec.android.app.launcher",        // 삼성 기본 런처
                "com.android.settings",                // 설정 앱 캐시
                "com.android.chrome",                  // 크롬 브라우저 캐시
                "com.google.android.projection.gearhead", // 안드로이드 오토
                "com.sktelecom.smartcard.SmartcardService",
                "com.samsung.android.mobileservice",
                "com.sec.android.diagmonagent",
                "com.samsung.cmh",
                "com.samsung.android.homemode",
                "com.samsung.android.lool",
                "com.samsung.android.sm.devicesecurity",
                "com.samsung.android.sm.policy",
                "com.sec.android.app.sbrowser",
                "com.brave.browser",
                "com.samsung.android.game.gamehome",
                "com.samsung.android.game.gametools",
                "com.samsung.android.bixby.agent",
                "com.samsung.android.bixby.service"
        };

        for (String targetPkg : aggressiveTargets) {
            try {
                am.killBackgroundProcesses(targetPkg);
            } catch (Exception ignored) {}
        }

        // [3. 화이트리스트를 제외한 모든 설치된 서드파티 앱 백그라운드 프로세스 정리]
        int cleanedCount = 0;
        for (ApplicationInfo packageInfo : packages) {
            String pkg = packageInfo.packageName;
            if (!protectedWhitelist.contains(pkg)) {
                try {
                    am.killBackgroundProcesses(pkg);
                    cleanedCount++;
                } catch (Exception ignored) {}
            }
        }

        final int totalCleaned = cleanedCount;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ActivityManager.MemoryInfo afterMem = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(afterMem);
            long freedMem = afterMem.availMem - beforeMem.availMem;
            long availMb = afterMem.availMem / (1024 * 1024);

            if (freedMem > 0) {
                long freedMb = freedMem / (1024 * 1024);
                Toast.makeText(this, "🧹 불필요한 백그라운드 앱 정리 완료!\n[ +" + freedMb + "MB 램 확보 ] (여유 램: " + availMb + "MB)", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "🧹 필수 앱(티맵/CarHome 등) 제외 " + totalCleaned + "개 백그라운드 정리 완료!\n(여유 램: " + availMb + "MB)", Toast.LENGTH_SHORT).show();
            }
        }, 600);
    }

    private void setAppIcon(ImageView imageView, String packageName) {
        AppLauncher.setAppIcon(getPackageManager(), imageView, packageName);
    }

    // 앱을 전체화면으로 단독 실행
    private void launchAppFullScreen(String packageName) {
        AppLauncher.launchApp(this, packageName);
    }

    // 채널 클릭 시 유튜브 전용앱 실행 (재생 중이어도 즉시 해당 채널로 전환)
    private void launchYoutubeFullScreen(String url, String name) {
        String displayName = (name != null ? name.replace("\n", " ") : "유튜브");
        Toast.makeText(this, "📺 " + displayName + " 이동", Toast.LENGTH_SHORT).show();

        if (BrowserActivity.currentInstance != null && !BrowserActivity.currentInstance.isFinishing()) {
            BrowserActivity.currentInstance.openChannelUrl(url);
            Intent intent = new Intent(this, BrowserActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, BrowserActivity.class);
            intent.putExtra("url", url);
            intent.putExtra("channel_name", name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
    }

    private void launchMainActivityFullScreen() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(mainIntent);
    }

    // 티맵 안심주행 직행
    private void launchTmapSafeDriving() {
        AppLauncher.launchTmapSafeDriving(this);
    }

    private void executeCloseAllAppsAndLockMacro() {
        if (MacroAccessibilityService.instance != null) {
            MacroAccessibilityService.instance.closeAllRecentAppsAndLock(true);
        }
    }

    private void populateChannelButtons() {
        LinearLayout channelContainer = floatingView.findViewById(R.id.channelButtonContainer);
        if (channelContainer == null) return;
        channelContainer.removeAllViews();

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        int iconSizePx = (int) (prefs.getInt("popup_icon_size", 80) * getResources().getDisplayMetrics().density);

        List<ChannelManager.ChannelItem> channels = ChannelManager.loadChannels(this);

        for (int i = 0; i < channels.size(); i++) {
            final int index = i;
            ChannelManager.ChannelItem item = channels.get(i);

            TextView btnChannel = new TextView(this);
            btnChannel.setText(ChannelManager.formatChannelName(item.name));
            btnChannel.setTextColor(Color.WHITE);
            btnChannel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btnChannel.setGravity(Gravity.CENTER);
            btnChannel.setBackgroundResource(R.drawable.bg_dock_btn);
            btnChannel.setClickable(true);
            btnChannel.setFocusable(true);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            params.setMarginEnd(16);
            btnChannel.setLayoutParams(params);

            btnChannel.setOnClickListener(v -> {
                launchYoutubeFullScreen(item.url, item.name);
                hideHandler.post(hideRunnable);
            });

            btnChannel.setOnLongClickListener(v -> {
                showDeleteConfirmDialog(item.name, index, channels);
                return true;
            });

            channelContainer.addView(btnChannel);
        }

        // [+] 새 채널 추가 버튼
        TextView btnAdd = new TextView(this);
        btnAdd.setText("＋\n추가");
        btnAdd.setTextColor(Color.parseColor("#3DDC84"));
        btnAdd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnAdd.setGravity(Gravity.CENTER);
        btnAdd.setBackgroundResource(R.drawable.bg_dock_btn);
        btnAdd.setClickable(true);
        btnAdd.setFocusable(true);

        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
        btnAdd.setLayoutParams(addParams);

        btnAdd.setOnClickListener(v -> showAddChannelDialog(channels));

        channelContainer.addView(btnAdd);
    }

    private void showAddChannelDialog(List<ChannelManager.ChannelItem> channels) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("새 유튜브 채널 바로가기 추가");

        LinearLayout layout = new LinearLayout(contextThemeWrapper);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText etName = new EditText(contextThemeWrapper);
        etName.setHint("채널/방송 이름 (예: YTN)");
        layout.addView(etName);

        final EditText etUrl = new EditText(contextThemeWrapper);
        etUrl.setHint("유튜브 링크 (URL)");
        layout.addView(etUrl);

        builder.setView(layout);

        builder.setPositiveButton("추가", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            String url = etUrl.getText().toString().trim();

            if (!name.isEmpty() && !url.isEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                channels.add(new ChannelManager.ChannelItem(name, url));
                ChannelManager.saveChannels(this, channels);
                populateChannelButtons();
                Toast.makeText(this, "'" + name + "' 채널이 추가되었습니다.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "이름과 주소를 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("취소", null);

        AlertDialog dialog = builder.create();
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(layoutFlag);
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.show();
    }

    private void showDeleteConfirmDialog(String name, int index, List<ChannelManager.ChannelItem> channels) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("채널 삭제");
        builder.setMessage("'" + name + "' 채널을 삭제하시겠습니까?");
        builder.setPositiveButton("삭제", (dialog, which) -> {
            if (index >= 0 && index < channels.size()) {
                channels.remove(index);
                ChannelManager.saveChannels(this, channels);
                populateChannelButtons();
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("취소", null);

        AlertDialog dialog = builder.create();
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(layoutFlag);
        }
        dialog.show();
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
        if (autoLaunchHandler != null) autoLaunchHandler.removeCallbacksAndMessages(null);
        if (powerDisconnectDebounceHandler != null) powerDisconnectDebounceHandler.removeCallbacksAndMessages(null);
        if (powerReceiver != null) { try { unregisterReceiver(powerReceiver); } catch (Exception e) {} }
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);
        if (floatingView != null) { try { windowManager.removeView(floatingView); } catch (Exception ignored) {} }
    }
}