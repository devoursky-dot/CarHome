package com.example.carhome;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Method;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private TextView tvMemory, tvBattery, tvAccessibility;

    private BroadcastReceiver batteryReceiver;
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRunnable;
    private AlertDialog appDrawerDialog = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 전원이 들어올 때 잠금 화면을 무시하고 화면을 즉시 켤 수 있도록 설정
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        setContentView(R.layout.activity_main);

        // 전체 화면 모드 (상단 상태바 숨기기, 스와이프 시 일시 노출)
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            windowInsetsController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars());
        }

        // 1. 상태바 뷰 연결
        tvMemory = findViewById(R.id.tvMemory);
        tvBattery = findViewById(R.id.tvBattery);
        tvAccessibility = findViewById(R.id.tvAccessibility);

        setupStatusBar();

        // 접근성 상태 버튼 클릭 시 안드로이드 접근성 설정창으로 즉시 이동
        View btnAccessibility = findViewById(R.id.btnAccessibilityStatus);
        if (btnAccessibility != null) {
            btnAccessibility.setOnClickListener(v -> {
                Toast.makeText(this, "CarHome 접근성 서비스를 [사용 중]으로 켜주세요! 🤖", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            });
        }

        // 2. 하단 4대 핵심 버튼 연결 (티맵, 태블릿 설정, 전체 앱, CarHome 설정)
        View btnNavi = findViewById(R.id.btnNavi);
        ImageView imgNaviIcon = findViewById(R.id.imgNaviIcon);
        if (imgNaviIcon != null) {
            try {
                Drawable tmapAppIcon = getPackageManager().getApplicationIcon("com.skt.tmap.ku");
                imgNaviIcon.setImageDrawable(tmapAppIcon);
            } catch (Exception ignored) {
                imgNaviIcon.setImageResource(R.drawable.ic_btn_tmap);
            }
        }

        View btnSystemSettings = findViewById(R.id.btnSystemSettings);
        View btnAllApps = findViewById(R.id.btnAllApps);
        View btnSettings = findViewById(R.id.btnSettings);

        if (btnNavi != null) {
            btnNavi.setOnClickListener(v -> launchApp("com.skt.tmap.ku"));
        }

        if (btnSystemSettings != null) {
            btnSystemSettings.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "설정 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnAllApps != null) {
            btnAllApps.setOnClickListener(v -> showAppDrawerDialog());
        }

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        }

        // 3. 플로팅 오버레이 권한 확인 및 실행
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 켜주세요!", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void setupStatusBar() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

                int batteryPct = 0;
                if (scale > 0) batteryPct = (int) ((level / (float) scale) * 100);

                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                String chargeStr = isCharging ? " (충전중 ⚡)" : "";

                if (tvBattery != null) tvBattery.setText("BAT: " + batteryPct + "%" + chargeStr);
            }
        };

        statusRunnable = new Runnable() {
            @Override
            public void run() {
                updateMemory();
                statusHandler.postDelayed(this, 2000); // 2초 주기 최적화
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (batteryReceiver != null) {
            try {
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Exception ignored) {}
        }
        if (statusRunnable != null) {
            statusHandler.post(statusRunnable);
        }

        // 플로팅 서비스 상시 유지 확인
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            Intent serviceIntent = new Intent(this, FloatingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        }
        if (statusHandler != null && statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }
    }

    private void updateMemory() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
            int memPct = (int) ((usedMem / (double) memoryInfo.totalMem) * 100);
            if (tvMemory != null) tvMemory.setText("RAM: " + memPct + "%");
        }

        if (tvAccessibility != null) {
            if (MacroAccessibilityService.instance != null) {
                tvAccessibility.setText("🟢 매크로 켜짐");
                tvAccessibility.setTextColor(Color.parseColor("#3DDC84"));
            } else {
                tvAccessibility.setText("🔴 매크로 꺼짐 (터치)");
                tvAccessibility.setTextColor(Color.parseColor("#FF5252"));
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // [홈 버튼 클릭 시] 열려 있는 전체 앱 서랍 창을 자동으로 닫고 메인 대시보드로 복귀
        if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
            appDrawerDialog.dismiss();
            appDrawerDialog = null;
        }
    }

    @Override
    public void onBackPressed() {
        // 1. 전체 앱 서랍 창이 열려 있다면 서랍 창을 먼저 닫음
        if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
            appDrawerDialog.dismiss();
            appDrawerDialog = null;
            return;
        }
        // 2. 메인 홈 화면에서는 뒤로가기를 눌러도 액티비티가 종료(finish)되지 않고 카홈 홈 화면을 안전하게 유지함
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
            appDrawerDialog.dismiss();
            appDrawerDialog = null;
        }
        if (statusHandler != null && statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        }
    }

    // [전체 앱 서랍 다이얼로그] 구현
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
            Toast.makeText(this, "접근성 서비스가 꺼져 있습니다. 상단 🔴을 눌러 켜주세요!", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppDrawerDialog() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> allApps = pm.queryIntentActivities(mainIntent, 0);

        Collections.sort(allApps, new ResolveInfo.DisplayNameComparator(pm));

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_drawer, null);
        RecyclerView recyclerApps = dialogView.findViewById(R.id.recyclerApps);
        EditText etSearch = dialogView.findViewById(R.id.etSearchApp);

        recyclerApps.setLayoutManager(new GridLayoutManager(this, 4));
        recyclerApps.setHasFixedSize(true);
        recyclerApps.setOverScrollMode(View.OVER_SCROLL_NEVER);

        AppDrawerAdapter adapter = new AppDrawerAdapter(this, allApps, pm, appInfo -> {
            launchApp(appInfo.activityInfo.packageName);
            if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
                appDrawerDialog.dismiss();
                appDrawerDialog = null;
            }
        });
        recyclerApps.setAdapter(adapter);

        if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
            appDrawerDialog.dismiss();
        }

        appDrawerDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (appDrawerDialog.getWindow() != null) {
            appDrawerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            appDrawerDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.88)
            );
        }

        // 실시간 앱 검색 필터링
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                adapter.filter(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        appDrawerDialog.setOnDismissListener(dialogInterface -> appDrawerDialog = null);
        appDrawerDialog.show();
    }

    // 다른 앱 실행 공통 메서드
    public void launchApp(String packageName) {
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

    // 전체 앱 서랍 어댑터 클래스 (RecyclerView 기반 고성능/스크롤 버그 방지 어댑터)
    static class AppDrawerAdapter extends RecyclerView.Adapter<AppDrawerAdapter.ViewHolder> {
        interface OnItemClickListener {
            void onItemClick(ResolveInfo info);
        }

        private Context context;
        private List<ResolveInfo> originalList;
        private List<ResolveInfo> filteredList;
        private PackageManager pm;
        private OnItemClickListener listener;
        private Map<String, Drawable> iconCache = new HashMap<>();
        private Map<String, String> labelCache = new HashMap<>();

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvName;

            public ViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgAppIcon);
                tvName = itemView.findViewById(R.id.tvAppName);
            }
        }

        public AppDrawerAdapter(Context context, List<ResolveInfo> apps, PackageManager pm, OnItemClickListener listener) {
            this.context = context;
            this.originalList = new ArrayList<>(apps);
            this.filteredList = new ArrayList<>(apps);
            this.pm = pm;
            this.listener = listener;
        }

        public void filter(String query) {
            filteredList.clear();
            if (query == null || query.trim().isEmpty()) {
                filteredList.addAll(originalList);
            } else {
                String lowerQuery = query.toLowerCase().trim();
                for (ResolveInfo info : originalList) {
                    String pkg = info.activityInfo.packageName;
                    String label = labelCache.get(pkg);
                    if (label == null) {
                        CharSequence cs = info.loadLabel(pm);
                        label = cs != null ? cs.toString() : "";
                        labelCache.put(pkg, label);
                    }
                    if (label.toLowerCase().contains(lowerQuery)) {
                        filteredList.add(info);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_app_grid, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ResolveInfo info = filteredList.get(position);
            if (info != null) {
                String pkg = info.activityInfo.packageName;
                String label = labelCache.get(pkg);
                if (label == null) {
                    CharSequence cs = info.loadLabel(pm);
                    label = cs != null ? cs.toString() : "";
                    labelCache.put(pkg, label);
                }
                holder.tvName.setText(label);

                Drawable icon = iconCache.get(pkg);
                if (icon == null) {
                    try {
                        icon = info.loadIcon(pm);
                    } catch (Exception e) {
                        icon = pm.getDefaultActivityIcon();
                    }
                    iconCache.put(pkg, icon);
                }
                holder.imgIcon.setImageDrawable(icon);

                holder.itemView.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onItemClick(info);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }
    }
}