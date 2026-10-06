package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.app.ActivityManager;
import android.content.Context;
import java.util.Locale;
import java.util.List;

public class BrowserActivity extends AppCompatActivity {

    private static final String TAG = "BrowserActivity";
    public static volatile BrowserActivity currentInstance = null;

    public static void closeIfRunning() {
        if (currentInstance != null) {
            try {
                currentInstance.finish();
            } catch (Exception ignored) {}
            currentInstance = null;
        }
    }

    // 램을 대량 소모하는 불필요 백그라운드 프로세스 타겟 상수 (실제 dumpsys meminfo 덤프 기반 최적화)
    private static final String[] AGGRESSIVE_TARGETS = {
            "com.android.vending",                  // 구글 플레이 스토어 (약 177MB)
            "com.sec.android.app.launcher",        // 삼성 기본 원UI 런처 (약 57MB)
            "com.osp.app.signin",                  // 삼성 계정 백그라운드 (약 32MB)
            "com.samsung.android.messaging",       // 삼성 메시지 캐시 (약 25MB)
            "com.samsung.android.mobileservice",   // 삼성 모바일 서비스 (약 24MB)
            "com.samsung.android.app.telephonyui", // 통화 UI 캐시 (약 19MB)
            "com.sec.android.gallery3d",           // 갤러리 캐시 (약 14MB)
            "com.android.settings.intelligence",   // 설정 인텔리전스 (약 14MB)
            "com.android.settings",                // 설정 앱 캐시 (약 13MB)
            "com.samsung.android.fmm",             // 내 디바이스 찾기 (약 14MB)
            "com.sec.android.app.myfiles",         // 내 파일 (약 12MB)
            "com.sec.android.app.clockpackage",    // 시계 앱 (약 11MB)
            "com.samsung.android.dynamiclock",     // 다이내믹 락 (약 10MB)
            "com.sec.android.app.soundalive",      // 사운드 얼라이브 (약 10MB)
            "com.samsung.android.homemode",        // 데일리 보드 (약 9MB)
            "com.samsung.android.app.smartcapture", // 스마트 캡처 (약 8MB)
            "com.samsung.android.lool",            // 디바이스 케어 (약 10MB)
            "com.samsung.android.sm.devicesecurity",// 디바이스 시큐리티 (약 6MB)
            "com.samsung.android.sm.policy",       // 보안 정책 데몬
            "com.google.android.googlequicksearchbox", // 구글 앱/어시스턴트 (약 130MB)
            "com.google.android.gms.ui",           // 구글 플레이 서비스 UI (약 35MB)
            "com.samsung.android.video",           // 삼성 비디오 캐시 (약 31MB)
            "com.android.chrome",                  // 크롬 브라우저 캐시
            "com.lguplus.appstore",                // 통신사 스토어
            "com.skt.skaf.OA00018282",             // 원스토어 서비스
            "com.skt.skaf.OA00412131",             // 원스토어 메인
            "com.google.android.projection.gearhead", // 안드로이드 오토
            "com.sec.android.app.sbrowser",        // 삼성 브라우저
            "com.brave.browser",                   // 브레이브 브라우저
            "com.samsung.android.game.gamehome",   // 게임홈
            "com.samsung.android.game.gametools",  // 게임툴즈
            "com.samsung.android.bixby.agent",     // 빅스비 에이전트
            "com.samsung.android.bixby.service",   // 빅스비 서비스
            "com.microsoft.skydrive"               // 원드라이브 동기화
    };

    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;
    private TextView btnRamStatus;
    private TextView[] resolutionButtons;
    private TextView[] speedButtons;
    private String currentQualityLabel = "240P";
    private String currentQualityLevel = "small";
    private float currentPlaybackSpeed = 1.0f;
    private String lastLoadedUrl = "https://m.youtube.com";
    private String currentChannelHomeUrl = "https://m.youtube.com";
    private volatile boolean isVideoCompleted = false;

    private ActivityManager activityManager;
    private final ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
    private final ActivityManager.MemoryInfo cleanTempMem = new ActivityManager.MemoryInfo();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastAdToastTime = 0;

    // [수정 6] WebView 렌더러 크래시 복구 무한 루프 방지 카운터
    private int renderCrashCount = 0;
    private static final int MAX_RENDER_CRASH_RECOVERY = 3;

