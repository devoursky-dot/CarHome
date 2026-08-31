package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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
import java.nio.charset.StandardCharsets;

public class BrowserActivity extends AppCompatActivity {

    private static final String TAG = "BrowserActivity";

    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;
    private TextView btnResolutionStatus;
    private String currentQualityLabel = "144P";
    private String lastLoadedUrl = "https://m.youtube.com";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastAdToastTime = 0;

    // [수정 6] WebView 렌더러 크래시 복구 무한 루프 방지 카운터
    private int renderCrashCount = 0;
    private static final int MAX_RENDER_CRASH_RECOVERY = 3;

    // 안드로이드 - 웹뷰 간 실시간 광고 차단 통신 브릿지
    public class AdBlockBridge {
        @JavascriptInterface
        public void onAdSkipped(String reason) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                long now = System.currentTimeMillis();
                if (now - lastAdToastTime > 5000) {
                    lastAdToastTime = now;
                    Toast.makeText(BrowserActivity.this, "⚡ [광고 차단] 유튜브 광고 건너뛰기 완료!", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // 안드로이드 - 웹뷰 간 실시간 해상도 감지 통신 브릿지
    public class QualityBridge {
        @JavascriptInterface
        public void onQualityDetected(String quality) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String label = "144P";
                if (quality != null) {
                    String qLower = quality.toLowerCase();
                    if (qLower.contains("1080") || qLower.contains("hd1080")) label = "1080P";
                    else if (qLower.contains("720") || qLower.contains("hd720")) label = "720P";
                    else if (qLower.contains("480") || qLower.contains("large")) label = "480P";
                    else if (qLower.contains("360") || qLower.contains("medium")) label = "360P";
                    else if (qLower.contains("240") || qLower.contains("small")) label = "240P";
                    else if (qLower.contains("144") || qLower.contains("tiny")) label = "144P";
                }
                updateResolutionBadge(label);
            });
        }
    }

    private void updateResolutionBadge(String label) {
        currentQualityLabel = label;
        if (btnResolutionStatus != null) {
            if (isFinishing() || isDestroyed()) return;
            btnResolutionStatus.setText("📺 " + label);
            if ("144P".equals(label)) {
                btnResolutionStatus.setTextColor(Color.parseColor("#3DDC84")); // 녹색 (기본 최저화질 초절약 모드)
            } else {
                btnResolutionStatus.setTextColor(Color.parseColor("#64B5F6")); // 하늘색 (상위 화질 모드)
            }
        }
    }

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
        btnResolutionStatus = findViewById(R.id.btnResolutionStatus);

        // 1. 현재 해상도 상태 표시 배지 버튼 (기본 144P)
        if (btnResolutionStatus != null) {
            updateResolutionBadge("144P");
            btnResolutionStatus.setOnClickListener(v -> {
                Toast.makeText(this, "📺 현재 동영상 해상도: " + currentQualityLabel + " (기본 144P 초절약 고정)", Toast.LENGTH_SHORT).show();
            });
        }

        // 2. 툴바 배속 버튼 연결 (1X, 1.5X, 2X)
        TextView btnSpeed1x = findViewById(R.id.btnSpeed1x);
        TextView btnSpeed1_5x = findViewById(R.id.btnSpeed1_5x);
        TextView btnSpeed2x = findViewById(R.id.btnSpeed2x);

        if (btnSpeed1x != null) btnSpeed1x.setOnClickListener(v -> setVideoSpeed(1.0f));
        if (btnSpeed1_5x != null) btnSpeed1_5x.setOnClickListener(v -> setVideoSpeed(1.5f));
        if (btnSpeed2x != null) btnSpeed2x.setOnClickListener(v -> setVideoSpeed(2.0f));

        // 3. 툴바 시간 건너뛰기 버튼 연결 (<< 1분, < 10초, 10초 >, 1분 >>)
        TextView btnRewind = findViewById(R.id.btnRewind);
        TextView btnForward = findViewById(R.id.btnForward);
        TextView btnRewind10 = findViewById(R.id.btnRewind10);
        TextView btnForward10 = findViewById(R.id.btnForward10);

