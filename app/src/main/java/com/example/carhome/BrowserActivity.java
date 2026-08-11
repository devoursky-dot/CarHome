package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.ByteArrayInputStream;

public class BrowserActivity extends AppCompatActivity {

    private WebView webView;
    private TextView btnAdBlockStatus;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler adBlockHandler = new Handler(Looper.getMainLooper());
    private long lastAdToastTime = 0;

    // 안드로이드 - 웹뷰 간 실시간 광고 차단 통신 브릿지
    public class AdBlockBridge {
        @JavascriptInterface
        public void onAdSkipped(String reason) {
            runOnUiThread(() -> {
                if (btnAdBlockStatus != null) {
                    btnAdBlockStatus.setText("⚡ 스킵!");
                    btnAdBlockStatus.setTextColor(Color.parseColor("#FFA000")); // 번쩍이는 주황/골드

                    // 2.5초 후 기본 [🛡️ 차단중] 녹색 상태로 복귀
                    adBlockHandler.postDelayed(() -> {
                        if (btnAdBlockStatus != null) {
                            btnAdBlockStatus.setText("🛡️ 차단중");
                            btnAdBlockStatus.setTextColor(Color.parseColor("#3DDC84")); // 상시 녹색
                        }
                    }, 2500);
                }

                long now = System.currentTimeMillis();
                if (now - lastAdToastTime > 2500) {
                    lastAdToastTime = now;
                    Toast.makeText(BrowserActivity.this, "⚡ [광고 차단] 유튜브 광고 즉시 건너뛰기 완료!", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private final Runnable adBlockPeriodicRunnable = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                injectAdBlocker(webView);
                adBlockHandler.postDelayed(this, 1000); // 1초마다 지속 주입하여 영상 전환 시에도 100% 감시 유지
            }
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        btnAdBlockStatus = findViewById(R.id.btnAdBlockStatus);

        // 1. 광고 차단 상태 배지 버튼 (클릭 시 상태 알림)
        if (btnAdBlockStatus != null) {
            btnAdBlockStatus.setOnClickListener(v -> {
                Toast.makeText(this, "🛡️ 유튜브 실시간 광고 차단 엔진 작동 중 (자동 스킵 활성)", Toast.LENGTH_SHORT).show();
            });
        }

        // 2. 툴바 배속 버튼 연결 (1X, 1.5X, 2X)
        TextView btnSpeed1x = findViewById(R.id.btnSpeed1x);
        TextView btnSpeed1_5x = findViewById(R.id.btnSpeed1_5x);
        TextView btnSpeed2x = findViewById(R.id.btnSpeed2x);

        btnSpeed1x.setOnClickListener(v -> setVideoSpeed(1.0f));
        btnSpeed1_5x.setOnClickListener(v -> setVideoSpeed(1.5f));
        btnSpeed2x.setOnClickListener(v -> setVideoSpeed(2.0f));

        // 3. 툴바 시간 건너뛰기 버튼 연결 (<< 1분, < 10초, 10초 >, 1분 >>)
        TextView btnRewind = findViewById(R.id.btnRewind);
        TextView btnForward = findViewById(R.id.btnForward);
        TextView btnRewind10 = findViewById(R.id.btnRewind10);
        TextView btnForward10 = findViewById(R.id.btnForward10);

        btnRewind.setOnClickListener(v -> skipVideo(-60));
        btnForward.setOnClickListener(v -> skipVideo(60));
        btnRewind10.setOnClickListener(v -> skipVideo(-10));
        btnForward10.setOnClickListener(v -> skipVideo(10));

        // 4. 툴바 해상도 조절 버튼 연결 (360P, 480P, 720P, 1080P)
        TextView btnRes360 = findViewById(R.id.btnRes360);
        TextView btnRes480 = findViewById(R.id.btnRes480);
        TextView btnRes720 = findViewById(R.id.btnRes720);
        TextView btnRes1080 = findViewById(R.id.btnRes1080);

        btnRes360.setOnClickListener(v -> setVideoQuality("medium", "360P"));
        btnRes480.setOnClickListener(v -> setVideoQuality("large", "480P"));
        btnRes720.setOnClickListener(v -> setVideoQuality("hd720", "720P"));
        btnRes1080.setOnClickListener(v -> setVideoQuality("hd1080", "1080P"));

        // [키보드/자판 입력 포커스 활성화]
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);
        webView.setOnTouchListener((v, event) -> {
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

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // 동영상 자동 재생 허용
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 자바스크립트 브릿지 등록 (광고 차단 동작 알림용)
        webView.addJavascriptInterface(new AdBlockBridge(), "AndroidAdBlock");

        // [HTML5 전체화면 비디오 재생 지원을 위한 WebChromeClient 장착]
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                // [전체화면 재생 시에도 화면 꺼짐/잠금 완벽 방지]
                view.setKeepScreenOn(true);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                findViewById(R.id.browserRootLayout).setVisibility(View.GONE);
                if (fullscreenContainer != null) {
                    fullscreenContainer.setKeepScreenOn(true);
                    fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                    fullscreenContainer.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenContainer != null && customView != null) {
                    fullscreenContainer.removeView(customView);
                    fullscreenContainer.setVisibility(View.GONE);
                }
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                findViewById(R.id.browserRootLayout).setVisibility(View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            // [강력한 광고 차단 엔진 1단계: 광고 서버 네트워크 패킷 원천 차단]
            private final String[] AD_HOSTS = {
                    "doubleclick.net",
                    "adservice.google.com",
                    "googlesyndication.com",
                    "google-analytics.com",
                    "pagead2.googlesyndication.com",
                    "pubads.g.doubleclick.net",
                    "securepubads.g.doubleclick.net",
                    "youtube.com/api/stats/ads",
                    "youtube.com/pagead/",
                    "youtube.com/ptracking",
                    "/pagead/",
                    "ad_type=",
                    "adformat=",
                    "youtube.com/get_midroll_info"
            };

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                for (String adHost : AD_HOSTS) {
                    if (url.contains(adHost)) {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes())); // 빈 데이터로 대체
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectAdBlocker(view);
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url != null) {
            webView.loadUrl(url);
        }

        // 앱 실행 시 현재 화면 방향(가로/세로)에 맞춰 레이아웃 동적 초기화
        updateMenuLayout(getResources().getConfiguration().orientation);

        // 광고 차단 상시 감시 스케줄러 시작
        adBlockHandler.postDelayed(adBlockPeriodicRunnable, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 브라우저 포커스 복귀 시 화면 꺼짐 방지 플래그 재확인
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // 뒤로가기 통합 처리 메서드 (태블릿 뒤로가기 버튼 지원)
    private void handleBackNavigation() {
        if (customView != null) {
            // 1. 전체화면 모드일 때 ➔ 전체화면 닫기
            if (webView != null && webView.getWebChromeClient() != null) {
                if (fullscreenContainer != null && customView != null) {
                    fullscreenContainer.removeView(customView);
                    fullscreenContainer.setVisibility(View.GONE);
                }
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                findViewById(R.id.browserRootLayout).setVisibility(View.VISIBLE);
            }
        } else if (webView != null && webView.canGoBack()) {
            // 2. 브라우저 이전 페이지가 있을 때 ➔ 이전 페이지로 이동
            webView.goBack();
        } else {
            // 3. 더 이상 이전 페이지가 없을 때 ➔ 브라우저 종료 후 홈(대시보드)으로 복귀
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    // 유튜브 광고를 완벽하게 차단하고 스킵하는 복합 스크립트 주입
    private void injectAdBlocker(WebView view) {
        if (view == null) return;
        String adBlockJs =
                "(function() {" +
                "  if (!window.__carHomeJsonHooked) {" +
                "    window.__carHomeJsonHooked = true;" +
                "    var origParse = JSON.parse;" +
                "    JSON.parse = function() {" +
                "      var r = origParse.apply(this, arguments);" +
                "      if (r && typeof r === 'object') {" +
                "        if (r.adPlacements) delete r.adPlacements;" +
                "        if (r.adSlots) delete r.adSlots;" +
                "        if (r.playerResponse) {" +
                "          if (r.playerResponse.adPlacements) delete r.playerResponse.adPlacements;" +
                "          if (r.playerResponse.adSlots) delete r.playerResponse.adSlots;" +
                "        }" +
                "      }" +
                "      return r;" +
                "    };" +
                "  }" +
                "  function applyAdBlockCss() {" +
                "    if (!document.getElementById('carhome-adblock-style') && (document.head || document.documentElement)) {" +
                "      var style = document.createElement('style');" +
                "      style.id = 'carhome-adblock-style';" +
                "      style.innerHTML = '" +
                "        .ad-showing, .ad-interrupting, .ytp-ad-overlay-container, " +
                "        .ytp-ad-message-container, .ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, " +
                "        ytd-promoted-video-renderer, ytd-banner-promo-renderer, " +
                "        ytd-promoted-sparkles-web-renderer, ytd-display-ad-renderer, " +
                "        ytd-ad-slot-renderer, .ytp-ad-action-interstitial, " +
                "        ytd-action-companion-ad-renderer, ytd-in-feed-ad-layout-renderer, " +
                "        #player-ads, .video-ads, yt-mealbar-promo-renderer, " +
                "        ytd-popup-container yt-mealbar-promo-renderer, " +
                "        .ytp-ad-text, .ytp-ad-preview-container, .ytp-ad-preview-text, " +
                "        .ytp-ad-image-overlay, #offer-module, .ad-container, " +
                "        ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, " +
                "        .ytm-promoted-sparkles-text-search-renderer { " +
                "          display: none !important; " +
                "          visibility: hidden !important; " +
                "          height: 0 !important; " +
                "          opacity: 0 !important; " +
                "          pointer-events: none !important; " +
                "        }';" +
                "      (document.head || document.documentElement).appendChild(style);" +
                "    }" +
                "  }" +
                "  applyAdBlockCss();" +
                "  function eliminateAds() {" +
                "    applyAdBlockCss();" +
                "    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "    var isAd = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, .ytp-ad-module, .video-ads, [class*=\"ad-showing\"], .ytm-player-ad');" +
                "    if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {" +
                "      isAd = true;" +
                "    }" +
                "    var video = document.querySelector('video');" +
                "    if (isAd && video) {" +
                "      try { if (player && typeof player.skipAd === 'function') { player.skipAd(); } } catch(e) {}" +
                "      video.muted = true;" +
                "      video.playbackRate = 16.0;" +
                "      if (video.duration && !isNaN(video.duration) && video.duration > 0 && isFinite(video.duration)) {" +
                "        video.currentTime = Math.max(0, video.duration - 0.1);" +
                "      }" +
                "      if (window.AndroidAdBlock) { window.AndroidAdBlock.onAdSkipped('video_ad_fast_forward'); }" +
                "    }" +
                "    var skipButtons = document.querySelectorAll(" +
                "      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton, ' +" +
                "      '.ytp-ad-skip-button-slot button, button.ytp-ad-skip-button, button.ytp-ad-skip-button-modern, ' +" +
                "      '.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container, .ytp-ad-skip-slot button, ' +" +
                "      'button[class*=\"skip-button\"], button[aria-label*=\"광고 건너뛰기\"], button[aria-label*=\"Skip ad\"], .ytp-skip-ad-button'" +
                "    );" +
                "    for (var i = 0; i < skipButtons.length; i++) {" +
                "      try {" +
                "        skipButtons[i].click();" +
                "        if (window.AndroidAdBlock) { window.AndroidAdBlock.onAdSkipped('skip_button_clicked'); }" +
                "      } catch(e) {}" +
                "    }" +
                "    var dismissBtns = document.querySelectorAll('yt-button-renderer#dismiss-button, button[aria-label=\"닫기\"], button[aria-label=\"Close\"], #dismiss-button');" +
                "    for (var j = 0; j < dismissBtns.length; j++) {" +
                "      try { if (dismissBtns[j].offsetParent !== null) dismissBtns[j].click(); } catch(e) {}" +
                "    }" +
                "  }" +
                "  eliminateAds();" +
                "  if (!window.carHomeAdBlockTimer) {" +
                "    window.carHomeAdBlockTimer = setInterval(eliminateAds, 100);" +
                "  }" +
                "  if (!window.carHomeAdBlockObs && window.MutationObserver && (document.documentElement || document.body)) {" +
                "    window.carHomeAdBlockObs = new MutationObserver(function() { eliminateAds(); });" +
                "    window.carHomeAdBlockObs.observe(document.documentElement || document.body, { childList: true, subtree: true });" +
                "  }" +
                "})();";

        view.evaluateJavascript(adBlockJs, null);
    }

    // 화면 회전 감지 시 영상이 끊기지 않고 메뉴바 위치만 자연스럽게 변경되도록 처리
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateMenuLayout(newConfig.orientation);
    }

    // 가로/세로 모드에 따라 메뉴바와 웹뷰의 구조를 완전히 재배치하는 메서드
    private void updateMenuLayout(int orientation) {
        LinearLayout rootLayout = findViewById(R.id.browserRootLayout);
        LinearLayout menuLayout = findViewById(R.id.menuLayout);
        LinearLayout menuGroup1 = findViewById(R.id.menuGroup1);
        LinearLayout menuGroup2 = findViewById(R.id.menuGroup2);
        LinearLayout menuGroup3 = findViewById(R.id.menuGroup3);

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 가로 모드: 전체를 가로로 분할, 메뉴바는 왼쪽 1줄 기둥으로 설정
            rootLayout.setOrientation(LinearLayout.HORIZONTAL);
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (96 * getResources().getDisplayMetrics().density), // 메뉴바 너비를 96dp로 설정
                    LinearLayout.LayoutParams.MATCH_PARENT));

            // 세 그룹도 모두 세로 기둥 방향으로 전환하여 1줄로 통합 배치
            menuGroup1.setOrientation(LinearLayout.VERTICAL);
            menuGroup1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            menuGroup2.setOrientation(LinearLayout.VERTICAL);
            menuGroup2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            menuGroup3.setOrientation(LinearLayout.VERTICAL);
            menuGroup3.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            webView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

            setButtonParams(menuGroup1, true);
            setButtonParams(menuGroup2, true);
            setButtonParams(menuGroup3, true);
        } else {
            // 세로 모드: 전체를 세로로 분할, 메뉴바는 맨 위 3줄로 설정
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (180 * getResources().getDisplayMetrics().density))); // 3줄 180dp

            // 세 그룹을 가로 줄로 전환하여 위아래 3층으로 배치
            menuGroup1.setOrientation(LinearLayout.HORIZONTAL);
            menuGroup1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            menuGroup2.setOrientation(LinearLayout.HORIZONTAL);
            menuGroup2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            menuGroup3.setOrientation(LinearLayout.HORIZONTAL);
            menuGroup3.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            webView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            setButtonParams(menuGroup1, false);
            setButtonParams(menuGroup2, false);
            setButtonParams(menuGroup3, false);
        }
    }

    // 각 그룹 안의 버튼들의 레이아웃 여백과 크기를 균등하게 맞춰주는 헬퍼 메서드
    private void setButtonParams(LinearLayout group, boolean isLandscape) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            LinearLayout.LayoutParams params = isLandscape
                    ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
                    : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            params.setMargins(6, 6, 6, 6);
            child.setLayoutParams(params);
        }
    }

    // 비디오 배속을 변경하는 공통 메서드
    private void setVideoSpeed(float speed) {
        if (webView != null) {
            webView.evaluateJavascript("var videos = document.getElementsByTagName('video'); if(videos.length > 0) videos[0].playbackRate = " + speed + ";", null);
            Toast.makeText(this, speed + "배속 적용", Toast.LENGTH_SHORT).show();
        }
    }

    // 자바스크립트를 이용해 유튜브 재생 시간을 앞/뒤로 넘기는 기능
    private void skipVideo(int seconds) {
        if (webView != null) {
            webView.evaluateJavascript("var v = document.getElementsByTagName('video')[0]; if(v) v.currentTime += " + seconds + ";", null);
            String msg = Math.abs(seconds) >= 60 ? (Math.abs(seconds) / 60) + "분" : Math.abs(seconds) + "초";
            Toast.makeText(this, (seconds > 0 ? "+" + msg + " 이동" : "-" + msg + " 이동"), Toast.LENGTH_SHORT).show();
        }
    }

    // 유튜브 해상도(360p, 480p, 720p, 1080p) 변경 메서드
    private void setVideoQuality(String qualityLevel, String label) {
        if (webView != null) {
            String js = "(function() {" +
                    "  var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                    "  if (p) {" +
                    "    if (typeof p.setPlaybackQualityRange === 'function') {" +
                    "      p.setPlaybackQualityRange('" + qualityLevel + "', '" + qualityLevel + "');" +
                    "    }" +
                    "    if (typeof p.setPlaybackQuality === 'function') {" +
                    "      p.setPlaybackQuality('" + qualityLevel + "');" +
                    "    }" +
                    "  }" +
                    "})();";
            webView.evaluateJavascript(js, null);
            Toast.makeText(this, label + " 해상도 설정 요청", Toast.LENGTH_SHORT).show();
        }
    }

    // 이미 브라우저 창이 열려있을 때 새로운 채널을 선택하면, 새 탭을 만들지 않고 기존 창에서 즉시 이동하도록 처리
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && webView != null) {
            webView.loadUrl(url); // 기존 창(첫 번째 탭)에서 새로운 URL을 로드
        }
    }

    @Override
    protected void onDestroy() {
        adBlockHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.clearHistory(); // 뒤로가기 기록 삭제
            webView.clearCache(true); // 임시 파일 삭제
            webView.destroy(); // 브라우저 엔진 완전 종료
            webView = null; // 메모리에서 즉시 해제
        }
        super.onDestroy();
    }
}