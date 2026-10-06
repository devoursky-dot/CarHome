package com.example.carhome;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    public static volatile MainActivity instance = null;
    public static boolean isMainActivityResumed = false;

    private TextView tvMemory, tvBattery, tvAccessibility;

    private BroadcastReceiver batteryReceiver;
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRunnable;
    private AlertDialog appDrawerDialog = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        // 윈도우 배경을 확실한 불투명 솔리드 색상으로 지정하여 이전 홈앱/잠금화면 잔상 겹침 방지
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#101014")));

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

        // 2. 하단 대시보드 카드 버튼 연결 (티맵, 전체앱, 비디오, 인터넷, RAM 청소, 설정)
        View btnNavi = findViewById(R.id.btnNavi);
        ImageView imgNaviIcon = findViewById(R.id.imgNaviIcon);
        setAppIcon(imgNaviIcon, "com.skt.tmap.ku");
        if (btnNavi != null) {
            btnNavi.setOnClickListener(v -> launchApp("com.skt.tmap.ku"));
        }

        View btnAllApps = findViewById(R.id.btnAllApps);
        if (btnAllApps != null) {
            btnAllApps.setOnClickListener(v -> showAppDrawerDialog());
        }

        View btnMainVideo = findViewById(R.id.btnMainVideo);
        ImageView imgMainVideoIcon = findViewById(R.id.imgMainVideoIcon);
        setAppIcon(imgMainVideoIcon, "com.samsung.android.videolist");
        if (btnMainVideo != null) {
            btnMainVideo.setOnClickListener(v -> launchApp("com.samsung.android.videolist"));
        }

        View btnMainBrave = findViewById(R.id.btnMainBrave);
        ImageView imgMainBraveIcon = findViewById(R.id.imgMainBraveIcon);
        setAppIcon(imgMainBraveIcon, "com.android.chrome");
        if (btnMainBrave != null) {
            btnMainBrave.setOnClickListener(v -> launchApp("com.android.chrome"));
        }

        View btnMainClean = findViewById(R.id.btnMainClean);
        if (btnMainClean != null) {
            btnMainClean.setOnClickListener(v -> cleanMemory());
            btnMainClean.setOnLongClickListener(v -> {
                cleanMemory();
                if (MacroAccessibilityService.instance != null) {
                    MacroAccessibilityService.instance.closeAllRecentApps();
                    Toast.makeText(this, "🚀 [초강력 RAM 청소] 최근 앱 모두 닫기 매크로 가동!", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        if (tvMemory != null) {
            tvMemory.setOnClickListener(v -> cleanMemory());
            tvMemory.setOnLongClickListener(v -> {
                cleanMemory();
                if (MacroAccessibilityService.instance != null) {
                    MacroAccessibilityService.instance.closeAllRecentApps();
                    Toast.makeText(this, "🚀 [초강력 RAM 청소] 최근 앱 모두 닫기 매크로 가동!", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        View btnSystemSettings = findViewById(R.id.btnSystemSettings);
        if (btnSystemSettings != null) {
            btnSystemSettings.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "설정 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 유튜브 등록 채널 독 바 동적 생성
        populateMainChannelButtons();

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
        isMainActivityResumed = true;

        // 메인 홈 화면에 단독으로 머무는 동안만 플로팅 서비스 위젯을 숨김
        if (FloatingService.instance != null) {
            FloatingService.instance.setFloatingVisibility(false);
        }

        populateMainChannelButtons();

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
        isMainActivityResumed = false;

        // 다른 앱(티맵, 유튜브, 설정 등)으로 나갈 때는 플로팅 위젯 다시 표시
        if (FloatingService.instance != null) {
            FloatingService.instance.setFloatingVisibility(true);
        }

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
                tvAccessibility.setText("🟢 자동화 켜짐");
                tvAccessibility.setTextColor(Color.parseColor("#3DDC84"));
            } else {
                tvAccessibility.setText("🔴 자동화 꺼짐");
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
        if (instance == this) {
            instance = null;
        }
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

    private void showAppDrawerDialog() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> allApps = pm.queryIntentActivities(mainIntent, 0);

        Collections.sort(allApps, new ResolveInfo.DisplayNameComparator(pm));

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_drawer, null);
        RecyclerView recyclerApps = dialogView.findViewById(R.id.recyclerApps);
        EditText etSearch = dialogView.findViewById(R.id.etSearchApp);
        View btnClose = dialogView.findViewById(R.id.btnCloseAppDrawer);

        // 태블릿 가로 화면에서 잘림 없이 쾌적하게 보이도록 5열 또는 4열 동적 적용
        int widthPx = getResources().getDisplayMetrics().widthPixels;
        int spanCount = widthPx >= 1400 ? 5 : 4;
        recyclerApps.setLayoutManager(new GridLayoutManager(this, spanCount));
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

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
                    appDrawerDialog.dismiss();
                    appDrawerDialog = null;
                }
            });
        }

        if (appDrawerDialog != null && appDrawerDialog.isShowing()) {
            appDrawerDialog.dismiss();
        }

        appDrawerDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        appDrawerDialog.setOnDismissListener(dialogInterface -> appDrawerDialog = null);

        // [핵심 버그 수정]: 다이얼로그 윈도우 크기는 반드시 show() 실행 직후에 지정해야 우측 잘림 없이 100% 반영됨
        appDrawerDialog.show();

        if (appDrawerDialog.getWindow() != null) {
            appDrawerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int dialogWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.95);
            int dialogHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.90);
            appDrawerDialog.getWindow().setLayout(dialogWidth, dialogHeight);
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
    }

    // 다른 앱 실행 공통 메서드
    public void launchApp(String packageName) {
        AppLauncher.launchApp(this, packageName);
    }

    // 티맵 안심주행 직행
    private void launchTmapSafeDriving() {
        AppLauncher.launchTmapSafeDriving(this);
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
        
        // 스레드 릭(Thread Leak) 방지를 위해 정적으로 1번만 생성하여 공용 사용
        private static final java.util.concurrent.ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(4);
        private static final Handler mainHandler = new Handler(Looper.getMainLooper());

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvName;
            String boundPackageName; // 비동기 콜백 시 재활용된 뷰인지 확인용

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
            
            // 초기 생성 시 라벨을 미리 백그라운드에서 캐싱 (검색 필터를 위해)
            executorService.execute(() -> {
                for (ResolveInfo info : originalList) {
                    String pkg = info.activityInfo.packageName;
                    if (!labelCache.containsKey(pkg)) {
                        CharSequence cs = info.loadLabel(pm);
                        labelCache.put(pkg, cs != null ? cs.toString() : "");
                    }
                }
            });
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
                holder.boundPackageName = pkg;
                
                // 임시 플레이스홀더 세팅 (재활용 시 이전 이미지/텍스트가 보이는 것을 방지)
                holder.tvName.setText("로딩중...");
                holder.imgIcon.setImageResource(android.R.drawable.sym_def_app_icon);

                // 라벨 세팅
                String cachedLabel = labelCache.get(pkg);
                if (cachedLabel != null) {
                    holder.tvName.setText(cachedLabel);
                }

                // 아이콘 비동기 로딩
                Drawable cachedIcon = iconCache.get(pkg);
                if (cachedIcon != null) {
                    holder.imgIcon.setImageDrawable(cachedIcon);
                }

                if (cachedLabel == null || cachedIcon == null) {
                    executorService.execute(() -> {
                        String loadedLabel = cachedLabel;
                        if (loadedLabel == null) {
                            CharSequence cs = info.loadLabel(pm);
                            loadedLabel = cs != null ? cs.toString() : "";
                            labelCache.put(pkg, loadedLabel);
                        }

                        Drawable loadedIcon = cachedIcon;
                        if (loadedIcon == null) {
                            try {
                                loadedIcon = info.loadIcon(pm);
                            } catch (Exception e) {
                                loadedIcon = pm.getDefaultActivityIcon();
                            }
                            iconCache.put(pkg, loadedIcon);
                        }
                        
                        final String finalLabel = loadedLabel;
                        final Drawable finalIcon = loadedIcon;

                        mainHandler.post(() -> {
                            // 뷰홀더가 다른 패키지로 재활용되지 않았을 때만 UI 업데이트
                            if (pkg.equals(holder.boundPackageName)) {
                                holder.tvName.setText(finalLabel);
                                holder.imgIcon.setImageDrawable(finalIcon);
                            }
                        });
                    });
                }

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

    private void setAppIcon(ImageView imageView, String packageName) {
        AppLauncher.setAppIcon(getPackageManager(), imageView, packageName);
    }

    private void cleanMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(0);

        ActivityManager.MemoryInfo beforeMem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(beforeMem);

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

        String[] aggressiveTargets = {
                "com.android.vending",
                "com.sec.android.app.launcher",
                "com.osp.app.signin",
                "com.samsung.android.messaging",
                "com.samsung.android.mobileservice",
                "com.samsung.android.app.telephonyui",
                "com.sec.android.gallery3d",
                "com.android.settings.intelligence",
                "com.android.settings",
                "com.samsung.android.fmm",
                "com.sec.android.app.myfiles",
                "com.sec.android.app.clockpackage",
                "com.samsung.android.dynamiclock",
                "com.sec.android.app.soundalive",
                "com.samsung.android.homemode",
                "com.samsung.android.app.smartcapture",
                "com.samsung.android.lool",
                "com.samsung.android.sm.devicesecurity",
                "com.samsung.android.sm.policy",
                "com.google.android.googlequicksearchbox",
                "com.google.android.gms.ui",
                "com.samsung.android.video",
                "com.android.chrome",
                "com.lguplus.appstore",
                "com.skt.skaf.OA00018282",
                "com.skt.skaf.OA00412131",
                "com.google.android.projection.gearhead",
                "com.sec.android.app.sbrowser",
                "com.brave.browser",
                "com.samsung.android.game.gamehome",
                "com.samsung.android.game.gametools",
                "com.samsung.android.bixby.agent",
                "com.samsung.android.bixby.service",
                "com.microsoft.skydrive"
        };

        for (String target : aggressiveTargets) {
            if (!protectedWhitelist.contains(target)) {
                try { am.killBackgroundProcesses(target); } catch (Exception ignored) {}
            }
        }

        int killedCount = 0;
        for (ApplicationInfo packageInfo : packages) {
            if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 1) continue;
            if (protectedWhitelist.contains(packageInfo.packageName)) continue;
            try {
                am.killBackgroundProcesses(packageInfo.packageName);
                killedCount++;
            } catch (Exception ignored) {}
        }

        updateMemory();

        ActivityManager.MemoryInfo afterMem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(afterMem);
        long freedBytes = afterMem.availMem - beforeMem.availMem;
        long freedMb = freedBytes / (1024 * 1024);

        if (freedMb > 0) {
            Toast.makeText(this, "🧹 메모리 " + freedMb + "MB 확보 완료! (" + killedCount + "개 프로세스 정리)", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "🧹 백그라운드 " + killedCount + "개 앱 정리 완료!", Toast.LENGTH_SHORT).show();
        }
    }

    private void populateMainChannelButtons() {
        LinearLayout channelContainer = findViewById(R.id.mainChannelContainer);
        if (channelContainer == null) return;
        channelContainer.removeAllViews();

        int iconSizePx = (int) (70 * getResources().getDisplayMetrics().density);
        List<ChannelManager.ChannelItem> channels = ChannelManager.loadChannels(this);

        for (int i = 0; i < channels.size(); i++) {
            final int index = i;
            ChannelManager.ChannelItem item = channels.get(i);

            TextView btnChannel = new TextView(this);
            btnChannel.setText(ChannelManager.formatChannelName(item.name));
            btnChannel.setTextColor(Color.WHITE);
            btnChannel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btnChannel.setGravity(Gravity.CENTER);
            btnChannel.setBackgroundResource(R.drawable.bg_dock_btn);
            btnChannel.setClickable(true);
            btnChannel.setFocusable(true);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            params.setMarginEnd(12);
            btnChannel.setLayoutParams(params);

            btnChannel.setOnClickListener(v -> {
                Intent ytIntent = new Intent(this, BrowserActivity.class);
                ytIntent.putExtra("url", item.url);
                ytIntent.putExtra("channel_name", item.name);
                ytIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(ytIntent);
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
        btnAdd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("새 유튜브 채널 바로가기 추가");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText inputName = new EditText(this);
        inputName.setHint("채널 이름 (예: 뉴스, 음악)");
        layout.addView(inputName);

        final EditText inputUrl = new EditText(this);
        inputUrl.setHint("유튜브 URL (예: https://m.youtube.com/...)");
        layout.addView(inputUrl);

        builder.setView(layout);

        builder.setPositiveButton("추가", (dialog, which) -> {
            String name = inputName.getText().toString().trim();
            String url = inputUrl.getText().toString().trim();

            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, "이름과 주소를 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            channels.add(new ChannelManager.ChannelItem(name, url));
            ChannelManager.saveChannels(this, channels);
            populateMainChannelButtons();
            Toast.makeText(this, name + " 바로가기 등록 완료!", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("취소", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showDeleteConfirmDialog(String name, int index, List<ChannelManager.ChannelItem> channels) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("채널 바로가기 삭제");
        builder.setMessage("'" + name.replace("\n", " ") + "' 바로가기를 삭제하시겠습니까?");

        builder.setPositiveButton("삭제", (dialog, which) -> {
            if (index >= 0 && index < channels.size()) {
                channels.remove(index);
                ChannelManager.saveChannels(this, channels);
                populateMainChannelButtons();
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("취소", (dialog, which) -> dialog.cancel());
        builder.show();
    }
}