        if (btnRewind != null) btnRewind.setOnClickListener(v -> skipVideo(-60));
        if (btnForward != null) btnForward.setOnClickListener(v -> skipVideo(60));
        if (btnRewind10 != null) btnRewind10.setOnClickListener(v -> skipVideo(-10));
        if (btnForward10 != null) btnForward10.setOnClickListener(v -> skipVideo(10));

        // 4. 툴바 해상도 조절 버튼 연결 (144P, 240P, 360P, 480P, 720P, 1080P)
        TextView btnRes144 = findViewById(R.id.btnRes144);
        TextView btnRes240 = findViewById(R.id.btnRes240);
        TextView btnRes360 = findViewById(R.id.btnRes360);
        TextView btnRes480 = findViewById(R.id.btnRes480);
        TextView btnRes720 = findViewById(R.id.btnRes720);
        TextView btnRes1080 = findViewById(R.id.btnRes1080);

        if (btnRes144 != null) btnRes144.setOnClickListener(v -> setVideoQuality("tiny", "144P"));
        if (btnRes240 != null) btnRes240.setOnClickListener(v -> setVideoQuality("small", "240P"));
        if (btnRes360 != null) btnRes360.setOnClickListener(v -> setVideoQuality("medium", "360P"));
        if (btnRes480 != null) btnRes480.setOnClickListener(v -> setVideoQuality("large", "480P"));
        if (btnRes720 != null) btnRes720.setOnClickListener(v -> setVideoQuality("hd720", "720P"));
        if (btnRes1080 != null) btnRes1080.setOnClickListener(v -> setVideoQuality("hd1080", "1080P"));

        setupWebViewInstance(webView);

