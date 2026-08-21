package com.example.carhome;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class TmapNotificationListener extends NotificationListenerService {

    public static TmapNotificationListener instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
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
        // TMAP 안심주행 속도 팝업 모달 삭제에 따라 알림 파싱 생략
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 생략
    }
}