    // 실시간 태블릿 RAM 상태 모니터링 Runnable (3초 주기, 객체 재할당 없이 재사용)
    private final Runnable ramMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            updateRamStatus();
            mainHandler.postDelayed(this, 3000);
        }
    };

    // 실시간 태블릿 RAM 상태 측정 및 UI 갱신 (단일 MemoryInfo 인스턴스 재사용으로 GC 압박 제거)
    private void updateRamStatus() {
        if (btnRamStatus == null || isFinishing() || isDestroyed()) return;
        try {
            if (activityManager == null) {
                activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            }
            if (activityManager == null) return;

            activityManager.getMemoryInfo(memoryInfo);

            long totalBytes = memoryInfo.totalMem;
            long availBytes = memoryInfo.availMem;
            long usedBytes = totalBytes - availBytes;
            int usedPercent = (int) ((usedBytes * 100) / totalBytes);

            double availGb = availBytes / (1024.0 * 1024.0 * 1024.0);
            String text;
            if (availGb < 1.0) {
                long availMb = availBytes / (1024 * 1024);
                text = String.format(Locale.getDefault(), "💾 여유 %dM (%d%%)", availMb, usedPercent);
            } else {
                text = String.format(Locale.getDefault(), "💾 여유 %.1fG (%d%%)", availGb, usedPercent);
            }

            btnRamStatus.setText(text);
            if (usedPercent >= 85 || memoryInfo.lowMemory) {
                btnRamStatus.setTextColor(Color.parseColor("#FF5252")); // 빨간색 (위험)
            } else if (usedPercent >= 75) {
                btnRamStatus.setTextColor(Color.parseColor("#FFB300")); // 주황색 (주의)
            } else {
                btnRamStatus.setTextColor(Color.parseColor("#3DDC84")); // 초록색 (안전)
            }

            // [스마트 자동 메모리 청소]: 80% 이상 임계치 도달 시 백그라운드 유휴 앱 자동 정리 (단, 3분 쿨다운 적용으로 CPU/발열 방지)
            if (usedPercent >= 80 && (System.currentTimeMillis() - lastAutoCleanTime >= AUTO_CLEAN_COOLDOWN_MS)) {
                lastAutoCleanTime = System.currentTimeMillis();
                Log.d(TAG, "스마트 자동 메모리 청소 트리거 발동 (현재 점유율: " + usedPercent + "%)");
                performDeepMemoryClean();
                // 1초 뒤 RAM 상태 즉시 갱신하여 회수된 램 반영
                mainHandler.postDelayed(this::updateRamStatus, 1200);
            }
        } catch (Exception e) {
            Log.e(TAG, "RAM 상태 조회 실패", e);
        }
    }

    private long lastAutoCleanTime = 0;
    private static final long AUTO_CLEAN_COOLDOWN_MS = 180_000; // 3분 쿨다운

    // 백그라운드 불필요 프로세스 일괄 정리 + WebView 캐시 트리밍 + 램 부스터
    private void performDeepMemoryClean() {
        if (activityManager == null) {
            activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        }
        if (activityManager == null) return;

        activityManager.getMemoryInfo(cleanTempMem);
        long beforeAvail = cleanTempMem.availMem;

        for (String targetPkg : AGGRESSIVE_TARGETS) {
            try {
                activityManager.killBackgroundProcesses(targetPkg);
            } catch (Exception ignored) {}
        }

        // 웹뷰 메모리 캐시 트리밍
        if (webView != null) {
            try {
                webView.freeMemory();
                webView.clearCache(false);
            } catch (Exception ignored) {}
        }

        updateRamStatus();

        mainHandler.postDelayed(() -> {
            updateRamStatus();
            if (activityManager == null) return;
            activityManager.getMemoryInfo(cleanTempMem);
            long freedMem = cleanTempMem.availMem - beforeAvail;
            long availMb = cleanTempMem.availMem / (1024 * 1024);
            int usedPercent = (int) (((cleanTempMem.totalMem - cleanTempMem.availMem) * 100) / cleanTempMem.totalMem);

            if (freedMem > 0) {
                long freedMb = freedMem / (1024 * 1024);
                Toast.makeText(this,
                        String.format(Locale.getDefault(),
                                "🧹 [RAM 부스터] 불필요 백그라운드 프로세스 정리 완료!\n[ +%d MB 확보 ] (여유 램: %d MB, 사용률: %d%%)",
                                freedMb, availMb, usedPercent),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        String.format(Locale.getDefault(),
                                "🧹 [RAM 부스터] 백그라운드 캐시 정리 완료!\n(여유 램: %d MB, 사용률: %d%%)",
                                availMb, usedPercent),
                        Toast.LENGTH_LONG).show();
            }
        }, 500);
    }

    // 안드로이드 - 웹뷰 간 실시간 광고 차단 통신 브릿지
    public class AdBlockBridge {
        @JavascriptInterface
        public void onAdSkipped(String reason) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                long now = System.currentTimeMillis();
                if (now - lastAdToastTime > 5000) {
                    lastAdToastTime = now;
                    Toast.makeText(BrowserActivity.this, "⚡ [초고속 광고 스킵] 광고 건너뛰기 완료!", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // 안드로이드 - 웹뷰 간 실시간 시청 기록 및 이어보기 통신 브릿지
    public class HistoryBridge {
        @JavascriptInterface
        public void onSaveProgress(String videoId, String title, String channel, String thumb, long durationMs, long posMs) {
            if (videoId == null || videoId.isEmpty() || durationMs <= 0) return;
            VideoHistoryDbHelper.getInstance(BrowserActivity.this)
                    .saveProgress(videoId, title, channel, thumb, durationMs, posMs);
        }

        @JavascriptInterface
        public long getSavedPosition(String videoId) {
            if (videoId == null || videoId.isEmpty()) return 0;
            VideoHistoryDbHelper.HistoryEntry entry = VideoHistoryDbHelper.getInstance(BrowserActivity.this).getHistory(videoId);
            if (entry != null && !entry.isCompleted()) {
                return entry.lastPositionMs;
            }
            return 0;
        }

        @JavascriptInterface
        public String getWatchedListJson() {
            List<VideoHistoryDbHelper.HistoryEntry> list = VideoHistoryDbHelper.getInstance(BrowserActivity.this).getAllHistories(2000);
            org.json.JSONObject json = new org.json.JSONObject();
            try {
                for (VideoHistoryDbHelper.HistoryEntry h : list) {
                    json.put(h.videoId, h.isCompleted() ? 100 : h.getProgressPercent());
                }
            } catch (Exception ignored) {}
            return json.toString();
        }

        @JavascriptInterface
        public void onResumeToast(String timeStr) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(BrowserActivity.this, "⏱️ 이전 시청 위치(" + timeStr + ")부터 이어보기", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void onVideoCompleted(String videoId, String title) {
            isVideoCompleted = true;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String displayTitle = (title != null && !title.isEmpty()) ? ("'" + title + "' ") : "";
                Toast.makeText(BrowserActivity.this, "🎬 [시청 완료] " + displayTitle + "채널 홈으로 이동합니다 🏠", Toast.LENGTH_SHORT).show();
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        if (customView != null) {
                            handleBackNavigation();
                        }
                        String targetUrl = (currentChannelHomeUrl != null && !currentChannelHomeUrl.isEmpty())
                                ? currentChannelHomeUrl : "https://m.youtube.com";
                        if (webView != null) {
                            webView.loadUrl(targetUrl);
                        }
                        mainHandler.postDelayed(() -> isVideoCompleted = false, 1200);
                    }
                }, 350);
            });
        }
    }

    // 안드로이드 - 웹뷰 간 실시간 재생 배속 감지 통신 브릿지
    public class SpeedBridge {
        @JavascriptInterface
        public void onSpeedDetected(float speed) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Log.d(TAG, "YouTube actual playback speed detected: " + speed);
                updateActiveSpeedUI(speed);
            });
        }
    }

    // 안드로이드 - 웹뷰 간 실시간 해상도 감지 통신 브릿지
    public class QualityBridge {
        @JavascriptInterface
        public void onQualityDetected(String quality) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Log.d(TAG, "YouTube actual playback quality detected: " + quality);
                String label = mapQualityToLabel(quality);
                if (!label.isEmpty()) {
                    updateActiveResolutionUI(label);
                }
            });
        }
    }

    private String mapQualityToLabel(String quality) {
        if (quality == null || quality.isEmpty()) return "";
        switch (quality.toLowerCase(Locale.ROOT)) {
            case "144p":
            case "tiny":
                return "144P";
            case "240p":
            case "small":
                return "240P";
            case "360p":
            case "medium":
                return "360P";
            case "480p":
            case "large":
                return "480P";
            case "720p":
            case "hd720":
                return "720P";
            case "1080p":
            case "hd1080":
            case "hd1440":
            case "hd2160":
            case "highres":
                return "1080P";
            default:
                return quality.toUpperCase(Locale.ROOT);
        }
    }

    // 툴바 3번째 줄 해상도 버튼 활성 상태 시각화
    private void updateActiveResolutionUI(String label) {
        if (resolutionButtons == null) return;
        if (label == null || label.isEmpty()) {
            return; // 일시적인 빈 감지값에 기존 하이라이트가 꺼지거나 깜빡이지 않도록 유지
        }
        currentQualityLabel = label;
        for (TextView btn : resolutionButtons) {
            if (btn == null) continue;
            String text = btn.getText().toString().toUpperCase(Locale.getDefault());
            if (text.contains(label.toUpperCase(Locale.getDefault()))) {
                btn.setBackgroundResource(R.drawable.bg_btn_res_active);
                btn.setTextColor(Color.parseColor("#052410")); // 네온 에메랄드 배경 위 딥 포레스트 볼드 텍스트
            } else {
                btn.setBackgroundResource(R.drawable.bg_btn_inactive);
                btn.setTextColor(Color.parseColor("#8E95A5")); // 모던 메탈릭 실버 텍스트
            }
        }
    }

    // 툴바 1번째 줄 배속 버튼 활성 상태 시각화
    private void updateActiveSpeedUI(float speed) {
        if (speed <= 0) return; // 유효하지 않은 배속 감지 시 기존 선택 유지
        currentPlaybackSpeed = speed;
        if (speedButtons == null) return;
        String targetText;
        if (Math.abs(speed - 1.5f) < 0.15f) {
            targetText = "1.5X";
        } else if (Math.abs(speed - 2.0f) < 0.15f) {
            targetText = "2X";
        } else if (Math.abs(speed - 1.0f) < 0.15f) {
            targetText = "1X";
        } else {
            return; // 중간 전환 배속 시 기존 활성 버튼 유지
        }
        for (TextView btn : speedButtons) {
            if (btn == null) continue;
            if (targetText.equals(btn.getText().toString())) {
                btn.setBackgroundResource(R.drawable.bg_btn_speed_active);
                btn.setTextColor(Color.parseColor("#001529")); // 일렉트릭 블루 배경 위 딥 네이비 볼드 텍스트
            } else {
                btn.setBackgroundResource(R.drawable.bg_btn_inactive);
                btn.setTextColor(Color.parseColor("#8E95A5")); // 모던 메탈릭 실버 텍스트
            }
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentInstance = this;

        // [화면 꺼짐 및 화면 잠금 방지 플래그 상시 유지]
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 키보드(IME)와 뷰가 정상적으로 리사이즈되도록 설정하면서 상단 상태바 숨김
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars()); // 상단 상태바만 숨김
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setContentView(R.layout.activity_browser);
        webView = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        btnRamStatus = findViewById(R.id.btnRamStatus);

        // 1. 실시간 태블릿 RAM 모니터링 배지 버튼 (클릭 시 불필요 백그라운드 프로세스 정리, 롱클릭 시 최근 앱 모두 닫기 매크로)
        if (btnRamStatus != null) {
            updateRamStatus();
            btnRamStatus.setOnClickListener(v -> performDeepMemoryClean());
            btnRamStatus.setOnLongClickListener(v -> {
                performDeepMemoryClean();
                if (MacroAccessibilityService.instance != null) {
                    MacroAccessibilityService.instance.closeAllRecentApps();
                    Toast.makeText(this, "🚀 [초강력 RAM 청소] 최근 앱 모두 닫기 매크로 가동!", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // 2. 툴바 배속 버튼 연결 (1X, 1.5X, 2X)
        TextView btnSpeed1x = findViewById(R.id.btnSpeed1x);
        TextView btnSpeed1_5x = findViewById(R.id.btnSpeed1_5x);
        TextView btnSpeed2x = findViewById(R.id.btnSpeed2x);
        speedButtons = new TextView[]{btnSpeed1x, btnSpeed1_5x, btnSpeed2x};

        // 사용자 설정 배속 및 해상도 불러오기 (기본값: 1.0X 배속, 240P 해상도)
        android.content.SharedPreferences prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        currentPlaybackSpeed = prefs.getFloat("playback_speed", 1.0f);
        currentQualityLabel = prefs.getString("quality_label", "240P");
        currentQualityLevel = prefs.getString("quality_level", "small");

        if (btnSpeed1x != null) btnSpeed1x.setOnClickListener(v -> setVideoSpeed(1.0f));
        if (btnSpeed1_5x != null) btnSpeed1_5x.setOnClickListener(v -> setVideoSpeed(1.5f));
        if (btnSpeed2x != null) btnSpeed2x.setOnClickListener(v -> setVideoSpeed(2.0f));
        updateActiveSpeedUI(currentPlaybackSpeed);

        // 3. 툴바 해상도 조절 버튼 연결 (144P, 240P, 360P, 480P, 720P, 1080P)
        TextView btnRes144 = findViewById(R.id.btnRes144);
        TextView btnRes240 = findViewById(R.id.btnRes240);
        TextView btnRes360 = findViewById(R.id.btnRes360);
        TextView btnRes480 = findViewById(R.id.btnRes480);
        TextView btnRes720 = findViewById(R.id.btnRes720);
        TextView btnRes1080 = findViewById(R.id.btnRes1080);
        resolutionButtons = new TextView[]{btnRes144, btnRes240, btnRes360, btnRes480, btnRes720, btnRes1080};

        if (btnRes144 != null) btnRes144.setOnClickListener(v -> setVideoQuality("tiny", "144P"));
        if (btnRes240 != null) btnRes240.setOnClickListener(v -> setVideoQuality("small", "240P"));
        if (btnRes360 != null) btnRes360.setOnClickListener(v -> setVideoQuality("medium", "360P"));
        if (btnRes480 != null) btnRes480.setOnClickListener(v -> setVideoQuality("large", "480P"));
        if (btnRes720 != null) btnRes720.setOnClickListener(v -> setVideoQuality("hd720", "720P"));
        if (btnRes1080 != null) btnRes1080.setOnClickListener(v -> setVideoQuality("hd1080", "1080P"));
        updateActiveResolutionUI(null);

        setupWebViewInstance(webView);

        String url = getIntent().getStringExtra("url");
        if (url != null && !url.isEmpty()) {
            lastLoadedUrl = url;
            currentChannelHomeUrl = url;
            webView.loadUrl(url);
        } else {
            webView.loadUrl(lastLoadedUrl);
        }

        // 앱 실행 시 현재 화면 방향(가로/세로)에 맞춰 레이아웃 동적 초기화
        updateMenuLayout(getResources().getConfiguration().orientation);
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void setupWebViewInstance(WebView wv) {
        if (wv == null) return;

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // [키보드/자판 입력 포커스 활성화]
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.requestFocus(View.FOCUS_DOWN);
        wv.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                isVideoCompleted = false;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_UP:
                    if (!v.hasFocus()) {
                        v.requestFocus();
                    }
                    break;
            }
            return false;
        });

        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // 동영상 자동 재생 허용
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        wv.clearCache(true); // 광고 차단 오염 캐시 및 재생 에러 상태 초기화
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        // [YouTube 안티-애드블록 우회] WebView 식별자(; wv) 제거하여 순수 모바일 크롬으로 인식되도록 설정
        String defaultUa = settings.getUserAgentString();
        if (defaultUa != null && defaultUa.contains("; wv")) {
            settings.setUserAgentString(defaultUa.replace("; wv", ""));
        }

        // 자바스크립트 브릿지 등록 (광고 차단, 해상도, 배속 및 시청 기록 감지용)
        wv.addJavascriptInterface(new AdBlockBridge(), "AndroidAdBlock");
        wv.addJavascriptInterface(new QualityBridge(), "AndroidQuality");
        wv.addJavascriptInterface(new SpeedBridge(), "AndroidSpeed");
        wv.addJavascriptInterface(new HistoryBridge(), "AndroidHistory");

        // [HTML5 전체화면 비디오 재생 지원을 위한 WebChromeClient 장착]
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                view.setKeepScreenOn(true);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                View rootLayout = findViewById(R.id.browserRootLayout);
                if (rootLayout != null) rootLayout.setVisibility(View.GONE);
                if (fullscreenContainer != null) {
                    fullscreenContainer.setVisibility(View.VISIBLE);
                    fullscreenContainer.setKeepScreenOn(true);
                    fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                }

                // [화면 회전 강제 고정 제거] 사용자의 태블릿 거치 방향(가로/세로)을 그대로 유지
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                if (fullscreenContainer != null) {
                    fullscreenContainer.removeView(customView);
                    fullscreenContainer.setVisibility(View.GONE);
                }
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                View rootLayout = findViewById(R.id.browserRootLayout);
                if (rootLayout != null) rootLayout.setVisibility(View.VISIBLE);
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String targetUrl = request.getUrl().toString();
                // [시청 완료 후 자동 다음 영상 전환 원천 차단]
                if (isVideoCompleted && !request.hasGesture()) {
                    Log.d(TAG, "🚫 [전용앱 보호] 시청 완료 후 다음 영상 자동 전환 네이티브 차단: " + targetUrl);
                    return true;
                }
                isVideoCompleted = false;
                return false;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (isVideoCompleted) {
                    Log.d(TAG, "🚫 [전용앱 보호] 시청 완료 후 다음 영상 자동 전환 네이티브 차단: " + url);
                    return true;
                }
                isVideoCompleted = false;
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url != null) {
                    lastLoadedUrl = url;
                    if (!url.contains("/watch") && (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/") || url.contains("list="))) {
                        currentChannelHomeUrl = url;
                    }
                }
                updateActiveSpeedUI(currentPlaybackSpeed);
                updateActiveResolutionUI(currentQualityLabel);
                injectEarlyAdPruner(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) {
                    lastLoadedUrl = url;
                    if (!url.contains("/watch") && (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/") || url.contains("list="))) {
                        currentChannelHomeUrl = url;
                    }
                }
                updateActiveSpeedUI(currentPlaybackSpeed);
                updateActiveResolutionUI(currentQualityLabel);
                injectEarlyAdPruner(view);
                injectAdBlocker(view);
            }

            // [수정 6] 웹뷰 렌더러 프로세스 비정상 종료 시 앱 크래시 방지 및 안정한 복구 (무한 루프 방지)
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (isFinishing() || isDestroyed()) return true;
                renderCrashCount++;
                Log.e(TAG, "WebView render process gone (#" + renderCrashCount + "). didCrash: " + detail.didCrash());

                // [문제 D 수정] 전체화면 상태에서 크래시 시 customView/fullscreen 상태 정리
                if (customView != null) {
                    if (fullscreenContainer != null) {
                        fullscreenContainer.removeView(customView);
                        fullscreenContainer.setVisibility(View.GONE);
                    }
                    customView = null;
                    if (customViewCallback != null) {
                        customViewCallback.onCustomViewHidden();
                        customViewCallback = null;
                    }
                    View rootLayout = findViewById(R.id.browserRootLayout);
                    if (rootLayout != null) rootLayout.setVisibility(View.VISIBLE);
                }

                if (view != null) {
                    ViewGroup parent = (ViewGroup) view.getParent();
                    if (parent != null) {
                        parent.removeView(view);
                    }
                    view.destroy();
                }
                if (renderCrashCount >= MAX_RENDER_CRASH_RECOVERY) {
                    Toast.makeText(BrowserActivity.this, "브라우저가 반복 충돌하여 복구를 중단합니다. 다시 열어주세요 ⚠️", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    Toast.makeText(BrowserActivity.this, "웹 브라우저 렌더러를 안전하게 복구합니다 🔄 (" + renderCrashCount + "/" + MAX_RENDER_CRASH_RECOVERY + ")", Toast.LENGTH_SHORT).show();
                    recreateWebView();

                    // [문제 C 수정] 복구 성공 30초 후 추가 크래시가 없으면 카운터 자동 리셋
                    final int crashCountAtRecovery = renderCrashCount;
                    mainHandler.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed() && renderCrashCount == crashCountAtRecovery) {
                            renderCrashCount = 0;
                            Log.d(TAG, "Render crash count reset after 30s stability");
                        }
                    }, 30000);
                }
                return true;
            }
        });
    }

    // WebView 렌더러 사망 시 액티비티 붕괴 없이 동적 복구
    private void recreateWebView() {
        if (isFinishing() || isDestroyed()) return;

        LinearLayout browserRootLayout = findViewById(R.id.browserRootLayout);
        if (browserRootLayout == null) return;

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        browserRootLayout.addView(webView);

        setupWebViewInstance(webView);
        updateMenuLayout(getResources().getConfiguration().orientation);

        if (lastLoadedUrl != null && !lastLoadedUrl.isEmpty()) {
            webView.loadUrl(lastLoadedUrl);
        } else {
            webView.loadUrl("https://m.youtube.com");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mainHandler.removeCallbacks(ramMonitorRunnable);
        mainHandler.post(ramMonitorRunnable);
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(ramMonitorRunnable);
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView != null) {
            try {
                webView.freeMemory();
            } catch (Exception ignored) {}
        }
        if (level >= TRIM_MEMORY_MODERATE) {
            try {
                if (webView != null) {
                    webView.clearCache(false);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (webView != null) {
            try {
                webView.freeMemory();
                webView.clearCache(false);
            } catch (Exception ignored) {}
        }
    }

    // 뒤로가기 통합 처리 메서드 (태블릿 뒤로가기 버튼 지원)
    private void handleBackNavigation() {
        if (customView != null) {
            if (fullscreenContainer != null && customView != null) {
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
            }
            customView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            View rootLayout = findViewById(R.id.browserRootLayout);
            if (rootLayout != null) rootLayout.setVisibility(View.VISIBLE);
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    // [uBlock Origin 표준] YouTube Player adPlacements/playerAds 무균화 스크립틀릿 (0초 무광고 직행)
    private void injectEarlyAdPruner(WebView view) {
        if (view == null) return;
        String js =
                "(function() {" +
                "  if (window.__carHomePrunerInstalled) return;" +
                "  window.__carHomePrunerInstalled = true;" +
                "  function prune(data) {" +
                "    if (!data || typeof data !== 'object') return data;" +
                "    try {" +
                "      if (data.adPlacements) delete data.adPlacements;" +
                "      if (data.playerAds) delete data.playerAds;" +
                "      if (data.adSlots) delete data.adSlots;" +
                "      if (data.playerResponse && typeof data.playerResponse === 'object') {" +
                "        if (data.playerResponse.adPlacements) delete data.playerResponse.adPlacements;" +
                "        if (data.playerResponse.playerAds) delete data.playerResponse.playerAds;" +
                "        if (data.playerResponse.adSlots) delete data.playerResponse.adSlots;" +
                "      }" +
                "    } catch(e) {}" +
                "    return data;" +
                "  }" +
                "  try {" +
                "    var origParse = JSON.parse;" +
                "    JSON.parse = function() {" +
                "      var res = origParse.apply(this, arguments);" +
                "      return prune(res);" +
                "    };" +
                "  } catch(e) {}" +
                "  try {" +
                "    if (window.Response && Response.prototype.json) {" +
                "      var origJson = Response.prototype.json;" +
                "      Response.prototype.json = function() {" +
                "        return origJson.apply(this, arguments).then(function(data) {" +
                "          return prune(data);" +
                "        });" +
                "      };" +
                "    }" +
                "  } catch(e) {}" +
                "  try {" +
                "    var _ytInitialPlayerResponse = window.ytInitialPlayerResponse;" +
                "    if (_ytInitialPlayerResponse) {" +
                "      _ytInitialPlayerResponse = prune(_ytInitialPlayerResponse);" +
                "    }" +
                "    Object.defineProperty(window, 'ytInitialPlayerResponse', {" +
                "      get: function() { return _ytInitialPlayerResponse; }," +
                "      set: function(val) { _ytInitialPlayerResponse = prune(val); }," +
                "      configurable: true," +
                "      enumerable: true" +
                "    });" +
                "  } catch(e) {}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    // 스마트 초고속 광고 스킵, 자동 스킵 버튼 클릭, 영상 종료 시 일시정지 및 화질/배속 제어
    private void injectAdBlocker(WebView view) {
        if (view == null) return;
        injectEarlyAdPruner(view);
        String adBlockJs =
                "(function() {" +
                "  if (window.__carHomeAdBlockInitialized) {" +
                "    window.__carHomeUserSpeed = " + currentPlaybackSpeed + ";" +
                "    window.__carHomeSelectedQualityLevel = '" + currentQualityLevel + "';" +
                "    return;" +
                "  }" +
                "  window.__carHomeAdBlockInitialized = true;" +
                "  window.__carHomeUserSpeed = " + currentPlaybackSpeed + ";" +
                "  window.__carHomeSelectedQualityLevel = '" + currentQualityLevel + "';" +
                "  window.__carHomeInAd = false;" +
                "  window.__carHomeWasMutedByAd = false;" +
                "  window.__carHomeLastQualitySetTime = 0;" +
                "  window.__lastReportedQuality = '';" +
                "  function handleVideoCompletion(v, p) {" +
                "    try {" +
                "      if (window.__carHomeJustCompleted) return;" +
                "      window.__carHomeJustCompleted = true;" +
                "      window.__carHomeLockStop = true;" +
                "      setTimeout(function() { window.__carHomeJustCompleted = false; }, 8000);" +
                "      setTimeout(function() { window.__carHomeLockStop = false; }, 8000);" +
                "      if (v) {" +
                "        v.pause();" +
                "        window.__carHomeInternalSetting = true;" +
                "        try { v.currentTime = Math.max(0, v.duration - 1.5); } catch(e) {}" +
                "        window.__carHomeInternalSetting = false;" +
                "      }" +
                "      if (p) {" +
                "        if (typeof p.pauseVideo === 'function') { p.pauseVideo(); }" +
                "        if (typeof p.stopVideo === 'function') { p.stopVideo(); }" +
                "        if (typeof p.setAutonavState === 'function') { p.setAutonavState(1); }" +
                "        p.nextVideo = function() { if (typeof p.pauseVideo === 'function') p.pauseVideo(); return false; };" +
                "      }" +
                "      var cancelSelector = '.ytp-autonav-endscreen-cancel-button, .ytp-autonav-cancel-button, .autonav-endscreen-button-cancel, ytm-autonav-bar button, .autonav-endscreen button';" +
                "      var purgeEndscreens = function() {" +
                "        var cancelBtns = document.querySelectorAll(cancelSelector);" +
                "        for (var c = 0; c < cancelBtns.length; c++) {" +
                "          try { cancelBtns[c].click(); } catch(e) {}" +
                "        }" +
                "        var overlays = document.querySelectorAll('.ytp-ce-element, .ytp-ce-covering-overlay, .ytp-autonav-endscreen, ytm-endscreen-renderer, ytm-autonav-bar, .autonav-endscreen, .ytp-pause-overlay, ytm-playlist-endscreen-renderer, .ytp-autonav-endscreen-countdown-overlay, tp-yt-paper-dialog, ytm-confirm-dialog-renderer, ytd-confirm-dialog-renderer');" +
                "        for (var o = 0; o < overlays.length; o++) {" +
                "          try { overlays[o].remove(); } catch(e) {}" +
                "        }" +
                "      };" +
                "      purgeEndscreens();" +
                "      setTimeout(purgeEndscreens, 50);" +
                "      setTimeout(purgeEndscreens, 150);" +
                "      setTimeout(purgeEndscreens, 300);" +
                "      var match = window.location.href.match(/[?&]v=([^&]+)/);" +
                "      var vId = match ? match[1] : '';" +
                "      if (!vId && p && typeof p.getVideoData === 'function') {" +
                "        var vd = p.getVideoData();" +
                "        if (vd && vd.video_id) vId = vd.video_id;" +
                "      }" +
                "      var title = document.title ? document.title.replace(' - YouTube', '').trim() : '';" +
                "      if (window.AndroidHistory) {" +
                "        if (vId && v && v.duration && v.duration > 0) {" +
                "          var dMs = Math.round(v.duration * 1000);" +
                "          window.AndroidHistory.onSaveProgress(vId, title, '', '', dMs, dMs);" +
                "        }" +
                "        if (typeof window.AndroidHistory.onVideoCompleted === 'function') {" +
                "          window.AndroidHistory.onVideoCompleted(vId || 'completed', title || '');" +
                "        }" +
                "      }" +
                "      renderChannelProgressBars();" +
                "    } catch(e) {}" +
                "  }" +
                "  function hookMoviePlayer(p) {" +
                "    if (!p || p.__carHomePlayerHooked) return;" +
                "    p.__carHomePlayerHooked = true;" +
                "    p.nextVideo = function() {" +
                "      if (typeof p.pauseVideo === 'function') p.pauseVideo();" +
                "      return false;" +
                "    };" +
                "    var origSeekTo = p.seekTo;" +
                "    if (typeof origSeekTo === 'function') {" +
                "      p.seekTo = function(seconds, allowSeekAhead) {" +
                "        var dur = typeof p.getDuration === 'function' ? p.getDuration() : 0;" +
                "        var v = document.querySelector('video');" +
                "        if (!dur && v && v.duration) dur = v.duration;" +
                "        if (!window.__carHomeInAd && dur && isFinite(dur) && dur > 3.0) {" +
                "          if (seconds >= dur - 1.2) {" +
                "            seconds = Math.max(0, dur - 1.5);" +
                "            origSeekTo.call(p, seconds, allowSeekAhead);" +
                "            if (v) v.pause();" +
                "            if (typeof p.pauseVideo === 'function') p.pauseVideo();" +
                "            handleVideoCompletion(v, p);" +
                "            return;" +
                "          }" +
                "        }" +
                "        return origSeekTo.apply(p, arguments);" +
                "      };" +
                "    }" +
                "  }" +
                "  if (!window.__carHomeEndedCaptureHooked) {" +
                "    window.__carHomeEndedCaptureHooked = true;" +
                "    window.addEventListener('ended', function(e) {" +
                "      if (window.__carHomeInAd) return;" +
                "      e.stopImmediatePropagation();" +
                "      e.stopPropagation();" +
                "      var v = e.target;" +
                "      if (v && typeof v.pause === 'function') v.pause();" +
                "      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (p && typeof p.pauseVideo === 'function') p.pauseVideo();" +
                "      handleVideoCompletion(v, p);" +
                "    }, true);" +
                "  }" +
                "  if (!window.__carHomeYtNavHooked) {" +
                "    window.__carHomeYtNavHooked = true;" +
                "    var blockNavIfJustCompleted = function(e) {" +
                "      if (window.__carHomeLockStop) {" +
                "        if (e.preventDefault) e.preventDefault();" +
                "        if (e.stopImmediatePropagation) e.stopImmediatePropagation();" +
                "        if (e.stopPropagation) e.stopPropagation();" +
                "        return false;" +
                "      }" +
                "    };" +
                "    window.addEventListener('yt-navigate-start', blockNavIfJustCompleted, true);" +
                "    window.addEventListener('yt-navigate', blockNavIfJustCompleted, true);" +
                "    window.addEventListener('yt-page-data-updated', function(e) {" +
                "      if (window.__carHomeLockStop) {" +
                "        if (e.preventDefault) e.preventDefault();" +
                "        if (e.stopImmediatePropagation) e.stopImmediatePropagation();" +
                "      }" +
                "    }, true);" +
                "  }" +
                "  try {" +
                "    var origDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime');" +
                "    if (origDesc && origDesc.set && !window.__carHomeTimeHooked) {" +
                "      window.__carHomeTimeHooked = true;" +
                "      Object.defineProperty(HTMLMediaElement.prototype, 'currentTime', {" +
                "        get: function() { return origDesc.get.call(this); }," +
                "        set: function(val) {" +
                "          if (!window.__carHomeInternalSetting && !window.__carHomeInAd && this.duration && isFinite(this.duration) && this.duration > 3.0) {" +
                "            var curRate = this.playbackRate || 1.0;" +
                "            var margin = Math.max(1.2, curRate * 0.8);" +
                "            if (val >= this.duration - margin) {" +
                "              val = Math.max(0, this.duration - 1.5);" +
                "              this.pause();" +
                "              var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "              if (p && typeof p.pauseVideo === 'function') { p.pauseVideo(); }" +
                "              handleVideoCompletion(this, p);" +
                "            }" +
                "          }" +
                "          return origDesc.set.call(this, val);" +
                "        }," +
                "        configurable: true" +
                "      });" +
                "    }" +
                "  } catch(e) {}" +
                "  try {" +
                "    if (!window.__carHomeHistoryHooked && window.history && history.pushState) {" +
                "      window.__carHomeHistoryHooked = true;" +
                "      var origPush = history.pushState;" +
                "      var origReplace = history.replaceState;" +
                "      history.pushState = function(state, title, url) {" +
                "        if (window.__carHomeLockStop) { return; }" +
                "        return origPush.apply(this, arguments);" +
                "      };" +
                "      if (origReplace) {" +
                "        history.replaceState = function(state, title, url) {" +
                "          if (window.__carHomeLockStop) { return; }" +
                "          return origReplace.apply(this, arguments);" +
                "        };" +
                "      }" +
                "    }" +
                "  } catch(e) {}" +
                "  function syncStorageConfig(q) {" +
                "    try {" +
                "      if (q) {" +
                "        var obj = { data: q, expiration: Date.now() + 315360000000, creation: Date.now() };" +
                "        var str = JSON.stringify(obj);" +
                "        localStorage.setItem('yt-player-quality', str);" +
                "        sessionStorage.setItem('yt-player-quality', str);" +
                "      }" +
                "      var autoNavOff = { data: false, expiration: Date.now() + 315360000000, creation: Date.now() };" +
                "      localStorage.setItem('yt-player-autonav', JSON.stringify(autoNavOff));" +
                "      sessionStorage.setItem('yt-player-autonav', JSON.stringify(autoNavOff));" +
                "      localStorage.setItem('yt-autonav-canceled', 'true');" +
                "    } catch(e) {}" +
                "  }" +
                "  syncStorageConfig('" + currentQualityLevel + "');" +
                "  function disableAutoplay() {" +
                "    try {" +
                "      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (p && typeof p.setAutonavState === 'function') { p.setAutonavState(1); }" +
                "      var autonavToggle = document.querySelector('button[aria-label*=\"자동재생\"], button[aria-label*=\"Autoplay\"], ytm-autonav-toggle-button-renderer button, .autonav-toggle, [aria-label*=\"자동 재생\"]');" +
                "      if (autonavToggle) {" +
                "        var isPressed = autonavToggle.getAttribute('aria-pressed');" +
                "        if (isPressed === 'true' || autonavToggle.classList.contains('active')) {" +
                "          autonavToggle.click();" +
                "        }" +
                "      }" +
                "    } catch(e) {}" +
                "  }" +
                "  try {" +
                "    var styleId = '__carHomeAdStyles';" +
                "    var style = document.getElementById(styleId);" +
                "    if (!style) {" +
                "      style = document.createElement('style');" +
                "      style.id = styleId;" +
                "      style.textContent = '#masthead-ad, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, .ytp-ad-overlay-container, ytm-companion-ad-renderer, #player-ads, .ytp-ad-message-container, ytm-paid-content-overlay-renderer, ytd-banner-promo-renderer, ytd-ad-slot-renderer, .ytp-ad-overlay-slot, ytd-enforcement-message-view-model, tp-yt-paper-dialog:has(.yt-mealbar-promo-renderer), .yt-playability-error-supported-renderers, ytm-ad-preview-renderer, .ytp-ad-module, .ytp-ad-preview-text, .ytp-ad-duration-remaining, .ytp-autonav-endscreen, ytm-autonav-bar, .autonav-endscreen, .ytp-ce-element, .ytp-ce-covering-overlay, .ytp-ce-element-show, .ytp-ce-channel, .ytp-ce-video, .ytp-ce-expanding-overlay, .ytp-endscreen-content, .ytp-autonav-endscreen-countdown-overlay, .ytp-autonav-endscreen-button-container, .ytp-autonav-endscreen-cancel-button, .ytp-autonav-cancel-button, ytm-endscreen-renderer, ytm-endscreen-element, ytm-autonav-endscreen, ytm-playlist-endscreen-renderer, ytm-autonav-toast, .ytp-pause-overlay, .ytp-pause-overlay-container, ytm-subscription-notification-toggle-button-renderer { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; } .ch-progress-container { position: absolute !important; bottom: 0 !important; left: 0 !important; width: 100% !important; height: 4px !important; background: rgba(0,0,0,0.6) !important; z-index: 2147483646 !important; pointer-events: none !important; } .ch-progress-bar { height: 100% !important; background: #FF0000 !important; } .ch-badge-completed { position: absolute !important; top: 6px !important; right: 6px !important; background: #0B6623 !important; color: #FFFFFF !important; font-size: 11px !important; font-weight: bold !important; padding: 2px 7px !important; border-radius: 4px !important; border: 1.5px solid #2ECC71 !important; box-shadow: 0 2px 6px rgba(0,0,0,0.85) !important; z-index: 2147483647 !important; pointer-events: none !important; display: block !important; line-height: 1.3 !important; white-space: nowrap !important; }';" +
                "      (document.head || document.documentElement).appendChild(style);" +
                "    }" +
                "  } catch(e) {}" +
                "  function applyQuality() {" +
                "    try {" +
                "      var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (player) {" +
                "        var targetQ = window.__carHomeSelectedQualityLevel || '" + currentQualityLevel + "';" +
                "        if (typeof player.setPlaybackQualityRange === 'function') { player.setPlaybackQualityRange(targetQ, targetQ); }" +
                "        if (typeof player.setPlaybackQuality === 'function') { player.setPlaybackQuality(targetQ); }" +
                "        window.__carHomeLastQualitySetTime = Date.now();" +
                "      }" +
                "    } catch(e) {}" +
                "  }" +
                "  function checkAndReportSpeed(v) {" +
                "    if (!v || window.__carHomeInAd) return;" +
                "    var r = v.playbackRate;" +
                "    if (typeof r !== 'number' || isNaN(r) || r <= 0) return;" +
                "    r = Math.round(r * 100) / 100;" +
                "    if (window.__lastReportedSpeed !== r) {" +
                "      window.__lastReportedSpeed = r;" +
                "      if (window.AndroidSpeed) {" +
                "        window.AndroidSpeed.onSpeedDetected(r);" +
                "      }" +
                "    }" +
                "  }" +
                "  function checkAndReportQuality(p, v) {" +
                "    if (window.__carHomeInAd) return;" +
                "    var label = '';" +
                "    if (v && v.videoHeight > 0 && v.videoWidth > 0) {" +
                "      var minDim = Math.min(v.videoHeight, v.videoWidth);" +
                "      if (minDim >= 900) label = '1080P';" +
                "      else if (minDim >= 600) label = '720P';" +
                "      else if (minDim >= 420) label = '480P';" +
                "      else if (minDim >= 300) label = '360P';" +
                "      else if (minDim >= 200) label = '240P';" +
                "      else if (minDim > 0) label = '144P';" +
                "    }" +
                "    if (!label && p && typeof p.getPlaybackQuality === 'function') {" +
                "      var q = p.getPlaybackQuality();" +
                "      if (q && q !== 'unknown' && q !== 'auto') {" +
                "        if (q === 'hd2160' || q === 'hd1440' || q === 'hd1080' || q === 'highres') label = '1080P';" +
                "        else if (q === 'hd720') label = '720P';" +
                "        else if (q === 'large') label = '480P';" +
                "        else if (q === 'medium') label = '360P';" +
                "        else if (q === 'small') label = '240P';" +
                "        else if (q === 'tiny') label = '144P';" +
                "      }" +
                "    }" +
                "    if (label && label !== window.__lastReportedQuality) {" +
                "      window.__lastReportedQuality = label;" +
                "      if (window.AndroidQuality) {" +
                "        window.AndroidQuality.onQualityDetected(label);" +
                "      }" +
                "    }" +
                "  }" +
                "  var lastSaveProgressTime = 0;" +
                "  function checkAndSaveProgress(v) {" +
                "    if (!v || window.__carHomeInAd || !window.AndroidHistory) return;" +
                "    var now = Date.now();" +
                "    if (now - lastSaveProgressTime < 3000) return;" +
                "    lastSaveProgressTime = now;" +
                "    try {" +
                "      var match = window.location.href.match(/[?&]v=([^&]+)/);" +
                "      var vId = match ? match[1] : '';" +
                "      if (!vId) {" +
                "        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "        if (p && typeof p.getVideoData === 'function') {" +
                "          var vd = p.getVideoData();" +
                "          if (vd && vd.video_id) vId = vd.video_id;" +
                "        }" +
                "      }" +
                "      if (vId && v.duration && v.duration > 0 && v.currentTime > 0) {" +
                "        if (!v.__carHomeResumed && v.currentTime < 4.0) {" +
                "          var savedMs = window.AndroidHistory.getSavedPosition(vId);" +
                "          if (savedMs && savedMs > 5000) {" +
                "            return;" +
                "          }" +
                "        }" +
                "        var title = document.title ? document.title.replace(' - YouTube', '') : '';" +
                "        var dMs = Math.round(v.duration * 1000);" +
                "        var pMs = Math.round(v.currentTime * 1000);" +
                "        window.AndroidHistory.onSaveProgress(vId, title, '', '', dMs, pMs);" +
                "      }" +
                "    } catch(e) {}" +
                "  }" +
                "  function checkAndResume(v) {" +
                "    if (!v || window.__carHomeInAd || v.__carHomeResumed || !window.AndroidHistory) return;" +
                "    try {" +
                "      var match = window.location.href.match(/[?&]v=([^&]+)/);" +
                "      var vId = match ? match[1] : '';" +
                "      if (vId) {" +
                "        var savedMs = window.AndroidHistory.getSavedPosition(vId);" +
                "        if (savedMs && savedMs > 4000) {" +
                "          v.__carHomeResumed = true;" +
                "          var targetSec = savedMs / 1000.0;" +
                "          if (Math.abs(v.currentTime - targetSec) > 3.0) {" +
                "            v.currentTime = targetSec;" +
                "            var m = Math.floor(targetSec / 60);" +
                "            var s = Math.floor(targetSec % 60);" +
                "            var timeStr = (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);" +
                "            window.AndroidHistory.onResumeToast(timeStr);" +
                "          }" +
                "        } else {" +
                "          v.__carHomeResumed = true;" +
                "        }" +
                "      }" +
                "    } catch(e) {}" +
                "  }" +
                "  var lastRenderProgressTime = 0;" +
                "  function renderChannelProgressBars() {" +
                "    var now = Date.now();" +
                "    if (now - lastRenderProgressTime < 400 || !window.AndroidHistory) return;" +
                "    lastRenderProgressTime = now;" +
                "    try {" +
                "      var watchedJsonStr = window.AndroidHistory.getWatchedListJson();" +
                "      if (!watchedJsonStr) return;" +
                "      var watchedMap = JSON.parse(watchedJsonStr);" +
                "      var links = document.querySelectorAll('a[href*=\"/watch?v=\"], a[href*=\"/shorts/\"]');" +
                "      for (var i = 0; i < links.length; i++) {" +
                "        var a = links[i];" +
                "        var href = a.getAttribute('href') || '';" +
                "        var m = href.match(/(?:watch\\?v=|\\/shorts\\/)([^&?\\/]+)/);" +
                "        if (!m) continue;" +
                "        var vid = m[1];" +
                "        var percent = watchedMap[vid];" +
                "        if (percent === undefined || percent === null) continue;" +
                "        var thumb = null;" +
                "        var img = a.querySelector('img');" +
                "        if (!img) {" +
                "          var card = a.closest('ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-media-item, ytm-channel-video-renderer, ytm-video-card-renderer, ytm-reel-item-renderer, ytm-shelf-renderer, ytm-channel-featured-video-renderer, ytd-rich-item-renderer, ytd-video-renderer, ytd-grid-video-renderer, ytd-reel-item-renderer, .compact-media-item, [class*=\"video-renderer\"], [class*=\"media-item\"], [class*=\"rich-item\"]');" +
                "          if (card) {" +
                "            img = card.querySelector('img');" +
                "          }" +
                "        }" +
                "        if (img) {" +
                "          thumb = img.closest('ytm-thumbnail-cover, .media-item-thumbnail-container, ytm-custom-thumbnail, ytd-thumbnail, .video-thumbnail-container-compact, .compact-media-item-image');" +
                "          if (!thumb) {" +
                "            var p = img.parentElement;" +
                "            if (p && p.classList && p.classList.contains('video-thumbnail-bg')) {" +
                "              thumb = p.parentElement;" +
                "            } else {" +
                "              thumb = p;" +
                "            }" +
                "          }" +
                "        } else {" +
                "          thumb = a.querySelector('.media-item-thumbnail-container, ytm-thumbnail-cover, ytd-thumbnail');" +
                "        }" +
                "        if (!thumb && a.offsetWidth > 60 && a.offsetHeight > 40) {" +
                "          thumb = a;" +
                "        }" +
                "        if (!thumb) continue;" +
                "        if (thumb.classList && thumb.classList.contains('video-thumbnail-bg') && thumb.parentElement) {" +
                "          thumb = thumb.parentElement;" +
                "        }" +
                "        try {" +
                "          var curPos = window.getComputedStyle(thumb).position;" +
                "          if (curPos === 'static') {" +
                "            thumb.style.position = 'relative';" +
                "          }" +
                "        } catch(e) {" +
                "          thumb.style.position = 'relative';" +
                "        }" +
                "        var cont = thumb.querySelector('.ch-progress-container');" +
                "        if (!cont && percent > 0) {" +
                "          cont = document.createElement('div');" +
                "          cont.className = 'ch-progress-container';" +
                "          var bar = document.createElement('div');" +
                "          bar.className = 'ch-progress-bar';" +
                "          cont.appendChild(bar);" +
                "          thumb.appendChild(cont);" +
                "        }" +
                "        if (cont) {" +
                "          var bar = cont.querySelector('.ch-progress-bar');" +
                "          if (bar) bar.style.width = percent + '%';" +
                "        }" +
                "        var badge = thumb.querySelector('.ch-badge-completed');" +
                "        if (percent >= 90) {" +
                "          if (!badge) {" +
                "            badge = document.createElement('div');" +
                "            badge.className = 'ch-badge-completed';" +
                "            badge.textContent = '✔ 시청 완료';" +
                "            thumb.appendChild(badge);" +
                "          }" +
                "        } else if (badge) {" +
                "          badge.remove();" +
                "        }" +
                "      }" +
                "    } catch(e) {}" +
                "  }" +
                "  function attachVideoHooks(v) {" +
                "    if (!v || v.__carHomeHooksAttached) return;" +
                "    v.__carHomeHooksAttached = true;" +
                "    var initP = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "    if (initP) hookMoviePlayer(initP);" +
                "    v.addEventListener('ratechange', function() {" +
                "      checkAndReportSpeed(v);" +
                "    });" +
                "    v.addEventListener('resize', function() {" +
                "      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (p) hookMoviePlayer(p);" +
                "      checkAndReportQuality(p, v);" +
                "    });" +
                "    v.addEventListener('loadedmetadata', function() {" +
                "      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (p) hookMoviePlayer(p);" +
                "      checkAndReportQuality(p, v);" +
                "      checkAndReportSpeed(v);" +
                "    });" +
                "    v.addEventListener('play', function() {" +
                "      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (p) hookMoviePlayer(p);" +
                "      if (window.__carHomeLockStop) {" +
                "        v.pause();" +
                "        if (p && typeof p.pauseVideo === 'function') { p.pauseVideo(); }" +
                "      }" +
                "    });" +
                "    var handleSeekNearEnd = function() {" +
                "      try {" +
                "        if (window.__carHomeInAd) return;" +
                "        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.getElementById('player');" +
                "        if (p) hookMoviePlayer(p);" +
                "        if (v.duration && isFinite(v.duration) && v.duration > 2.0) {" +
                "          var curRate = v.playbackRate || 1.0;" +
                "          var margin = Math.max(1.2, curRate * 0.8);" +
                "          if (v.currentTime >= v.duration - margin) {" +
                "            v.currentTime = Math.max(0, v.duration - 1.5);" +
                "            v.pause();" +
                "            if (p && typeof p.pauseVideo === 'function') { p.pauseVideo(); }" +
                "            handleVideoCompletion(v, p);" +
                "          }" +
                "        }" +
                "      } catch(e) {}" +
                "    };" +
                "    v.addEventListener('seeking', handleSeekNearEnd);" +
                "    v.addEventListener('seeked', handleSeekNearEnd);" +
                "    v.addEventListener('timeupdate', function() {" +
                "      checkAndSaveProgress(v);" +
                "      try {" +
                "        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.getElementById('player');" +
                "        if (p) hookMoviePlayer(p);" +
                "        var isAdNow = window.__carHomeInAd || (p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));" +
                "        if (!isAdNow && v.duration && isFinite(v.duration) && v.duration > 3.0) {" +
                "          var curSpeed = v.playbackRate || 1.0;" +
                "          var margin = Math.max(1.2, curSpeed * 0.8);" +
                "          if (v.currentTime >= v.duration - margin) {" +
                "            handleVideoCompletion(v, p);" +
                "          }" +
                "        }" +
                "      } catch(e) {}" +
                "    });" +
                "    v.addEventListener('pause', function() {" +
                "      checkAndSaveProgress(v);" +
                "    });" +
                "    v.addEventListener('ended', function() {" +
                "      try {" +
                "        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.getElementById('player');" +
                "        if (p) hookMoviePlayer(p);" +
                "        var isAdNow = window.__carHomeInAd || (p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));" +
                "        if (isAdNow) {" +
                "          setTimeout(safeSkipAd, 50);" +
                "          return;" +
                "        }" +
                "        handleVideoCompletion(v, p);" +
                "      } catch(e) {}" +
                "    });" +
                "    v.addEventListener('playing', function() {" +
                "      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      applyQuality();" +
                "      checkAndReportQuality(p, v);" +
                "      checkAndReportSpeed(v);" +
                "      checkAndResume(v);" +
                "    });" +
                "  }" +
                "  var modernSkipSelectorStr = '.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button-modern, .ytm-skip-ad-button, .videoAdUiSkipButton, button.ytp-ad-skip-button-text, button.ytp-skip-ad-button-modern, .ytp-ad-skip-button-container button, ytm-skip-ad-button-renderer button, .ytp-ad-skip-button-slot button, .ytp-ad-skip-button-container-modern button, ytm-skip-ad-button-renderer, button[class*=\"skip\"], button[aria-label*=\"건너뛰기\"], button[aria-label*=\"Skip\"], button[aria-label*=\"skip\"], [id^=\"skip-button\"], .ytp-ad-skip-slot button';" +
                "  function performClick(el) {" +
                "    if (!el) return;" +
                "    try { el.click(); } catch(e) {}" +
                "    try {" +
                "      var evt = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });" +
                "      el.dispatchEvent(evt);" +
                "    } catch(e) {}" +
                "    try {" +
                "      var pd = new PointerEvent('pointerdown', { bubbles: true, cancelable: true, view: window });" +
                "      el.dispatchEvent(pd);" +
                "      var pu = new PointerEvent('pointerup', { bubbles: true, cancelable: true, view: window });" +
                "      el.dispatchEvent(pu);" +
                "    } catch(e) {}" +
                "  }" +
                "  function safeSkipAd() {" +
                "    try {" +
                "      var antiPopups = document.querySelectorAll('ytd-enforcement-message-view-model, tp-yt-paper-dialog:has(.yt-mealbar-promo-renderer), tp-yt-paper-dialog[role=\"dialog\"]:has(#confirm-button), yt-playability-error-supported-renderers');" +
                "      for (var pi = 0; pi < antiPopups.length; pi++) {" +
                "        try {" +
                "          antiPopups[pi].remove();" +
                "          var pv = document.querySelector('video');" +
                "          if (pv && pv.paused) { pv.play(); }" +
                "        } catch(e) {}" +
                "      }" +
                "      var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.getElementById('player');" +
                "      if (player) hookMoviePlayer(player);" +
                "      var isAd = false;" +
                "      if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {" +
                "        isAd = true;" +
                "      }" +
                "      if (!isAd) {" +
                "        var adOverlay = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytm-player-ad, .ytp-ad-module, .video-ads, .ytm-ad-preview-text, .ytp-ad-duration-remaining');" +
                "        if (adOverlay && (adOverlay.offsetWidth > 0 || adOverlay.offsetHeight > 0 || adOverlay.classList.contains('ad-showing') || adOverlay.classList.contains('ad-interrupting'))) {" +
                "          isAd = true;" +
                "        }" +
                "      }" +
                "      var video = document.querySelector('video');" +
                "      if (video) {" +
                "        attachVideoHooks(video);" +
                "      }" +
                "      if (isAd && video) {" +
                "        var clickedAny = false;" +
                "        var btns = document.querySelectorAll(modernSkipSelectorStr);" +
                "        for (var b = 0; b < btns.length; b++) {" +
                "          var btn = btns[b];" +
                "          if (btn) {" +
                "            performClick(btn);" +
                "            clickedAny = true;" +
                "          }" +
                "        }" +
                "        var dismissBtns = document.querySelectorAll('yt-button-renderer#dismiss-button, #dismiss-button, .ytp-ad-overlay-close-button');" +
                "        for (var j = 0; j < dismissBtns.length; j++) {" +
                "          try { performClick(dismissBtns[j]); } catch(e) {}" +
                "        }" +
                "        if (player && typeof player.skipAd === 'function') {" +
                "          try { player.skipAd(); } catch(e) {}" +
                "        }" +
                "        if (!window.__carHomeInAd) {" +
                "          window.__carHomeInAd = true;" +
                "          window.__carHomeWasMutedByAd = !video.muted;" +
                "          try {" +
                "            if (video.duration && isFinite(video.duration) && video.duration > 0.5 && video.duration <= 180) {" +
                "              video.currentTime = Math.max(0, video.duration - 0.1);" +
                "            }" +
                "          } catch(e) {}" +
                "        }" +
                "        video.muted = true;" +
                "        try {" +
                "          if (video.playbackRate < 16.0) {" +
                "            video.playbackRate = 16.0;" +
                "          }" +
                "        } catch(e) {}" +
                "        if (window.AndroidAdBlock && !window.__carHomeNotifiedThisAd) {" +
                "          window.__carHomeNotifiedThisAd = true;" +
                "          window.AndroidAdBlock.onAdSkipped(clickedAny ? 'skip_button_clicked' : 'ad_accelerated');" +
                "        }" +
                "      } else if (!isAd) {" +
                "        window.__carHomeNotifiedThisAd = false;" +
                "        if (window.__carHomeInAd) {" +
                "          window.__carHomeInAd = false;" +
                "          if (video) {" +
                "            if (window.__carHomeWasMutedByAd) {" +
                "              video.muted = false;" +
                "              window.__carHomeWasMutedByAd = false;" +
                "            }" +
                "            var targetSpeed = window.__carHomeUserSpeed || 1.0;" +
                "            try {" +
                "              video.playbackRate = targetSpeed;" +
                "              if (player && typeof player.setPlaybackRate === 'function') {" +
                "                player.setPlaybackRate(targetSpeed);" +
                "              }" +
                "            } catch(e) {}" +
                "          }" +
                "        }" +
                "        if (video) {" +
                "          checkAndReportSpeed(video);" +
                "          checkAndReportQuality(player, video);" +
                "          checkAndSaveProgress(video);" +
                "          checkAndResume(video);" +
                "        }" +
                "        disableAutoplay();" +
                "        renderChannelProgressBars();" +
                "      }" +
                "    } catch(err) {}" +
                "  }" +
                "  safeSkipAd();" +
                "  if (!window.__carHomeObserver) {" +
                "    var obsTimeout = null;" +
                "    var obs = new MutationObserver(function() {" +
                "      if (!obsTimeout) {" +
                "        obsTimeout = setTimeout(function() {" +
                "          obsTimeout = null;" +
                "          safeSkipAd();" +
                "        }, 80);" +
                "      }" +
                "    });" +
                "    obs.observe(document.body || document.documentElement, {" +
                "      childList: true," +
                "      subtree: true," +
                "      attributes: true," +
                "      attributeFilter: ['class', 'src']" +
                "    });" +
                "    window.__carHomeObserver = obs;" +
                "  }" +
                "  if (!window.carHomeAdBlockScheduled) {" +
                "    window.carHomeAdBlockScheduled = true;" +
                "    var runAdCheckLoop = function() {" +
                "      safeSkipAd();" +
                "      var loopDelay = window.__carHomeInAd ? 150 : 1000;" +
                "      setTimeout(runAdCheckLoop, loopDelay);" +
                "    };" +
                "    runAdCheckLoop();" +
                "  }" +
                "  if (!window.__carHomeQualityEventAttached) {" +
                "    window.__carHomeQualityEventAttached = true;" +
                "    var onNavigate = function() {" +
                "      window.__carHomeInAd = false;" +
                "      window.__carHomeWasMutedByAd = false;" +
                "      window.__carHomeLastQualitySetTime = 0;" +
                "      window.__lastReportedQuality = '';" +
                "      window.__lastReportedSpeed = -1;" +
                "      window.__carHomeNotifiedThisAd = false;" +
                "      var v = document.querySelector('video');" +
                "      if (v) { v.__carHomeResumed = false; }" +
                "      var targetQ = window.__carHomeSelectedQualityLevel || '" + currentQualityLevel + "';" +
                "      syncStorageConfig(targetQ);" +
                "      disableAutoplay();" +
                "      safeSkipAd();" +
                "      renderChannelProgressBars();" +
                "      setTimeout(renderChannelProgressBars, 600);" +
                "      setTimeout(renderChannelProgressBars, 1500);" +
                "    };" +
                "    document.addEventListener('yt-navigate-start', function(e) {" +
                "      if (window.__carHomeLockStop) {" +
                "        if (e && typeof e.preventDefault === 'function') e.preventDefault();" +
                "        if (e && typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();" +
                "      }" +
                "    }, true);" +
                "    document.addEventListener('yt-navigate-finish', onNavigate);" +
                "    document.addEventListener('sp-navigate-finish', onNavigate);" +
                "    window.addEventListener('scroll', function() { renderChannelProgressBars(); }, { passive: true });" +
                "    setTimeout(renderChannelProgressBars, 600);" +
                "    setTimeout(renderChannelProgressBars, 1500);" +
                "    setTimeout(renderChannelProgressBars, 3000);" +
                "  }" +
                "})();";
        view.evaluateJavascript(adBlockJs, null);
    }

    // 화면 회전 감지 시 영상이 끊기지 않고 메뉴바 위치가 안정적으로 유지되도록 처리
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateMenuLayout(newConfig.orientation);
    }

    // 상단 2줄 툴바 (1행: 배속 & RAM / 2행: 해상도) 상시 상단 고정 레이아웃
    private void updateMenuLayout(int orientation) {
        LinearLayout menuLayout = findViewById(R.id.menuLayout);
        LinearLayout menuGroup1 = findViewById(R.id.menuGroup1);
        LinearLayout menuGroup3 = findViewById(R.id.menuGroup3);
        LinearLayout browserRootLayout = findViewById(R.id.browserRootLayout);

        if (menuLayout == null || menuGroup1 == null || menuGroup3 == null || browserRootLayout == null || webView == null) {
            return;
        }

        // 가로, 세로, 분할 화면 모두 항상 상단 2줄 수평 레이아웃으로 고정 (좌측 이동 방지)
        browserRootLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setOrientation(LinearLayout.VERTICAL);

        int toolbarHeightDp = 84;
        menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (toolbarHeightDp * getResources().getDisplayMetrics().density)));

        menuGroup1.setOrientation(LinearLayout.HORIZONTAL);
        menuGroup1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        menuGroup3.setOrientation(LinearLayout.HORIZONTAL);
        menuGroup3.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        webView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setButtonParams(menuGroup1, false);
        setButtonParams(menuGroup3, false);
    }

    private void setButtonParams(LinearLayout group, boolean isLandscape) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            // RAM 배지 버튼인 경우 가중치를 약간 더 주어 텍스트가 잘리지 않게 설정
            float weight = (child.getId() == R.id.btnRamStatus) ? 1.2f : 1.0f;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
            params.setMargins(3, 3, 3, 3);
            child.setLayoutParams(params);
        }
    }

    private void setVideoSpeed(float speed) {
        currentPlaybackSpeed = speed;
        getSharedPreferences("browser_prefs", MODE_PRIVATE)
                .edit()
                .putFloat("playback_speed", speed)
                .apply();

        if (webView != null) {
            String js = "try { " +
                    "  window.__carHomeUserSpeed = " + speed + "; " +
                    "  var videos = document.getElementsByTagName('video'); " +
                    "  if (videos && videos.length > 0 && !window.__carHomeInAd) { " +
                    "    videos[0].playbackRate = " + speed + "; " +
                    "  } " +
                    "} catch(e) {}";
            webView.evaluateJavascript(js, null);
            Toast.makeText(this, speed + "배속 변경 요청 ⚡", Toast.LENGTH_SHORT).show();
        }
    }

    private void setVideoQuality(String qualityLevel, String label) {
        currentQualityLevel = qualityLevel;
        currentQualityLabel = label;
        getSharedPreferences("browser_prefs", MODE_PRIVATE)
                .edit()
                .putString("quality_label", label)
                .putString("quality_level", qualityLevel)
                .apply();

        if (webView != null) {
            String js = "(function() {" +
                    "  try {" +
                    "    window.__carHomeUserSelectedQuality = true;" +
                    "    window.__carHomeSelectedQualityLevel = '" + qualityLevel + "';" +
                    "    window.__carHomeLastQualitySetTime = Date.now();" +
                    "    if (typeof syncStorageConfig === 'function') {" +
                    "      syncStorageConfig('" + qualityLevel + "');" +
                    "    }" +
                    "    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                    "    if (p) {" +
                    "      if (typeof p.setPlaybackQualityRange === 'function') {" +
                    "        p.setPlaybackQualityRange('" + qualityLevel + "', '" + qualityLevel + "');" +
                    "      }" +
                    "      if (typeof p.setPlaybackQuality === 'function') {" +
                    "        p.setPlaybackQuality('" + qualityLevel + "');" +
                    "      }" +
                    "    }" +
                    "  } catch(e) {}" +
                    "})();";
            webView.evaluateJavascript(js, null);
            Toast.makeText(this, label + " 해상도로 변경 요청 📺", Toast.LENGTH_SHORT).show();
        }
    }

    // 플로팅 메뉴 등에서 채널 클릭 시 실시간 즉시 채널 전환 (전체화면 해제 포함)
    public void openChannelUrl(String url) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            isVideoCompleted = false;
            if (url != null && !url.isEmpty()) {
                lastLoadedUrl = url;
                currentChannelHomeUrl = url;
                if (customView != null) {
                    handleBackNavigation();
                }
                if (webView != null) {
                    webView.loadUrl(url);
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String url = intent.getStringExtra("url");
            if (url != null && !url.isEmpty()) {
                openChannelUrl(url);
            }
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        Log.d(TAG, "onMultiWindowModeChanged: isInMultiWindowMode=" + isInMultiWindowMode);
    }

    @Override
    protected void onDestroy() {
        if (currentInstance == this) {
            currentInstance = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    parent.removeView(webView);
                }
                webView.loadUrl("about:blank");
                webView.stopLoading();
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}