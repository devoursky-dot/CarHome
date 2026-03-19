package com.example.carhome;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

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

    // 전원 연결 시 화면을 켜주기 위한 브로드캐스트 리시버
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                
                // [방어 1] 이미 화면이 켜져서 사용 중이라면 케이블이 흔들려서 재연결된 것이므로 무시합니다.
                if (pm != null && pm.isInteractive()) {
                    return;
                }
                
                if (pm != null && !pm.isInteractive()) {
                    @SuppressWarnings("deprecation")
                    PowerManager.WakeLock wakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "CarHome:PowerWakeLock"
                    );
                    wakeLock.acquire(3000); // 3초간 강제로 화면 켜기 (이후 기기 설정 시간에 따라 꺼짐)
                }
                Intent mainIntent = new Intent(context, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(mainIntent);

                // [방어 2] 시동 걸 때 전원이 흔들리며 여러 번 호출되는 것을 막기 위해, 기존 예약된 티맵 실행 타이머를 취소합니다.
                autoLaunchHandler.removeCallbacksAndMessages(null);

                // 홈 화면이 뜨고 나서 2초(2000ms) 뒤에 티맵 자동 실행
                autoLaunchHandler.postDelayed(() -> {
                    Intent tmapIntent = getPackageManager().getLaunchIntentForPackage("com.skt.tmap.ku");
                    if (tmapIntent != null) {
                        tmapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(tmapIntent);
                        Toast.makeText(context, "티맵을 자동 실행합니다 🚗", Toast.LENGTH_SHORT).show();
                        executeTmapMacro(); // 매크로 자동 실행
                    }
                }, 2000);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 바인딩을 사용하지 않으므로 null 반환
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 메인 액티비티가 켜질 때마다(홈 화면 진입 시) 호출되므로, 
        // 숨김바가 꼬여있거나 사라졌으면 다시 우측 하단에 나타나도록 강력하게 초기화합니다.
        if (floatingView != null && handleBar != null && floatingContent != null && params != null) {
            hideHandler.removeCallbacks(hideRunnable);
            floatingContent.setVisibility(View.GONE);
            handleBar.setVisibility(View.VISIBLE);

            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.x = 32;

            try {
                windowManager.updateViewLayout(floatingView, params);
            } catch (IllegalArgumentException e) {
                // 앱 강제종료 등으로 뷰가 윈도우 매니저에서 떨어져 나간 경우 다시 붙여줍니다.
                try { windowManager.addView(floatingView, params); } catch (Exception ex) { ex.printStackTrace(); }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return START_STICKY;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        floatingView = inflater.inflate(R.layout.layout_floating, null);

        // 안드로이드 버전에 따른 플로팅 윈도우 타입 설정
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 창 크기를 실시간으로 변경하기 위해 멤버 변수로 초기화
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 플로팅 창 밖도 터치 가능하게 설정
                PixelFormat.TRANSLUCENT
        );

        // 화면 우측 하단에 고정
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        params.x = 32; // 우측 여백
        params.y = 132; // 하단 여백 (기존 32에서 위로 100만큼 올림)

        windowManager.addView(floatingView, params);

        handleBar = floatingView.findViewById(R.id.handleBar);
        floatingContent = floatingView.findViewById(R.id.floatingContent);

        // 5초 후 다시 숨기는 동작
        hideRunnable = () -> {
            floatingContent.setVisibility(View.GONE);
            handleBar.setVisibility(View.VISIBLE);

            // 숨김 모드일 때는 우측 하단으로 크기/위치 복구
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.x = 32;
            windowManager.updateViewLayout(floatingView, params);
        };

        // 숨김바(손잡이)를 터치하면 메뉴가 나타나고 5초 타이머 시작
        handleBar.setOnClickListener(v -> {
            handleBar.setVisibility(View.GONE);
            floatingContent.setVisibility(View.VISIBLE);

            // 펼침 모드일 때는 가로 90% 크기로 화면 하단 중앙에 배치
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.x = 0; // 중앙 정렬이므로 좌우 여백 0
            windowManager.updateViewLayout(floatingView, params);

            hideHandler.removeCallbacks(hideRunnable);
            hideHandler.postDelayed(hideRunnable, 5000); // 5초 대기
        });

        // 뷰 연결 및 기기에 설치된 앱 아이콘 로드
        ImageView btnTmap = floatingView.findViewById(R.id.btnFloatingTmap);
        ImageView btnVideo = floatingView.findViewById(R.id.btnFloatingVideo);
        ImageView btnBrave = floatingView.findViewById(R.id.btnFloatingBrave);
        ImageView btnClean = floatingView.findViewById(R.id.btnFloatingClean);

        setAppIcon(btnTmap, "com.skt.tmap.ku");
        setAppIcon(btnVideo, "com.samsung.android.videolist"); // 삼성 비디오 앱
        setAppIcon(btnBrave, "com.android.chrome"); // 내장 브라우저 느낌을 위해 크롬 아이콘으로 교체

        // 클릭 이벤트 설정
        btnTmap.setOnClickListener(v -> {
            launchApp("com.skt.tmap.ku");
            hideHandler.post(hideRunnable); // 앱 실행 시 즉시 메뉴 숨김
        });
        
        btnVideo.setOnClickListener(v -> {
            launchApp("com.samsung.android.videolist");
            hideHandler.post(hideRunnable);
        });
        
        btnBrave.setOnClickListener(v -> {
            showYouTubeChannelDialog();
            hideHandler.post(hideRunnable); // 채널 목록 띄우고 즉시 메뉴 숨김
        });

        // 램 청소 버튼 터치 이벤트
        btnClean.setOnClickListener(v -> {
            cleanMemory();
            hideHandler.post(hideRunnable); // 청소 시작과 동시에 메뉴 숨김
        });

        // 전원 연결 감지 리시버 동적 등록
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        registerReceiver(powerReceiver, filter);
    }

    // 백그라운드 앱들을 종료하여 램을 확보하는 메서드
    private void cleanMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(0);

        ActivityManager.MemoryInfo beforeMem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(beforeMem);

        for (ApplicationInfo packageInfo : packages) {
            // 우리 런처 앱(CarHome)은 꺼지지 않도록 보호
            if (!packageInfo.packageName.equals(getPackageName())) {
                am.killBackgroundProcesses(packageInfo.packageName);
            }
        }

        // OS가 앱들을 정리할 시간을 살짝 준 뒤(0.5초) 확보된 용량 계산
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
            
            // 실행한 앱이 티맵일 경우 매크로 발동
            if ("com.skt.tmap.ku".equals(packageName)) {
                executeTmapMacro();
            }
        } else {
            Toast.makeText(this, "앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // 티맵 광고 닫기 및 안심 주행 자동 터치 매크로
    private void executeTmapMacro() {
        if (MacroAccessibilityService.instance != null) {
            Handler handler = new Handler(Looper.getMainLooper());
            
            // 1. 티맵 로딩 및 광고가 뜰 때까지 넉넉하게 대기 (15초 = 15000ms 설정)
            // 기기 속도에 맞춰 이 숫자를 조절하세요.
            handler.postDelayed(() -> {
                MacroAccessibilityService.instance.performClick(1130f, 70f);
                Toast.makeText(this, "매크로: 광고 닫기 터치 🤖", Toast.LENGTH_SHORT).show();
                
                // 2. 광고 닫기 후 1초(1000ms) 대기 후 안심 주행 버튼 터치
                handler.postDelayed(() -> {
                    MacroAccessibilityService.instance.performClick(1130f, 70f);
                    Toast.makeText(this, "매크로: 안심 주행 진입 🤖", Toast.LENGTH_SHORT).show();
                }, 1000);
                
            }, 15000); 
        } else {
            Toast.makeText(this, "매크로 대기 중... (작동하지 않으면 설정에서 권한을 확인하세요)", Toast.LENGTH_SHORT).show();
        }
    }

    private void showYouTubeChannelDialog() {
        // 다이얼로그용 기본 테마 적용
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        
        // 기기에 저장된 커스텀 채널 목록 불러오기
        SharedPreferences prefs = getSharedPreferences("CarHomePrefs", MODE_PRIVATE);
        String jsonString = prefs.getString("brave_channels", null);

        List<String> channelNames = new ArrayList<>();
        List<String> channelUrls = new ArrayList<>();

        try {
            if (jsonString == null) {
                // 처음 실행 시 기본 제공 목록 세팅
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

        // 표시할 메뉴 구성
        List<String> displayList = new ArrayList<>(channelNames);
        displayList.add("➕ 새 채널 추가");
        displayList.add("➖ 채널 삭제");
        displayList.add("닫기");

        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("유튜브 채널 선택");
        builder.setItems(displayList.toArray(new CharSequence[0]), (dialog, which) -> {
            if (which < channelNames.size()) {
                // 내장 브라우저 화면(BrowserActivity) 호출
                Intent intent = new Intent(this, BrowserActivity.class);
                intent.putExtra("url", channelUrls.get(which));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            } else if (which == channelNames.size()) {
                showAddChannelDialog(channelNames, channelUrls);
            } else if (which == channelNames.size() + 1) {
                showDeleteChannelDialog(channelNames, channelUrls);
            }
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        
        // 서비스 영역에서 시스템 다이얼로그를 띄우기 위한 필수 윈도우 타입 권한 설정
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    // 채널 추가 다이얼로그 띄우기
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
                showYouTubeChannelDialog(); // 목록 다시 띄우기
            } else {
                Toast.makeText(this, "이름과 URL을 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("취소", null);

        AlertDialog dialog = builder.create();
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        dialog.getWindow().setType(layoutFlag);
        // 키보드 입력을 위해 NOT_FOCUSABLE 플래그를 확실하게 제거
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
    }

    // 채널 삭제 다이얼로그 띄우기
    private void showDeleteChannelDialog(List<String> channelNames, List<String> channelUrls) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("삭제할 채널 선택");

        builder.setItems(channelNames.toArray(new CharSequence[0]), (dialog, which) -> {
            channelNames.remove(which);
            channelUrls.remove(which);
            saveChannels(channelNames, channelUrls);
            Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
            showYouTubeChannelDialog(); // 목록 다시 띄우기
        });
        builder.setNegativeButton("취소", null);

        AlertDialog dialog = builder.create();
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        dialog.getWindow().setType(layoutFlag);
        dialog.show();
    }

    // 변경된 채널 목록을 기기에 저장하는 헬퍼 메서드
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
        if (powerReceiver != null) { try { unregisterReceiver(powerReceiver); } catch (Exception e) {} }
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}