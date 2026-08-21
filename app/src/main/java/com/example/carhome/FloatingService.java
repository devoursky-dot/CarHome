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
    private Handler powerDisconnectDebounceHandler = new Handler(Looper.getMainLooper());
    private boolean isCountingDownToPowerOff = false;

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
                // 1. 전원 차단 디바운스 및 카운트다운 타이머 즉시 취소
                powerDisconnectDebounceHandler.removeCallbacksAndMessages(null);
                powerOffHandler.removeCallbacksAndMessages(null);

                boolean wasCountingDown = isCountingDownToPowerOff;
                isCountingDownToPowerOff = false;

                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                boolean isScreenOff = (pm != null && !pm.isInteractive());

                // [진짜 시동 ON일 때만 실행]
                // 화면이 꺼져 있던 상태(새로 시동 건 상태)이거나,
                // 직전에 전원이 끊겨서 실제로 10초 카운트다운이 돌고 있었을 때만 화면을 켜고 티맵 자동 실행
                if (isScreenOff || wasCountingDown) {
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
                // 만약 이미 화면이 켜져서 브라우저나 유튜브 등을 사용 중이던 상태에서 0.5초 잠깐 전원이 튄 것은
                // 사용자를 절대 방해하지 않고 현재 보던 화면을 100% 그대로 유지합니다.
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                autoLaunchHandler.removeCallbacksAndMessages(null);

                // [3초 전원 차단 안전망 디바운스]
                // 0.5초 미세 순단, 배터리 완충 깜빡임, 시동 순간 전압 강하는 3초 이내에 복구되므로 조용히 무시.
                // 3초 이상 전원 차단이 지속될 때만 "진짜 시동 OFF"로 판단하여 카운트다운을 시작합니다.
                powerDisconnectDebounceHandler.removeCallbacksAndMessages(null);
                powerDisconnectDebounceHandler.postDelayed(() -> {
                    // 3초 후 실제 하드웨어 충전 상태 교차 검증
                    if (!isCurrentlyCharging()) {
                        isCountingDownToPowerOff = true;
                        Toast.makeText(context, "전원 차단 감지: 10초 뒤 모든 앱 정리 및 화면 잠금(절전)을 실행합니다 🧹💤", Toast.LENGTH_LONG).show();
                        powerOffHandler.removeCallbacksAndMessages(null);
                        powerOffHandler.postDelayed(() -> {
                            isCountingDownToPowerOff = false;
                            cleanMemory(); // 1. 백그라운드 프로세스 & 캐시 일괄 청소
                            executeCloseAllAppsAndLockMacro(); // 2. 최근 앱 모두 닫기 & 화면 잠금(절전 모드) 전환
                        }, 10000);
                    }
                }, 3000);
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
            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (Exception ignored) {}
        };

        handleBar.setOnClickListener(v -> {
            handleBar.setVisibility(View.GONE);
            floatingContent.setVisibility(View.VISIBLE);

            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.x = 0;
            params.y = getSharedPreferences("CarHomePrefs", MODE_PRIVATE).getInt("popup_y", 200);
            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (Exception ignored) {}

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }
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
        if ("com.skt.tmap.ku".equals(packageName)) {
            launchTmapSafeDriving();
            return;
        }

        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } else {
            Toast.makeText(this, "해당 앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // 티맵 광고를 100% 건너뛰고 안심주행 화면으로 즉시 직행하는 다이렉트 런처
    private void launchTmapSafeDriving() {
        try {
            Intent directIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tmap://safe"));
            directIntent.setPackage("com.skt.tmap.ku");
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

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

                startActivity(directIntent, options.toBundle());
            } else {
                startActivity(directIntent);
            }
            Toast.makeText(this, "🚗 티맵 안심주행으로 바로 실행합니다", Toast.LENGTH_SHORT).show();

            boolean isMacroEnabled = prefs.getBoolean("tmap_macro_enabled", false);
            if (isMacroEnabled) {
                executeTmapMacro();
            }
        } catch (Exception e) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage("com.skt.tmap.ku");
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback);
                } else {
                    Toast.makeText(this, "티맵이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) {
                Toast.makeText(this, "티맵 실행 실패", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void executeTmapMacro() {
        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        int macroCount = prefs.getInt("tmap_macro_count", 6);
        if (macroCount <= 0) return;

        float tmapX = prefs.getInt("tmap_x", 1130);
        float tmapY = prefs.getInt("tmap_y", 70);
        int intervalSec = prefs.getInt("tmap_macro_interval", 3);

        if (MacroAccessibilityService.instance != null) {
            MacroAccessibilityService.instance.scheduleTmapMacro(tmapX, tmapY, macroCount, intervalSec);
        }
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

        List<String> channelNames = new ArrayList<>();
        List<String> channelUrls = new ArrayList<>();

        String savedJson = prefs.getString("brave_channels", null);
        if (savedJson != null) {
            try {
                JSONArray jsonArray = new JSONArray(savedJson);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    channelNames.add(obj.getString("name"));
                    channelUrls.add(obj.getString("url"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // 기본 권장 채널 프리셋
            channelNames.add("실시간\n뉴스");
            channelUrls.add("https://m.youtube.com/results?search_query=실시간+뉴스+라이브");

            channelNames.add("실시간\n음악");
            channelUrls.add("https://m.youtube.com/results?search_query=실시간+음악+라이브");

            channelNames.add("유튜브\n홈");
            channelUrls.add("https://m.youtube.com");

            saveChannels(channelNames, channelUrls);
        }

        for (int i = 0; i < channelNames.size(); i++) {
            final int index = i;
            String name = channelNames.get(i);
            String url = channelUrls.get(i);

            TextView btnChannel = new TextView(this);
            btnChannel.setText(formatChannelName(name));
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
                Intent browserIntent = new Intent(this, BrowserActivity.class);
                browserIntent.putExtra("url", url);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(browserIntent);
                hideHandler.post(hideRunnable);
            });

            btnChannel.setOnLongClickListener(v -> {
                showDeleteConfirmDialog(name, index, channelNames, channelUrls);
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

        btnAdd.setOnClickListener(v -> showAddChannelDialog(channelNames, channelUrls));

        channelContainer.addView(btnAdd);
    }

    private String formatChannelName(String name) {
        if (name == null || name.length() <= 3 || name.contains("\n")) {
            return name;
        }
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
        if (powerDisconnectDebounceHandler != null) powerDisconnectDebounceHandler.removeCallbacksAndMessages(null);
        if (powerReceiver != null) { try { unregisterReceiver(powerReceiver); } catch (Exception e) {} }
        if (settingsReceiver != null) { try { unregisterReceiver(settingsReceiver); } catch (Exception e) {} }
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);
        if (floatingView != null) { try { windowManager.removeView(floatingView); } catch (Exception ignored) {} }
    }
}