        String url = getIntent().getStringExtra("url");
        if (url != null && !url.isEmpty()) {
            lastLoadedUrl = url;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        // 자바스크립트 브릿지 등록 (광고 차단 및 해상도 감지용)
        wv.addJavascriptInterface(new AdBlockBridge(), "AndroidAdBlock");
        wv.addJavascriptInterface(new QualityBridge(), "AndroidQuality");

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

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
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

                // [문제 E 수정] 전체화면 해제 시 화면 회전을 자유 상태로 복원 (가로 고정 방지)
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            // [수정 5] 외부 순수 광고 서빙 도메인 필터 목록 (유튜브 필수 리소스 보호를 위해 정밀화)
            private final String[] AD_EXTERNAL_DOMAINS = {
                    "pagead2.googlesyndication.com",
                    "pubads.g.doubleclick.net",
                    "securepubads.g.doubleclick.net",
                    "static.doubleclick.net",
                    "google-analytics.com"
            };

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String host = request.getUrl().getHost();
                    if (host != null) {
                        String hostLower = host.toLowerCase();
                        for (String adDomain : AD_EXTERNAL_DOMAINS) {
                            if (hostLower.contains(adDomain)) {
                                // 403 Forbidden 응답으로 외부 광고 서빙 안전하게 차단
                                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", null,
                                        new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) lastLoadedUrl = url;
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
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
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
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
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

    // 유튜브 광고 차단 및 안정적인 화질/배속 제어 스크립트 주입 (성능 최적화 버전)
    private void injectAdBlocker(WebView view) {
        if (view == null) return;
        // [수정 1] DOM 변경을 감지하던 무거운 MutationObserver 제거 (2배속 재생 시 렌더러 과부하 방지)
        // [수정 2] 가벼운 setInterval 기반으로 폴링 주기 최적화
        String adBlockJs =
                "(function() {" +
                "  if (window.__carHomeAdBlockInitialized) return;" +
                "  window.__carHomeAdBlockInitialized = true;" +
                "  window.__lastQualityReported = '';" +
                "  window.__safeSkipAdLastRun = 0;" +
                "  try {" +
                "    var qObj = { data: 'tiny', expiration: Date.now() + 315360000000, creation: Date.now() };" +
                "    localStorage.setItem('yt-player-quality', JSON.stringify(qObj));" +
                "    sessionStorage.setItem('yt-player-quality', JSON.stringify(qObj));" +
                "  } catch(e) {}" +
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
                "  var isHandlingAd = false;" +
                "  function safeSkipAd() {" +
                "    var now = Date.now();" +
                "    if (now - window.__safeSkipAdLastRun < 1000) return;" +
                "    window.__safeSkipAdLastRun = now;" +
                "    try {" +
                "      var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      var isAd = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, .ytp-ad-module, .video-ads, [class*=\"ad-showing\"], .ytm-player-ad');" +
                "      if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {" +
                "        isAd = true;" +
                "      }" +
                "      var video = document.querySelector('video');" +
                "      if (isAd && video) {" +
                "        video.muted = true;" +
                "        window.__carHomeWasMutedByAdBlock = true;" +
                "        if (player && typeof player.skipAd === 'function') {" +
                "          try { player.skipAd(); } catch(e) {}" +
                "        }" +
                "        if (!isHandlingAd) {" +
                "          isHandlingAd = true;" +
                "          if (video.duration && !isNaN(video.duration) && isFinite(video.duration) && video.duration > 0) {" +
                "            video.currentTime = Math.max(0, video.duration - 0.2);" +
                "          }" +
                "          if (window.AndroidAdBlock) { window.AndroidAdBlock.onAdSkipped('ad_skipped'); }" +
                "          setTimeout(function() { isHandlingAd = false; }, 1500);" +
                "        }" +
                "      } else if (!isAd && video && window.__carHomeWasMutedByAdBlock) {" +
                "        video.muted = false;" +
                "        if (player && typeof player.unMute === 'function') { try { player.unMute(); } catch(e) {} }" +
                "        window.__carHomeWasMutedByAdBlock = false;" +
                "      }" +
                "      var skipButtons = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton, .ytp-ad-skip-button-slot button, button.ytp-ad-skip-button, button.ytp-ad-skip-button-modern, .ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container, .ytp-ad-skip-slot button, button[class*=\"skip-button\"], button[aria-label*=\"광고 건너뛰기\"], button[aria-label*=\"Skip ad\"], .ytp-skip-ad-button');" +
                "      for (var i = 0; i < skipButtons.length; i++) {" +
                "        try {" +
                "          skipButtons[i].click();" +
                "          if (window.AndroidAdBlock) { window.AndroidAdBlock.onAdSkipped('skip_button_clicked'); }" +
                "        } catch(e) {}" +
                "      }" +
                "      var dismissBtns = document.querySelectorAll('yt-button-renderer#dismiss-button, button[aria-label=\"닫기\"], button[aria-label=\"Close\"], #dismiss-button');" +
                "      for (var j = 0; j < dismissBtns.length; j++) {" +
                "        try { if (dismissBtns[j].offsetParent !== null) dismissBtns[j].click(); } catch(e) {}" +
                "      }" +
                "      if (player) {" +
                "        if (!window.__carHomeUserSelectedQuality) {" +
                "          if (typeof player.setPlaybackQualityRange === 'function') {" +
                "            player.setPlaybackQualityRange('tiny', 'tiny');" +
                "          }" +
                "          if (typeof player.setPlaybackQuality === 'function') {" +
                "            player.setPlaybackQuality('tiny');" +
                "          }" +
                "        }" +
                "        if (typeof player.getPlaybackQuality === 'function') {" +
                "          var q = player.getPlaybackQuality();" +
                "          if (q && q !== window.__lastQualityReported && window.AndroidQuality) {" +
                "            window.__lastQualityReported = q;" +
                "            window.AndroidQuality.onQualityDetected(q);" +
                "          }" +
                "        }" +
                "      }" +
                "    } catch(err) {}" +
                "  }" +
                "  function attachPlayerObserver() {" +
                "    try {" +
                "      var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "      if (player && !player.__carHomeObserverAttached) {" +
                "        player.__carHomeObserverAttached = true;" +
                "        var playerObserver = new MutationObserver(function(mutations) {" +
                "          for (var i = 0; i < mutations.length; i++) {" +
                "            if (mutations[i].attributeName === 'class') {" +
                "              safeSkipAd();" +
                "              break;" +
                "            }" +
                "          }" +
                "        });" +
                "        playerObserver.observe(player, { attributes: true, attributeFilter: ['class'] });" +
                "      }" +
                "    } catch(e) {}" +
                "  }" +
                "  attachPlayerObserver();" +
                "  safeSkipAd();" +
                "  if (!window.carHomeAdBlockTimer) {" +
                "    window.carHomeAdBlockTimer = setInterval(function() {" +
                "      attachPlayerObserver();" +
                "      safeSkipAd();" +
                "    }, 3000);" +
                "  }" +
                "  if (!window.__carHomeQualityEventAttached) {" +
                "    window.__carHomeQualityEventAttached = true;" +
                "    document.addEventListener('yt-navigate-finish', function() {" +
                "      window.__carHomeUserSelectedQuality = false;" +
                "      window.__lastQualityReported = '';" +
                "      window.__safeSkipAdLastRun = 0;" +
                "      attachPlayerObserver();" +
                "      safeSkipAd();" +
                "    });" +
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

    // 화면 회전 시 4x3 툴바 레이아웃 자동 변환 메서드
    private void updateMenuLayout(int orientation) {
        LinearLayout menuLayout = findViewById(R.id.menuLayout);
        LinearLayout menuGroup1 = findViewById(R.id.menuGroup1);
        LinearLayout menuGroup2 = findViewById(R.id.menuGroup2);
        LinearLayout menuGroup3 = findViewById(R.id.menuGroup3);
        LinearLayout browserRootLayout = findViewById(R.id.browserRootLayout);

        if (menuLayout == null || menuGroup1 == null || menuGroup2 == null || menuGroup3 == null || browserRootLayout == null || webView == null) {
            return;
        }

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            browserRootLayout.setOrientation(LinearLayout.HORIZONTAL);
            menuLayout.setOrientation(LinearLayout.HORIZONTAL);
            menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (320 * getResources().getDisplayMetrics().density),
                    LinearLayout.LayoutParams.MATCH_PARENT));

            menuGroup1.setOrientation(LinearLayout.VERTICAL);
            menuGroup1.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
            menuGroup2.setOrientation(LinearLayout.VERTICAL);
            menuGroup2.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
            menuGroup3.setOrientation(LinearLayout.VERTICAL);
            menuGroup3.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

            webView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

            setButtonParams(menuGroup1, true);
            setButtonParams(menuGroup2, true);
            setButtonParams(menuGroup3, true);
        } else {
            browserRootLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (180 * getResources().getDisplayMetrics().density)));

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

    private void setVideoSpeed(float speed) {
        if (webView != null) {
            String js = "try { var videos = document.getElementsByTagName('video'); if(videos && videos.length > 0) { videos[0].playbackRate = " + speed + "; } } catch(e) {}";
            webView.evaluateJavascript(js, null);
            Toast.makeText(this, speed + "배속 적용", Toast.LENGTH_SHORT).show();
        }
    }

    private void skipVideo(int seconds) {
        if (webView != null) {
            String js = "try { var v = document.getElementsByTagName('video')[0]; if(v) { v.currentTime += " + seconds + "; } } catch(e) {}";
            webView.evaluateJavascript(js, null);
            String msg = Math.abs(seconds) >= 60 ? (Math.abs(seconds) / 60) + "분" : Math.abs(seconds) + "초";
            Toast.makeText(this, (seconds > 0 ? "+" + msg + " 이동" : "-" + msg + " 이동"), Toast.LENGTH_SHORT).show();
        }
    }

    private void setVideoQuality(String qualityLevel, String label) {
        if (webView != null) {
            String js = "(function() {" +
                    "  try {" +
                    "    window.__carHomeUserSelectedQuality = true;" +
                    "    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                    "    if (p) {" +
                    "      if (typeof p.setPlaybackQualityRange === 'function') {" +
                    "        p.setPlaybackQualityRange('" + qualityLevel + "', '" + qualityLevel + "');" +
                    "      }" +
                    "      if (typeof p.setPlaybackQuality === 'function') {" +
                    "        p.setPlaybackQuality('" + qualityLevel + "');" +
                    "      }" +
                    "      window.__lastQualityReported = '" + qualityLevel + "';" +
                    "      if (window.AndroidQuality) {" +
                    "        window.AndroidQuality.onQualityDetected('" + qualityLevel + "');" +
                    "      }" +
                    "    }" +
                    "  } catch(e) {}" +
                    "})();";
            webView.evaluateJavascript(js, null);
            updateResolutionBadge(label);
            Toast.makeText(this, label + " 해상도로 변경 요청 📺", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null) {
            lastLoadedUrl = url;
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    parent.removeView(webView);
                }
                webView.stopLoading();
                webView.clearHistory();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}