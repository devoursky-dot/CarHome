package com.example.carhome;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ActivityOptions;
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
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Method;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
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

    private WindowManager windowManager;
    private View floatingView;
    private View handleBar;
    private View floatingContent;
    private Handler hideHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;
    private WindowManager.LayoutParams params;
    private Handler autoLaunchHandler = new Handler(Looper.getMainLooper());
    private Handler powerOffHandler = new Handler(Looper.getMainLooper());

    // TMAP 안심주행 전용 스마트 HUD 뷰 및 GPS / 사운드 경고 엔진
    private View hudView;
    private WindowManager.LayoutParams hudParams;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private int currentGpsSpeed = 0;
    private boolean isSafeDrivingActive = false;
    private int currentSpeedLimit = 0;
    private int currentDistance = 0;
    private String currentCameraType = "안심주행 중";
    private ToneGenerator toneGenerator;
    private long lastBeepTime = 0;

    private final BroadcastReceiver tmapHudReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.carhome.TMAP_HUD_UPDATE".equals(intent.getAction())) {
                isSafeDrivingActive = intent.getBooleanExtra("isSafeDriving", false);
                currentSpeedLimit = intent.getIntExtra("speedLimit", 0);
                currentDistance = intent.getIntExtra("distanceMeters", 0);
                String type = intent.getStringExtra("cameraType");
                if (type != null) currentCameraType = type;
                updateHudView();
            }
        }
    };

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.carhome.UPDATE_SETTINGS".equals(intent.getAction())) {
                SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
                String key = intent.getStringExtra("key");

                if ("popup_y".equals(key)) {
                    if (floatingContent != null) floatingContent.setVisibility(View.VISIBLE);
                    if (handleBar != null) handleBar.setVisibility(View.GONE);
                    params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
                    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    params.x = 0;
                    params.y = prefs.getInt("popup_y", 200);

                    hideHandler.removeCallbacks(hideRunnable);
                    hideHandler.postDelayed(hideRunnable, 3000);
                } else if ("floating_y".equals(key) || "handle_clock_size".equals(key)) {
                    if (floatingContent != null) floatingContent.setVisibility(View.GONE);
                    if (handleBar != null) handleBar.setVisibility(View.VISIBLE);
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    params.gravity = Gravity.BOTTOM | Gravity.END;
                    params.x = 32;
                    params.y = prefs.getInt("floating_y", 132);

                    applySizesToViews(prefs);
                } else if ("popup_clock_size".equals(key) || "popup_icon_size".equals(key)) {
                    if (floatingContent != null) floatingContent.setVisibility(View.VISIBLE);
                    if (handleBar != null) handleBar.setVisibility(View.GONE);
                    params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
                    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    params.x = 0;
                    params.y = prefs.getInt("popup_y", 200);

                    applySizesToViews(prefs);

                    hideHandler.removeCallbacks(hideRunnable);
                    hideHandler.postDelayed(hideRunnable, 3000);
                } else if ("tmap_hud_test".equals(key)) {
                    // 설정 화면에서 HUD 테스트 팝업 요청
                    isSafeDrivingActive = true;
                    currentSpeedLimit = 60;
                    currentDistance = 500;
                    currentCameraType = "🚨 과속단속 (테스트)";
                    currentGpsSpeed = 68;
                    updateHudView();
                } else if ("tmap_hud_enabled".equals(key)) {
                    updateHudView();
                }
                try {
                    windowManager.updateViewLayout(floatingView, params);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                powerOffHandler.removeCallbacksAndMessages(null);

                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    return;
                }

                if (pm != null && !pm.isInteractive()) {
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
                    Intent tmapIntent = getPackageManager().getLaunchIntentForPackage("com.skt.tmap.ku");
                    if (tmapIntent != null) {
                        tmapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(tmapIntent);
                        Toast.makeText(context, "티맵을 자동 실행합니다 🚗", Toast.LENGTH_SHORT).show();
                        executeTmapMacro();
                    }
                }, 2000);
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                Toast.makeText(context, "전원 차단 감지: 10초 뒤 모든 앱 정리 및 화면 잠금(절전)을 실행합니다 🧹💤", Toast.LENGTH_LONG).show();
                powerOffHandler.removeCallbacksAndMessages(null);
                powerOffHandler.postDelayed(() -> {
                    cleanMemory(); // 1. 백그라운드 배터리 소모 프로세스 & 캐시 일괄 청소
                    executeCloseAllAppsAndLockMacro(); // 2. 최근 앱 모두 닫기 & 화면 잠금(절전 모드) 전환
                }, 10000);
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

            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = 32;
            params.y = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("floating_y", 132);

            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (IllegalArgumentException e) {
                try { windowManager.addView(floatingView, params); } catch (Exception ex) { ex.printStackTrace(); }
            } catch (Exception e) {
                e.printStackTrace();
            }
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
            channel.setDescription("차량용 플로팅 위젯과 안심주행 HUD를 유지합니다.");
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
                .setContentTitle("CarHome 안심주행 HUD 가동 중")
                .setContentText("티맵 안심주행 과속 경고 및 플로팅 위젯이 활성화되어 있습니다.")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        startForeground(1001, builder.build());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();

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
        params.x = 32;
        params.y = prefs.getInt("floating_y", 132);

        windowManager.addView(floatingView, params);

        handleBar = floatingView.findViewById(R.id.handleBar);
        floatingContent = floatingView.findViewById(R.id.floatingContent);

        hideRunnable = () -> {
            floatingContent.setVisibility(View.GONE);
            handleBar.setVisibility(View.VISIBLE);

            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = 32;
            params.y = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("floating_y", 132);
            windowManager.updateViewLayout(floatingView, params);
        };

        handleBar.setOnClickListener(v -> {
            handleBar.setVisibility(View.GONE);
            floatingContent.setVisibility(View.VISIBLE);

            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.x = 0;
            params.y = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("popup_y", 200);
            windowManager.updateViewLayout(floatingView, params);

            hideHandler.removeCallbacks(hideRunnable);
            int autoCloseSec = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("auto_close", 5);
            hideHandler.postDelayed(hideRunnable, autoCloseSec * 1000L);
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
            launchApp("com.skt.tmap.ku");
            hideHandler.post(hideRunnable);
        });

        btnVideo.setOnClickListener(v -> {
            launchApp("com.samsung.android.videolist");
            hideHandler.post(hideRunnable);
        });

        btnBrave.setOnClickListener(v -> {
            launchApp("com.android.chrome");
            hideHandler.post(hideRunnable);
        });

        if (btnAllApps != null) {
            btnAllApps.setOnClickListener(v -> {
                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(mainIntent);
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

        IntentFilter settingsFilter = new IntentFilter("com.example.carhome.UPDATE_SETTINGS");
        IntentFilter tmapFilter = new IntentFilter("com.example.carhome.TMAP_HUD_UPDATE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(tmapHudReceiver, tmapFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
            registerReceiver(tmapHudReceiver, tmapFilter);
        }

        // TMAP 안심주행 HUD 뷰 및 GPS 속도 측정 초기화
        initTmapHudView();
        startGpsTracking();

        // TmapNotificationListener 직접 리스너 콜백 연결
        TmapNotificationListener.setUpdateListener((isActive, speedLimit, distance, cameraType, rawText) -> {
            new Handler(Looper.getMainLooper()).post(() -> {
                isSafeDrivingActive = isActive;
                currentSpeedLimit = speedLimit;
                currentDistance = distance;
                if (cameraType != null) currentCameraType = cameraType;
                updateHudView();
            });
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTmapHudView() {
        if (hudView != null) return;

        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        hudView = inflater.inflate(R.layout.layout_tmap_hud, null);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        hudParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        hudParams.gravity = Gravity.TOP | Gravity.START;
        hudParams.x = prefs.getInt("tmap_hud_x", 120);
        hudParams.y = prefs.getInt("tmap_hud_y", 50);

        hudView.setVisibility(View.GONE);

        try {
            windowManager.addView(hudView, hudParams);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 터치 드래그로 화면 어디든 자유롭게 이동 & 위치 저장
        hudView.setOnTouchListener(new View.OnTouchListener() {
            private float initialTouchX, initialTouchY;
            private int initialX, initialY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = hudParams.x;
                        initialY = hudParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10 || isMoving) {
                            isMoving = true;
                            hudParams.x = initialX + dx;
                            hudParams.y = initialY + dy;
                            try {
                                windowManager.updateViewLayout(hudView, hudParams);
                            } catch (Exception ignored) {}
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isMoving) {
                            prefs.edit().putInt("tmap_hud_x", hudParams.x).putInt("tmap_hud_y", hudParams.y).apply();
                        } else {
                            // 단순 탭 터치 시 티맵 전면 실행
                            launchApp("com.skt.tmap.ku");
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void updateHudView() {
        if (hudView == null) {
            initTmapHudView();
        }

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        boolean hudEnabled = prefs.getBoolean("tmap_hud_enabled", true);

        if (!hudEnabled || !isSafeDrivingActive) {
            if (hudView != null) hudView.setVisibility(View.GONE);
            return;
        }

        if (hudView.getVisibility() != View.VISIBLE) {
            hudView.setVisibility(View.VISIBLE);
        }

        TextView tvSpeedLimit = hudView.findViewById(R.id.tvHudSpeedLimit);
        TextView tvCurrentSpeed = hudView.findViewById(R.id.tvHudCurrentSpeed);
        TextView tvDistance = hudView.findViewById(R.id.tvHudDistance);
        TextView tvCameraType = hudView.findViewById(R.id.tvHudCameraType);

        if (tvCurrentSpeed != null) {
            tvCurrentSpeed.setText(String.valueOf(currentGpsSpeed));
        }

        if (tvSpeedLimit != null) {
            if (currentSpeedLimit > 0) {
                tvSpeedLimit.setText(String.valueOf(currentSpeedLimit));
                tvSpeedLimit.setVisibility(View.VISIBLE);
            } else {
                tvSpeedLimit.setVisibility(View.GONE);
            }
        }

        if (tvDistance != null) {
            if (currentDistance > 0) {
                tvDistance.setText("📍 " + currentDistance + "m");
            } else {
                tvDistance.setText("🧭 주행중");
            }
        }

        if (tvCameraType != null) {
            tvCameraType.setText(currentCameraType != null ? currentCameraType : "안심주행 중");
        }

        // 과속 판별 및 시각/청각 2중 경고
        boolean isOverSpeed = (currentSpeedLimit > 0 && currentGpsSpeed > currentSpeedLimit);

        if (isOverSpeed) {
            hudView.setBackgroundResource(R.drawable.bg_tmap_hud_warning);
            if (tvCurrentSpeed != null) tvCurrentSpeed.setTextColor(Color.parseColor("#FF1744"));

            long now = System.currentTimeMillis();
            if (now - lastBeepTime > 1200) {
                lastBeepTime = now;
                playLoudWarningBeep();
            }
        } else {
            hudView.setBackgroundResource(R.drawable.bg_tmap_hud_normal);
            if (tvCurrentSpeed != null) tvCurrentSpeed.setTextColor(Color.WHITE);
        }
    }

    private void playLoudWarningBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 350);
        } catch (Exception e) {
            try {
                ToneGenerator backup = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                backup.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            } catch (Exception ignored) {}
        }
    }

    private void startGpsTracking() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null && location.hasSpeed()) {
                    currentGpsSpeed = Math.round(location.getSpeed() * 3.6f);
                } else {
                    currentGpsSpeed = 0;
                }
                if (isSafeDrivingActive && hudView != null && hudView.getVisibility() == View.VISIBLE) {
                    updateHudView();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0.5f, locationListener);
            }
        } catch (SecurityException ignored) {}
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
        Set<String> protectedWhitelist = new HashSet<>(Arrays.asList(
                getPackageName(),
                "com.skt.tmap.ku",
                "com.android.systemui",
                "com.sec.android.app.launcher",
                "com.android.launcher3",
                "com.samsung.android.honeyboard",
                "com.google.android.inputmethod.korean",
                "com.google.android.inputmethod.latin",
                "com.android.phone",
                "com.sec.imsservice",
                "com.android.bluetooth",
                "com.sec.location.nsflp2",
                "com.google.android.gms",
                "com.google.android.gsf"
        ));

        // [2. 메모리를 많이 먹는 불필요 백그라운드 앱 우선 타겟팅]
        String[] aggressiveTargets = {
                "com.android.chrome",
                "com.google.android.projection.gearhead",
                "com.skt.skaf.OA00412131",
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

        // 앱 내부 메모리 가비지 컬렉션(GC) 즉시 수행
        System.gc();
        Runtime.getRuntime().gc();

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
        try {
            Drawable icon = getPackageManager().getApplicationIcon(packageName);
            imageView.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            imageView.setImageResource(android.R.drawable.sym_def_app_icon);
        }
    }

    private void launchApp(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            if ("com.skt.tmap.ku".equals(packageName)) {
                SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
                boolean popupEnabled = prefs.getBoolean("tmap_popup_enabled", true);

                if (popupEnabled) {
                    int left = prefs.getInt("tmap_popup_x", 1050);
                    int top = prefs.getInt("tmap_popup_y", 50);
                    int width = prefs.getInt("tmap_popup_w", 840);
                    int height = prefs.getInt("tmap_popup_h", 1100);

                    Rect bounds = new Rect(left, top, left + width, top + height);

                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchBounds(bounds);

                    try {
                        Method method = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                        method.invoke(options, 5); // WINDOWING_MODE_FREEFORM = 5
                    } catch (Exception ignored) {}

                    startActivity(intent, options.toBundle());
                } else {
                    startActivity(intent);
                }

                executeTmapMacro();
            } else {
                startActivity(intent);
            }
        } else {
            Toast.makeText(this, "해당 앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeTmapMacro() {
        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        int macroCount = prefs.getInt("tmap_macro_count", 6);
        if (macroCount <= 0) {
            return;
        }

        float tmapX = prefs.getInt("tmap_x", 1130);
        float tmapY = prefs.getInt("tmap_y", 70);
        int intervalSec = prefs.getInt("tmap_macro_interval", 3);

        if (MacroAccessibilityService.instance != null) {
            Toast.makeText(this, "티맵 안전주행 자동확인 매크로 가동 중... 🤖 (" + macroCount + "회)", Toast.LENGTH_SHORT).show();
            MacroAccessibilityService.instance.scheduleTmapMacro(tmapX, tmapY, macroCount, intervalSec);
        } else {
            Toast.makeText(this, "접근성 서비스가 꺼져 있습니다. 권한을 확인하세요!", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeCloseAllAppsAndLockMacro() {
        if (MacroAccessibilityService.instance != null) {
            MacroAccessibilityService.instance.closeAllRecentAppsAndLock(true);
        }
    }

    private void populateChannelButtons() {
        LinearLayout container = floatingView.findViewById(R.id.channelButtonContainer);
        if (container == null) return;
        container.removeAllViews();

        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        String jsonString = prefs.getString("brave_channels", null);

        List<String> channelNames = new ArrayList<>();
        List<String> channelUrls = new ArrayList<>();

        try {
            if (jsonString == null) {
                channelNames.add("운전용 음악 재생"); channelUrls.add("https://www.youtube.com/results?search_query=driving+music+playlist");
                channelNames.add("실시간 YTN 뉴스"); channelUrls.add("https://www.youtube.com/watch?v=GoXhAEYGj1Q");
                channelNames.add("침착맨"); channelUrls.add("https://www.youtube.com/@chim_tube");
                channelNames.add("슈카월드"); channelUrls.add("https://www.youtube.com/@syukaworld");
                saveChannels(channelNames, channelUrls);
            } else {
                JSONArray jsonArray = new JSONArray(jsonString);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    channelNames.add(obj.getString("name"));
                    channelUrls.add(obj.getString("url"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        int iconSizeDp = prefs.getInt("popup_icon_size", 80);
        int channelSizeDp = Math.max(50, iconSizeDp - 10);
        int sizeInPx = (int) (channelSizeDp * getResources().getDisplayMetrics().density);

        for (int i = 0; i < channelNames.size(); i++) {
            String name = channelNames.get(i);
            String url = channelUrls.get(i);

            TextView btn = new TextView(this);
            btn.setText(formatChannelName(name));
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14);
            btn.setMaxLines(2);
            btn.setGravity(Gravity.CENTER);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            btn.setBackgroundResource(R.drawable.bg_round_btn);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizeInPx, sizeInPx);
            lp.setMargins(0, 0, 20, 0);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                Intent browserIntent = new Intent(this, BrowserActivity.class);
                browserIntent.putExtra("url", url);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                hideHandler.post(hideRunnable);
            });

            final int idx = i;
            btn.setOnLongClickListener(v -> {
                showDeleteConfirmDialog(name, idx, channelNames, channelUrls);
                return true;
            });

            container.addView(btn);
        }

        TextView btnAdd = new TextView(this);
        btnAdd.setText("+\n추가");
        btnAdd.setTextColor(Color.parseColor("#4CAF50"));
        btnAdd.setTextSize(16);
        btnAdd.setGravity(Gravity.CENTER);
        btnAdd.setTypeface(null, android.graphics.Typeface.BOLD);
        btnAdd.setBackgroundResource(R.drawable.bg_round_btn);

        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(sizeInPx, sizeInPx);
        btnAdd.setLayoutParams(lpAdd);

        btnAdd.setOnClickListener(v -> showAddChannelDialog(channelNames, channelUrls));
        container.addView(btnAdd);
    }

    private String formatChannelName(String name) {
        if (name == null) return "";
        if (name.length() <= 4) return name;
        int mid = (name.length() + 1) / 2;
        return name.substring(0, mid) + "\n" + name.substring(mid);
    }

    private void showAddChannelDialog(List<String> channelNames, List<String> channelUrls) {
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
                channelNames.add(name);
                channelUrls.add(url);
                saveChannels(channelNames, channelUrls);
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

    private void showDeleteConfirmDialog(String name, int index, List<String> channelNames, List<String> channelUrls) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("채널 삭제");
        builder.setMessage("'" + name + "' 채널을 삭제하시겠습니까?");
        builder.setPositiveButton("삭제", (dialog, which) -> {
            channelNames.remove(index);
            channelUrls.remove(index);
            saveChannels(channelNames, channelUrls);
            populateChannelButtons();
            Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("취소", null);

        AlertDialog dialog = builder.create();
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(layoutFlag);
        }
        dialog.show();
    }

    private void saveChannels(List<String> names, List<String> urls) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (int i = 0; i < names.size(); i++) {
                JSONObject obj = new JSONObject();
                obj.put("name", names.get(i));
                obj.put("url", urls.get(i));
                jsonArray.put(obj);
            }
            SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
            prefs.edit().putString("brave_channels", jsonArray.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (autoLaunchHandler != null) autoLaunchHandler.removeCallbacksAndMessages(null);
        if (powerOffHandler != null) powerOffHandler.removeCallbacksAndMessages(null);
        if (powerReceiver != null) { try { unregisterReceiver(powerReceiver); } catch (Exception e) {} }
        if (settingsReceiver != null) { try { unregisterReceiver(settingsReceiver); } catch (Exception e) {} }
        if (tmapHudReceiver != null) { try { unregisterReceiver(tmapHudReceiver); } catch (Exception e) {} }
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);
        if (floatingView != null) { try { windowManager.removeView(floatingView); } catch (Exception ignored) {} }
        if (hudView != null) { try { windowManager.removeView(hudView); } catch (Exception ignored) {} }
        if (locationManager != null && locationListener != null) { try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {} }
        if (toneGenerator != null) { try { toneGenerator.release(); } catch (Exception ignored) {} }
    }
}