package com.example.carhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * 앱 실행 및 티맵 안심주행 공통 도우미
 * MainActivity와 FloatingService에서 공통으로 사용합니다.
 */
public class AppLauncher {

    public static final String PACKAGE_TMAP = "com.skt.tmap.ku";
    public static final String PACKAGE_VIDEO = "com.samsung.android.videolist";
    public static final String PACKAGE_CHROME = "com.android.chrome";

    /**
     * 티맵 안심주행 직행 (URI: tmap://safe)
     */
    public static void launchTmapSafeDriving(Context context) {
        try {
            Intent directIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("tmap://safe"));
            directIntent.setPackage(PACKAGE_TMAP);
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            if (directIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(directIntent);
                Toast.makeText(context, "🚗 티맵 안심주행으로 바로 실행합니다", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {}

        // 폴백: 일반 런처 인텐트로 티맵 실행
        launchApp(context, PACKAGE_TMAP);
    }

    /**
     * 패키지명으로 앱 실행
     */
    public static void launchApp(Context context, String packageName) {
        if (PACKAGE_TMAP.equals(packageName)) {
            launchTmapSafeDriving(context);
            return;
        }

        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } else {
            Toast.makeText(context, "해당 앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // 스레드 릭(Thread Leak) 방지를 위해 정적 스레드풀 1개를 생성하여 공용으로 재사용합니다.
    private static final java.util.concurrent.ExecutorService iconLoaderExecutor = java.util.concurrent.Executors.newFixedThreadPool(3);
    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * ImageView에 앱 아이콘 설정 (미설치 시 기본 아이콘)
     * 비동기로 로드하여 UI 스레드 버벅임(ANR)을 방지합니다.
     */
    public static void setAppIcon(PackageManager pm, ImageView imageView, String packageName) {
        if (imageView == null || pm == null) return;
        
        // 아이콘 로딩 전 임시로 투명 픽셀이나 기본 아이콘 세팅 (깜빡임 방지)
        imageView.setImageResource(android.R.drawable.sym_def_app_icon);

        iconLoaderExecutor.execute(() -> {
            try {
                Drawable icon = pm.getApplicationIcon(packageName);
                mainHandler.post(() -> {
                    imageView.setImageDrawable(icon);
                });
            } catch (Exception e) {
                // 이미 기본 아이콘 세팅됨
            }
        });
    }
}
