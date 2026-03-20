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
import android.graphics.Color;
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
    private Handler powerOffHandler = new Handler(Looper.getMainLooper()); // 전원 끊김 타이머용

    // 전원 연결 시 화면을 켜주기 위한 브로드캐스트 리시버
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                // 전원이 다시 들어오면(방지턱 등) 예약된 모두 닫기 매크로를 즉시 취소!
                powerOffHandler.removeCallbacksAndMessages(null);
                
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
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                // 전원이 끊어지면 10초 뒤에 모두 닫기 매크로 실행
                Toast.makeText(context, "전원 차단: 10초 뒤 앱을 자동 정리합니다 🧹", Toast.LENGTH_SHORT).show();
                powerOffHandler.removeCallbacksAndMessages(null);
                powerOffHandler.postDelayed(() -> {
                    executeCloseAllAppsMacro();
                    Toast.makeText(context, "운행 종료: 모두 닫기 완료!", Toast.LENGTH_SHORT).show();
                }, 10000); // 10초(10000ms) 대기
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
            launchApp("com.android.chrome"); // 브라우저 아이콘은 순수 브라우저 앱 실행으로 변경
            hideHandler.post(hideRunnable);
        });

        // 램 청소 버튼 터치 이벤트
        btnClean.setOnClickListener(v -> {
            cleanMemory();
            hideHandler.post(hideRunnable); // 청소 시작과 동시에 메뉴 숨김
        });

        // 전원 연결 감지 리시버 동적 등록
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED); // 전원 끊김 감지 추가
        registerReceiver(powerReceiver, filter);

        // 동적 채널 버튼 생성
        populateChannelButtons();
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
            
            // 1초 간격으로 두 번 클릭하는 공통 동작 정의
            Runnable doubleClickAction = () -> {
                if (MacroAccessibilityService.instance != null) {
                    MacroAccessibilityService.instance.performClick(1130f, 70f);
                    handler.postDelayed(() -> {
                        if (MacroAccessibilityService.instance != null) {
                            MacroAccessibilityService.instance.performClick(1130f, 70f);
                        }
                    }, 1000);
                }
            };

            // 로딩 속도 편차를 고려하여 10초, 20초, 30초에 걸쳐 총 3회 반복
            handler.postDelayed(() -> {
                doubleClickAction.run();
            }, 10000);
            handler.postDelayed(() -> {
                doubleClickAction.run();
            }, 20000);
            handler.postDelayed(() -> {
                doubleClickAction.run();
                Toast.makeText(this, "매크로: 티맵 안전주행 모드 확인 완료 🤖", Toast.LENGTH_SHORT).show();
            }, 30000);
        } else {
            Toast.makeText(this, "매크로 대기 중... (작동하지 않으면 설정에서 권한을 확인하세요)", Toast.LENGTH_SHORT).show();
        }
    }

    // 모두 닫기(최근 실행 앱 정리) 매크로 실행
    private void executeCloseAllAppsMacro() {
        if (MacroAccessibilityService.instance != null) {
            // MacroAccessibilityService에 구현할 메서드 호출
            MacroAccessibilityService.instance.closeAllRecentApps();
        }
    }

    // 플로팅 메뉴 안에 유튜브 채널 버튼들을 동적으로 생성하여 가로로 나열하는 메서드
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

        // 동그란 아이콘 모양을 만들기 위해 고정된 가로/세로 크기(90dp) 설정
        int sizeInPx = (int) (90 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < channelNames.size(); i++) {
            String name = channelNames.get(i);
            String url = channelUrls.get(i);

            TextView btn = new TextView(this);
            btn.setText(formatChannelName(name)); // 이름 포맷팅 적용
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(15); // 원 안에 들어가도록 글자 크기 축소
            btn.setMaxLines(2);
            btn.setGravity(Gravity.CENTER);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            btn.setBackgroundResource(R.drawable.bg_round_btn);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizeInPx, sizeInPx);
            lp.setMargins(0, 0, 24, 0);
            btn.setLayoutParams(lp);

            // 클릭 시 유튜브 실행
            btn.setOnClickListener(v -> {
                Intent intent = new Intent(this, BrowserActivity.class);
                intent.putExtra("url", url);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                hideHandler.post(hideRunnable);
            });

            // 길게 누를 시 삭제 로직
            final int index = i;
            btn.setOnLongClickListener(v -> {
                showDeleteConfirmDialog(name, index, channelNames, channelUrls);
                return true;
            });

            container.addView(btn);
        }

        // 새 채널 추가 버튼
        TextView addBtn = new TextView(this);
        addBtn.setText("➕\n추가");
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTextSize(15);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        addBtn.setBackgroundResource(R.drawable.bg_round_btn);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizeInPx, sizeInPx);
        addBtn.setLayoutParams(lp);

        addBtn.setOnClickListener(v -> {
            showAddChannelDialog(channelNames, channelUrls);
        });

        container.addView(addBtn);
    }

    // 채널 이름을 규칙(한글은 2, 영문은 1로 계산해 최대 16너비까지만, 그리고 공백은 줄바꿈)에 맞게 포맷팅
    private String formatChannelName(String name) {
        StringBuilder truncated = new StringBuilder();
        int length = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // 한글(가~힣, 자음모음)인지 판별하여 너비를 2로, 나머지는 1로 계산
            int w = (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E) ? 2 : 1;
            if (length + w > 16) break; // 지정된 글자 수를 초과하면 자름
            truncated.append(c);
            length += w;
        }
        return truncated.toString().replaceFirst(" ", "\n"); // 첫 번째 공백을 줄바꿈으로 변경
    }

    // 채널 추가 다이얼로그 (버튼 누르면 호출됨)
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
                populateChannelButtons(); // UI 버튼 다시 그리기
            } else {
                Toast.makeText(this, "이름과 URL을 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("취소", null);

        AlertDialog dialog = builder.create();
        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        dialog.getWindow().setType(layoutFlag);
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
    }

    // 채널 롱클릭 시 삭제 확인 다이얼로그
    private void showDeleteConfirmDialog(String name, int index, List<String> channelNames, List<String> channelUrls) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(contextThemeWrapper);
        builder.setTitle("채널 삭제");
        builder.setMessage("'" + name + "' 채널을 삭제하시겠습니까?");
        builder.setPositiveButton("삭제", (dialog, which) -> {
            channelNames.remove(index);
            channelUrls.remove(index);
            saveChannels(channelNames, channelUrls);
            populateChannelButtons(); // UI 버튼 다시 그리기
            Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
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
        if (powerOffHandler != null) powerOffHandler.removeCallbacksAndMessages(null);
        if (powerReceiver != null) { try { unregisterReceiver(powerReceiver); } catch (Exception e) {} }
        if (hideHandler != null && hideRunnable != null) hideHandler.removeCallbacks(hideRunnable);
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}