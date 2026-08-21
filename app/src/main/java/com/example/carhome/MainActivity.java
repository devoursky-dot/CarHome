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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private TextView tvMemory, tvBattery, tvAccessibility, tvGpsStatus;
    private TextView tvGpsSpeed, tvGpsHeading;

    private BroadcastReceiver batteryReceiver;
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRunnable;

    private LocationManager locationManager;
    private static final int PERMISSION_REQ_LOCATION = 1001;

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

        // 1. 상태바 및 대시보드 뷰 연결
        tvMemory = findViewById(R.id.tvMemory);
        tvBattery = findViewById(R.id.tvBattery);
        tvAccessibility = findViewById(R.id.tvAccessibility);
        tvGpsStatus = findViewById(R.id.tvGpsStatus);
        tvGpsSpeed = findViewById(R.id.tvGpsSpeed);
        tvGpsHeading = findViewById(R.id.tvGpsHeading);

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

        // 3. 위치 관리자 초기화 및 권한 확인
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        checkLocationPermission();

        // 4. 플로팅 오버레이 권한 확인 및 실행
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 켜주세요!", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSION_REQ_LOCATION
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                registerLocationUpdates();
            } else {
                if (tvGpsStatus != null) tvGpsStatus.setText("📡 GPS 권한 필요");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void registerLocationUpdates() {
        if (locationManager == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0.5f, this);
                if (tvGpsStatus != null) tvGpsStatus.setText("📡 GPS 수신 중");
            } else {
                if (tvGpsStatus != null) tvGpsStatus.setText("📡 GPS 꺼짐");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void unregisterLocationUpdates() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (tvGpsStatus != null) tvGpsStatus.setText("📡 GPS 정상");

        // 속도 계산 (m/s -> km/h)
        float speedKmh = 0f;
        if (location.hasSpeed()) {
            speedKmh = location.getSpeed() * 3.6f;
        }

        int speedInt = Math.round(speedKmh);
        if (tvGpsSpeed != null) {
            tvGpsSpeed.setText(speedInt + " km/h");
            // 80km/h 초과 시 시각적 경고 색상
            if (speedInt >= 100) {
                tvGpsSpeed.setTextColor(Color.parseColor("#FF5252"));
            } else if (speedInt >= 80) {
                tvGpsSpeed.setTextColor(Color.parseColor("#FFB300"));
            } else {
                tvGpsSpeed.setTextColor(Color.WHITE);
            }
        }

        // 주행 방위 계산
        if (tvGpsHeading != null) {
            if (speedInt < 3) {
                tvGpsHeading.setText("[ 🧭 정지 ]");
            } else if (location.hasBearing()) {
                String headingStr = getHeadingString(location.getBearing());
                tvGpsHeading.setText("[ 🧭 " + headingStr + " ]");
            }
        }
    }

    private String getHeadingString(float bearing) {
        String[] directions = {"북 (N)", "북동 (NE)", "동 (E)", "남동 (SE)", "남 (S)", "남서 (SW)", "서 (W)", "북서 (NW)"};
        int index = Math.round(bearing / 45) % 8;
        return directions[index < 0 ? index + 8 : index];
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider) && tvGpsStatus != null) {
            tvGpsStatus.setText("📡 GPS 켜짐");
        }
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider) && tvGpsStatus != null) {
            tvGpsStatus.setText("📡 GPS 꺼짐");
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
                statusHandler.postDelayed(this, 1000);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (batteryReceiver != null) {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
        if (statusRunnable != null) {
            statusHandler.post(statusRunnable);
        }

        // GPS 수신 등록
        registerLocationUpdates();

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
        // 배터리 방전 방지를 위해 화면 벗어날 때 즉시 센서 및 GPS 해제
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        }
        if (statusHandler != null && statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }
        unregisterLocationUpdates();
    }

    private void updateMemory() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        int memPct = (int) ((usedMem / (double) memoryInfo.totalMem) * 100);
        if (tvMemory != null) tvMemory.setText("RAM: " + memPct + "%");

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
    protected void onDestroy() {
        super.onDestroy();
        if (statusHandler != null && statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        }
        unregisterLocationUpdates();
    }

    // [전체 앱 서랍 다이얼로그] 구현
    private void executeTmapMacro() {
        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        int macroCount = prefs.getInt("tmap_macro_count", 6);
        if (macroCount <= 0) {
            // 0회 설정 시 매크로 실행 안 함
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
        GridView gridApps = dialogView.findViewById(R.id.gridApps);
        EditText etSearch = dialogView.findViewById(R.id.etSearchApp);
        ImageView btnClose = dialogView.findViewById(R.id.btnCloseDrawer);

        AppDrawerAdapter adapter = new AppDrawerAdapter(this, allApps, pm);
        gridApps.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.88)
            );
        }

        gridApps.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo appInfo = adapter.getItem(position);
            if (appInfo != null) {
                launchApp(appInfo.activityInfo.packageName);
                dialog.dismiss();
            }
        });

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

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
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
            // 안심주행 스킴 실패 시 기본 패키지 인텐트로 폴백
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

    // 전체 앱 서랍 어댑터 클래스
    static class AppDrawerAdapter extends BaseAdapter {
        private Context context;
        private List<ResolveInfo> originalList;
        private List<ResolveInfo> filteredList;
        private PackageManager pm;

        public AppDrawerAdapter(Context context, List<ResolveInfo> apps, PackageManager pm) {
            this.context = context;
            this.originalList = new ArrayList<>(apps);
            this.filteredList = new ArrayList<>(apps);
            this.pm = pm;
        }

        public void filter(String query) {
            filteredList.clear();
            if (query == null || query.trim().isEmpty()) {
                filteredList.addAll(originalList);
            } else {
                String lowerQuery = query.toLowerCase().trim();
                for (ResolveInfo info : originalList) {
                    CharSequence label = info.loadLabel(pm);
                    if (label != null && label.toString().toLowerCase().contains(lowerQuery)) {
                        filteredList.add(info);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return filteredList.size();
        }

        @Override
        public ResolveInfo getItem(int position) {
            return filteredList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_app_grid, parent, false);
            }

            ImageView imgIcon = convertView.findViewById(R.id.imgAppIcon);
            TextView tvName = convertView.findViewById(R.id.tvAppName);

            ResolveInfo info = getItem(position);
            if (info != null) {
                tvName.setText(info.loadLabel(pm));
                Drawable icon = info.loadIcon(pm);
                imgIcon.setImageDrawable(icon);
            }

            return convertView;
        }
    }
}