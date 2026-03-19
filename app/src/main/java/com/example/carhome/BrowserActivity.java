package com.example.carhome;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 메인 화면처럼 상태바 및 네비게이션바 숨김 (전체화면)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars()); // 상단 상태바만 숨김
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setContentView(R.layout.activity_browser);
        webView = findViewById(R.id.webView);

        // 닫기 버튼 연결 및 클릭 이벤트 (화면 종료)
        ImageView btnCloseBrowser = findViewById(R.id.btnCloseBrowser);
        btnCloseBrowser.setOnClickListener(v -> finish());

        // 툴바 배속 버튼 연결 및 클릭 이벤트
        TextView btnSpeed1x = findViewById(R.id.btnSpeed1x);
        TextView btnSpeed1_5x = findViewById(R.id.btnSpeed1_5x);
        TextView btnSpeed2x = findViewById(R.id.btnSpeed2x);
        TextView btnRewind = findViewById(R.id.btnRewind);
        TextView btnForward = findViewById(R.id.btnForward);
        TextView btnRewind10 = findViewById(R.id.btnRewind10);
        TextView btnForward10 = findViewById(R.id.btnForward10);

        btnSpeed1x.setOnClickListener(v -> setVideoSpeed(1.0f));
        btnSpeed1_5x.setOnClickListener(v -> setVideoSpeed(1.5f));
        btnSpeed2x.setOnClickListener(v -> setVideoSpeed(2.0f));
        btnRewind.setOnClickListener(v -> skipVideo(-60));
        btnForward.setOnClickListener(v -> skipVideo(60));
        btnRewind10.setOnClickListener(v -> skipVideo(-10));
        btnForward10.setOnClickListener(v -> skipVideo(10));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // 동영상 자동 재생 허용

        webView.setWebViewClient(new WebViewClient() {
            // 1. 알려진 광고 서버 네트워크 차단
            private final String[] AD_HOSTS = {"doubleclick.net", "adservice.google.com", "googlesyndication.com", "youtube.com/api/stats/ads"};

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                for (String adHost : AD_HOSTS) {
                    if (url.contains(adHost)) {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes())); // 빈 화면 반환
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            // 2. 페이지 로딩 후 자바스크립트 주입 (유튜브 '광고 건너뛰기' 자동 클릭 매크로)
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(
                        "setInterval(function() {" +
                        "  var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton');" +
                        "  if (skipBtn) skipBtn.click();" + // 스킵 버튼이 보이면 클릭
                        "  var adOverlay = document.querySelector('.ytp-ad-overlay-container');" +
                        "  if (adOverlay) adOverlay.style.display = 'none';" + // 하단 배너 광고 숨김
                        "}, 1000);", null);
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url != null) {
            webView.loadUrl(url);
        }

        // 앱 실행 시 현재 화면 방향(가로/세로)에 맞춰 레이아웃 동적 초기화
        updateMenuLayout(getResources().getConfiguration().orientation);
    }

    // 화면 회전 감지 시 영상이 끊기지 않고 메뉴바 위치만 자연스럽게 변경되도록 처리
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateMenuLayout(newConfig.orientation);
    }

    // 가로/세로 모드에 따라 메뉴바와 웹뷰의 구조를 완전히 재배치하는 꿀팁 메서드!
    private void updateMenuLayout(int orientation) {
        LinearLayout rootLayout = findViewById(R.id.browserRootLayout);
        LinearLayout menuLayout = findViewById(R.id.menuLayout);
        LinearLayout menuGroup1 = findViewById(R.id.menuGroup1);
        LinearLayout menuGroup2 = findViewById(R.id.menuGroup2);

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 가로 모드: 전체를 가로로 분할, 메뉴바는 왼쪽 1줄 기둥으로 설정
            rootLayout.setOrientation(LinearLayout.HORIZONTAL);
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (90 * getResources().getDisplayMetrics().density), // 메뉴바 너비를 90dp로 고정
                    LinearLayout.LayoutParams.MATCH_PARENT));

            // 두 그룹도 모두 세로 기둥 방향으로 전환하여 1줄로 통합 배치
            menuGroup1.setOrientation(LinearLayout.VERTICAL);
            menuGroup1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            menuGroup2.setOrientation(LinearLayout.VERTICAL);
            menuGroup2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            webView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

            setButtonParams(menuGroup1, true);
            setButtonParams(menuGroup2, true);
        } else {
            // 세로 모드: 전체를 세로로 분할, 메뉴바는 맨 위 2줄로 설정
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (160 * getResources().getDisplayMetrics().density))); // 2줄이므로 160dp로 확대

            // 두 그룹을 가로 줄로 전환하여 위아래 2층으로 배치
            menuGroup1.setOrientation(LinearLayout.HORIZONTAL);
            menuGroup1.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            menuGroup2.setOrientation(LinearLayout.HORIZONTAL);
            menuGroup2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            webView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            setButtonParams(menuGroup1, false);
            setButtonParams(menuGroup2, false);
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
        if (webView != null) {
            webView.clearHistory(); // 뒤로가기 기록 삭제
            webView.clearCache(true); // 임시 파일 삭제
            webView.destroy(); // 브라우저 엔진 완전 종료
            webView = null; // 메모리에서 즉시 해제
        }
        super.onDestroy();
    }
}