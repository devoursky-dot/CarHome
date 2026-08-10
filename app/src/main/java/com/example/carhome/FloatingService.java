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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
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
import java.util.List;

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
                Toast.makeText(context, "전원 차단: 10초 뒤 앱을 자동 정리합니다 🧹", Toast.LENGTH_SHORT).show();
                powerOffHandler.removeCallbacksAndMessages(null);
                powerOffHandler.postDelayed(() -> {
                    executeCloseAllAppsMacro();
                    Toast.makeText(context, "운행 종료: 모두 닫기 완료!", Toast.LENGTH_SHORT).show();
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
            channel.setDescription("차량용 플로팅 위젯과 시동 자동화를 유지합니다.");
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
                .setContentTitle("CarHome 차량용 서비스 동작 중")
                .setContentText("플로팅 위젯과 시동 자동화가 활성화되어 있습니다.")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }
    }

    private void applySizesToViews(SharedPreferences prefs) {
        TextView handleTime = floatingView.findViewById(R.id.handleTime);
        if (handleTime != null) {
            handleTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getInt("handle_clock_size", 18));
        }

        TextView tcFloating = floatingView.findViewById(R.id.textClockFloating);
        if (tcFloating != null) {
            tcFloating.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getInt("popup_clock_size", 50));
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
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(0);

        ActivityManager.MemoryInfo beforeMem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(beforeMem);

        for (ApplicationInfo packageInfo : packages) {
            if (!packageInfo.packageName.equals(getPackageName())) {
                am.killBackgroundProcesses(packageInfo.packageName);
            }
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ActivityManager.MemoryInfo afterMem = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(afterMem);
            long freedMem = afterMem.availMem - beforeMem.availMem;

            if (freedMem > 0) {
                Toast.makeText(this, (freedMem / (1024 * 1024)) + "MB의 램이 확보되었습니다! 🧹", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "이미 램이 최적화된 상태입니다. ✨", Toast.LENGTH_SHORT).show();
            }
        }, 500);
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
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

            if ("com.skt.tmap.ku".equals(packageName)) {
                executeTmapMacro();
            }
        } else {
            Toast.makeText(this, "앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeTmapMacro() {
        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        float tmapX = prefs.getInt("tmap_x", 1130);
        float tmapY = prefs.getInt("tmap_y", 70);

        if (MacroAccessibilityService.instance != null) {
            Toast.makeText(this, "티맵 안전주행 자동확인 매크로 가동 중... 🤖", Toast.LENGTH_SHORT).show();
            MacroAccessibilityService.instance.scheduleTmapMacro(tmapX, tmapY);
        } else {
            Toast.makeText(this, "접근성 서비스가 꺼져 있습니다. 권한을 확인하세요!", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeCloseAllAppsMacro() {
        if (MacroAccessibilityService.instance != null) {
            MacroAccessibilityService.instance.closeAllRecentApps();
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
                Intent intent = new Intent(this, BrowserActivity.class);
                intent.putExtra("url", url);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                hideHandler.post(hideRunnable);
            });

            final int index = i;
            btn.setOnLongClickListener(v -> {
                showDeleteConfirmDialog(name, index, channelNames, channelUrls);
                return true;
            });

            container.addView(btn);
        }

        TextView addBtn = new TextView(this);
        addBtn.setText("➕\n추가");
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTextSize(14);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        addBtn.setBackgroundResource(R.drawable.bg_round_btn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizeInPx, sizeInPx);
        addBtn.setLayoutParams(lp);

        addBtn.setOnClickListener(v -> showAddChannelDialog(channelNames, channelUrls));

        container.addView(addBtn);
    }

    private String formatChannelName(String name) {
        StringBuilder truncated = new StringBuilder();
        int length = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            int w = (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E) ? 2 : 1;
            if (length + w > 16) break;
            truncated.append(c);
            length += w;
        }
        return truncated.toString().replaceFirst(" ", "\n");
    }

    private void showAddChannelDialog(List<String> channelNames, List<String> channelUrls) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("새 채널 추가");

        LinearLayout layout = new LinearLayout(contextThemeWrapper);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        EditText nameInput = new EditText(contextThemeWrapper);
        nameInput.setHint("채널 이름 (예: 워크맨)");
        layout.addView(nameInput);

        EditText urlInput = new EditText(contextThemeWrapper);
        urlInput.setHint("유튜브 URL 주소 (https://...)");
        layout.addView(urlInput);

        builder.setView(layout);
        builder.setPositiveButton("추가", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            if (!name.isEmpty() && !url.isEmpty()) {
                channelNames.add(name);
                channelUrls.add(url);
                saveChannels(channelNames, channelUrls);
                Toast.makeText(this, "추가되었습니다.", Toast.LENGTH_SHORT).show();
                populateChannelButtons();
            } else {
                Toast.makeText(this, "이름과 URL을 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
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
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}