package com.example.carhome;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
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

import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvSpeed, tvMemory, tvBattery;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private BroadcastReceiver batteryReceiver;
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 화면 항상 켜짐 유지 - 시스템 설정(예: 15초)을 따르기 위해 주석 처리함
        // getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 화면 번쩍임 방지 코드이나, 커스텀 런처 사용 시 최근 실행 창(모두 닫기)이
        // 오른쪽으로 쏠리는 시스템 버그를 유발하므로 테스트를 위해 주석 처리함
        // WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        // 2. 전체 화면 모드 (상단 상태바 및 하단 네비게이션바 숨기기)
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (windowInsetsController != null) {
            // 화면 가장자리 스와이프 시 일시적으로 바가 나타나도록 설정
            windowInsetsController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
            // 상단 상태바만 숨기고 하단 네비게이션바(최근 실행 앱 버튼 포함)는 항상 보이게 유지
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars());
        }

        // 3. 레이아웃의 아이콘(ImageView)들 연결
        ImageView btnNavi = findViewById(R.id.btnNavi);
        ImageView btnMusic = findViewById(R.id.btnMusic);
        ImageView btnYoutube = findViewById(R.id.btnYoutube);
        ImageView btnSettings = findViewById(R.id.btnSettings);
        ImageView btnApp5 = findViewById(R.id.btnApp5);
        ImageView btnApp6 = findViewById(R.id.btnApp6);

        // 4. 기기에 저장된 사용자의 앱 설정값을 불러옵니다. (없을 경우 기본값 사용)
        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        String app1 = prefs.getString("app1", "com.skt.tmap.ku");
        String app2 = prefs.getString("app2", "com.google.android.apps.youtube.music");
        String app3 = prefs.getString("app3", "com.google.android.youtube");
        String app4 = prefs.getString("app4", "com.android.settings");
        String app5 = prefs.getString("app5", "com.android.chrome");
        String app6 = prefs.getString("app6", "com.android.vending");

        // UI와 클릭 이벤트 연결
        setupAppIconAndClick(btnNavi, app1, "app1");
        setupAppIconAndClick(btnMusic, app2, "app2");
        setupAppIconAndClick(btnYoutube, app3, "app3");
        setupAppIconAndClick(btnSettings, app4, "app4");
        setupAppIconAndClick(btnApp5, app5, "app5");
        setupAppIconAndClick(btnApp6, app6, "app6");

        // 상태 표시줄 UI 연결 및 설정
        tvSpeed = findViewById(R.id.tvSpeed);
        tvMemory = findViewById(R.id.tvMemory);
        tvBattery = findViewById(R.id.tvBattery);
        setupStatusBar();

        // 권한 확인 및 플로팅 서비스 실행
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 다른 앱 위에 표시 권한이 없다면 설정창 띄우기
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
                int batteryPct = (int) ((level / (float) scale) * 100);
                if (tvBattery != null) tvBattery.setText("BAT: " + batteryPct + "%");
            }
        };

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (location.hasSpeed()) {
                    int speedKmH = (int) (location.getSpeed() * 3.6); // m/s 를 km/h 로 변환
                    if (tvSpeed != null) tvSpeed.setText(speedKmH + " km/h");
                } else {
                    if (tvSpeed != null) tvSpeed.setText("0 km/h");
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
        }

        statusRunnable = new Runnable() {
            @Override
            public void run() {
                updateMemory();
                statusHandler.postDelayed(this, 1000);
            }
        };
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onResume() {
        super.onResume();
        // 화면이 켜지고 런처가 보일 때만 센서 업데이트 시작 (배터리 방전 방지)
        if (batteryReceiver != null) {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
        if (statusRunnable != null) {
            statusHandler.post(statusRunnable);
        }
        if (locationManager != null && locationListener != null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            }
        }

        // 홈 화면에 진입할 때마다 플로팅 서비스가 살아있는지 확인하고 뷰를 초기화 (튕김 및 사라짐 100% 방지)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startService(new Intent(this, FloatingService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 화면이 꺼지거나 다른 앱(티맵 등)이 켜지면 즉시 모든 백그라운드 측정 일시정지 (배터리 극강 절약)
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        }
        if (statusHandler != null && statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (locationManager != null && locationListener != null) {
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
                } catch (SecurityException e) { e.printStackTrace(); }
            }
        }
    }

    private void updateMemory() {
        // RAM 사용량 계산
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        int memPct = (int) ((usedMem / (double) memoryInfo.totalMem) * 100);
        if (tvMemory != null) tvMemory.setText("RAM: " + memPct + "%");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 앱이 꺼질 때 시스템 메모리 누수를 막기 위해 모든 리스너 해제 (필수)
        if (statusHandler != null && statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    // 다른 앱을 실행하는 공통 메서드
    private void launchApp(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            startActivity(intent);
        } else {
            // 태블릿에 해당 앱이 설치되어 있지 않을 경우 안내 메시지
            Toast.makeText(this, "해당 앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // 패키지명으로 앱의 실제 아이콘을 추출해 이미지뷰에 씌우는 메서드
    private void setupAppIconAndClick(ImageView imageView, String packageName, String prefKey) {
        PackageManager pm = getPackageManager();
        try {
            Drawable icon = pm.getApplicationIcon(packageName);
            imageView.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            // 앱이 없을 경우 안드로이드 기본 아이콘 표시
            imageView.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        // 클릭 이벤트 연결
        imageView.setOnClickListener(v -> launchApp(packageName));

        // 길게 누를 경우 앱 변경 다이얼로그 호출
        imageView.setOnLongClickListener(v -> {
            showAppSelectionDialog(imageView, prefKey);
            return true;
        });
    }

    // 기기에 설치된 앱 목록을 불러와 다이얼로그로 보여주는 메서드
    private void showAppSelectionDialog(ImageView imageView, String prefKey) {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);

        // 앱 이름을 가나다(알파벳) 순으로 정렬
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

        AppAdapter adapter = new AppAdapter(this, apps);
        new AlertDialog.Builder(this)
                .setTitle("앱 선택")
                .setAdapter(adapter, (dialog, which) -> {
                    ResolveInfo selectedApp = apps.get(which);
                    String packageName = selectedApp.activityInfo.packageName;

                    // 선택한 앱을 SharedPreferences에 저장
                    SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
                    prefs.edit().putString(prefKey, packageName).apply();

                    // 선택 즉시 아이콘과 클릭 이벤트 변경
                    setupAppIconAndClick(imageView, packageName, prefKey);
                })
                .show();
    }

    // 다이얼로그에 앱 아이콘과 이름을 함께 보여주기 위한 어댑터 클래스
    class AppAdapter extends ArrayAdapter<ResolveInfo> {
        PackageManager pm;
        public AppAdapter(Context context, List<ResolveInfo> apps) {
            super(context, android.R.layout.select_dialog_item, android.R.id.text1, apps);
            pm = context.getPackageManager();
        }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView tv = view.findViewById(android.R.id.text1);
            ResolveInfo info = getItem(position);

            tv.setText(info.loadLabel(pm));
            Drawable icon = info.loadIcon(pm);
            // 다이얼로그 리스트에 맞춰 아이콘 크기 조정
            int size = (int) (48 * getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
            tv.setCompoundDrawables(icon, null, null, null);
            tv.setCompoundDrawablePadding(30);
            return view;
        }
    }
}