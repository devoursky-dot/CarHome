package com.example.carhome;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TmapNotificationListener extends NotificationListenerService {

    private static final String TAG = "TmapNotiListener";
    public static final String TMAP_PACKAGE = "com.skt.tmap.ku";
    public static TmapNotificationListener instance;

    public interface OnTmapUpdateListener {
        void onTmapSafeDrivingUpdate(boolean isActive, int speedLimit, int distanceMeters, String cameraType, String rawText);
    }

    private static OnTmapUpdateListener updateListener;

    public static void setUpdateListener(OnTmapUpdateListener listener) {
        updateListener = listener;
    }

    private final Pattern limitPattern = Pattern.compile("(?:제한|단속|카메라|시속|스쿨존|구간|속도|\\D)?\\s*(30|40|50|60|70|80|90|100|110|120)\\s*(?:km|km/h|k|킬로)?");
    private final Pattern distPattern = Pattern.compile("(\\d{1,4})\\s*(?:m|미터)");

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.d(TAG, "TmapNotificationListener connected successfully");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !TMAP_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return;
        }

        Bundle extras = notification.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append(" ");
        if (text != null) sb.append(text).append(" ");
        if (subText != null) sb.append(subText).append(" ");
        if (bigText != null) sb.append(bigText).append(" ");

        String fullText = sb.toString().trim();
        Log.d(TAG, "Tmap Notification Text: " + fullText);

        // 안심주행 또는 안전운행 알림 감지
        boolean isSafeDriving = fullText.contains("안심") || fullText.contains("안전") ||
                fullText.contains("단속") || fullText.contains("카메라") ||
                fullText.contains("구간") || fullText.contains("km/h");

        int speedLimit = 0;
        int distanceMeters = 0;
        String cameraType = "안심주행 중";

        if (isSafeDriving) {
            // 1. 제한속도 파싱 (30, 50, 60, 70, 80, 100, 110 등)
            Matcher lm = limitPattern.matcher(fullText);
            if (lm.find()) {
                try {
                    speedLimit = Integer.parseInt(lm.group(1));
                } catch (Exception ignored) {}
            }

            // 2. 남은 거리 파싱 (예: 600m, 300m, 150m)
            Matcher dm = distPattern.matcher(fullText);
            if (dm.find()) {
                try {
                    distanceMeters = Integer.parseInt(dm.group(1));
                } catch (Exception ignored) {}
            }

            // 3. 카메라 종류 파싱
            if (fullText.contains("스쿨존") || fullText.contains("어린이")) {
                cameraType = "🚸 스쿨존 단속";
            } else if (fullText.contains("구간")) {
                cameraType = "⏱️ 구간단속";
            } else if (fullText.contains("신호")) {
                cameraType = "🚦 신호·과속단속";
            } else if (fullText.contains("이동식")) {
                cameraType = "📷 이동식 단속";
            } else if (fullText.contains("과속") || speedLimit > 0) {
                cameraType = "📷 과속단속";
            }
        }

        // 리스너 콜백 전달
        if (updateListener != null) {
            updateListener.onTmapSafeDrivingUpdate(isSafeDriving, speedLimit, distanceMeters, cameraType, fullText);
        }

        // 브로드캐스트 전송
        Intent intent = new Intent("com.example.carhome.TMAP_HUD_UPDATE");
        intent.putExtra("isSafeDriving", isSafeDriving);
        intent.putExtra("speedLimit", speedLimit);
        intent.putExtra("distanceMeters", distanceMeters);
        intent.putExtra("cameraType", cameraType);
        intent.putExtra("rawText", fullText);
        sendBroadcast(intent);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && TMAP_PACKAGE.equals(sbn.getPackageName())) {
            Log.d(TAG, "Tmap Notification removed");
            if (updateListener != null) {
                updateListener.onTmapSafeDrivingUpdate(false, 0, 0, "", "");
            }
            Intent intent = new Intent("com.example.carhome.TMAP_HUD_UPDATE");
            intent.putExtra("isSafeDriving", false);
            sendBroadcast(intent);
        }
    }
}
