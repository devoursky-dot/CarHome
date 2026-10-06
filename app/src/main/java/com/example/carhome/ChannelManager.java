package com.example.carhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 유튜브 바로가기 채널 목록 관리 공통 유틸리티
 * MainActivity와 FloatingService에서 채널 데이터를 일관되게 공유 및 저장합니다.
 */
public class ChannelManager {

    private static final String PREF_NAME = "CarHomePrefs";
    private static final String KEY_CHANNELS = "brave_channels";

    public static class ChannelItem {
        public String name;
        public String url;

        public ChannelItem(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public static List<ChannelItem> loadChannels(Context context) {
        List<ChannelItem> list = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedJson = prefs.getString(KEY_CHANNELS, null);

        if (savedJson != null) {
            try {
                JSONArray jsonArray = new JSONArray(savedJson);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    list.add(new ChannelItem(obj.getString("name"), obj.getString("url")));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 저장된 채널이 없으면 기본 추천 채널 자동 생성
        if (list.isEmpty()) {
            list.add(new ChannelItem("실시간\n뉴스", "https://m.youtube.com/results?search_query=실시간+뉴스+라이브"));
            list.add(new ChannelItem("실시간\n음악", "https://m.youtube.com/results?search_query=실시간+음악+라이브"));
            list.add(new ChannelItem("유튜브\n홈", "https://m.youtube.com"));
            saveChannels(context, list);
        }

        return list;
    }

    public static void saveChannels(Context context, List<ChannelItem> channels) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (ChannelItem item : channels) {
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("url", item.url);
                jsonArray.put(obj);
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CHANNELS, jsonArray.toString())
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String formatChannelName(String rawName) {
        if (rawName == null) return "";
        if (rawName.contains("\n")) return rawName;
        if (rawName.length() >= 4) {
            int mid = (rawName.length() + 1) / 2;
            return rawName.substring(0, mid) + "\n" + rawName.substring(mid);
        }
        return rawName;
    }
}
