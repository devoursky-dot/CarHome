package com.example.carhome;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class VideoHistoryDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "carhome_history.db";
    private static final int DATABASE_VERSION = 2;

    public static final String TABLE_HISTORY = "video_history";
    public static final String COL_VIDEO_ID = "video_id";
    public static final String COL_TITLE = "title";
    public static final String COL_CHANNEL_TITLE = "channel_title";
    public static final String COL_THUMBNAIL_URL = "thumbnail_url";
    public static final String COL_DURATION_MS = "duration_ms";
    public static final String COL_LAST_POSITION_MS = "last_position_ms";
    public static final String COL_IS_COMPLETED = "is_completed";
    public static final String COL_UPDATED_AT = "updated_at";

    private static VideoHistoryDbHelper instance;

    public static synchronized VideoHistoryDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new VideoHistoryDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private VideoHistoryDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableSql = "CREATE TABLE " + TABLE_HISTORY + " ("
                + COL_VIDEO_ID + " TEXT PRIMARY KEY, "
                + COL_TITLE + " TEXT, "
                + COL_CHANNEL_TITLE + " TEXT, "
                + COL_THUMBNAIL_URL + " TEXT, "
                + COL_DURATION_MS + " INTEGER DEFAULT 0, "
                + COL_LAST_POSITION_MS + " INTEGER DEFAULT 0, "
                + COL_IS_COMPLETED + " INTEGER DEFAULT 0, "
                + COL_UPDATED_AT + " INTEGER DEFAULT 0"
                + ");";
        db.execSQL(createTableSql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_HISTORY + " ADD COLUMN " + COL_IS_COMPLETED + " INTEGER DEFAULT 0;");
                // 기존 데이터 중 90% 이상 본 영상들을 즉시 완료 상태(1)로 일괄 마이그레이션
                db.execSQL("UPDATE " + TABLE_HISTORY + " SET " + COL_IS_COMPLETED + " = 1 WHERE " + COL_DURATION_MS + " > 0 AND (" + COL_LAST_POSITION_MS + " * 100 / " + COL_DURATION_MS + ") >= 90;");
            } catch (Exception ignored) {}
        }
    }

    public static class HistoryEntry {
        public String videoId;
        public String title;
        public String channelTitle;
        public String thumbnailUrl;
        public long durationMs;
        public long lastPositionMs;
        public boolean isCompleted;
        public long updatedAt;

        public int getProgressPercent() {
            if (isCompleted()) return 100;
            if (durationMs <= 0) return 0;
            int percent = (int) ((lastPositionMs * 100L) / durationMs);
            return Math.min(100, Math.max(0, percent));
        }

        public boolean isCompleted() {
            return isCompleted || (durationMs > 0 && ((lastPositionMs * 100L) / durationMs) >= 90);
        }

        public String getFormattedPosition() {
            long sec = lastPositionMs / 1000;
            long m = sec / 60;
            long s = sec % 60;
            return String.format(java.util.Locale.getDefault(), "%02d:%02d", m, s);
        }
    }

    public synchronized void saveProgress(String videoId, String title, String channelTitle, String thumbnailUrl, long durationMs, long lastPositionMs) {
        if (videoId == null || videoId.isEmpty()) return;
        try {
            HistoryEntry existing = getHistory(videoId);
            boolean alreadyCompleted = existing != null && existing.isCompleted();
            boolean newlyCompleted = durationMs > 0 && ((lastPositionMs * 100L) / durationMs) >= 90;

            // [핵심 방어 1]: 한 번 완료된 영상은 나중에 다시 틀어서 0초나 초반 위치가 들어와도 완료 상태를 영구 보존!
            boolean finalCompleted = alreadyCompleted || newlyCompleted;

            // [핵심 방어 2]: 영상 실행 초기(0~5초)에 이어보기 점프 전 상태가 이전 진도(예: 10분)를 덮어쓰지 않도록 보호!
            long finalPositionMs = lastPositionMs;
            if (existing != null && !alreadyCompleted) {
                if (lastPositionMs < 5000 && existing.lastPositionMs > 5000) {
                    finalPositionMs = existing.lastPositionMs;
                }
            }

            long finalDurationMs = durationMs > 0 ? durationMs : (existing != null ? existing.durationMs : 0);

            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(COL_VIDEO_ID, videoId);
            if (title != null && !title.isEmpty()) cv.put(COL_TITLE, title);
            if (channelTitle != null) cv.put(COL_CHANNEL_TITLE, channelTitle);
            if (thumbnailUrl != null) cv.put(COL_THUMBNAIL_URL, thumbnailUrl);
            cv.put(COL_DURATION_MS, finalDurationMs);
            cv.put(COL_LAST_POSITION_MS, finalPositionMs);
            cv.put(COL_IS_COMPLETED, finalCompleted ? 1 : 0);
            cv.put(COL_UPDATED_AT, System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_HISTORY, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized HistoryEntry getHistory(String videoId) {
        if (videoId == null || videoId.isEmpty()) return null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_HISTORY, null, COL_VIDEO_ID + "=?", new String[]{videoId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                HistoryEntry entry = parseCursor(cursor);
                cursor.close();
                return entry;
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized List<HistoryEntry> getAllHistories(int limit) {
        List<HistoryEntry> list = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            String limitStr = limit > 0 ? String.valueOf(limit) : null;
            Cursor cursor = db.query(TABLE_HISTORY, null, null, null, null, null, COL_UPDATED_AT + " DESC", limitStr);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    list.add(parseCursor(cursor));
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private HistoryEntry parseCursor(Cursor cursor) {
        HistoryEntry entry = new HistoryEntry();
        entry.videoId = cursor.getString(cursor.getColumnIndexOrThrow(COL_VIDEO_ID));
        entry.title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE));
        entry.channelTitle = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHANNEL_TITLE));
        entry.thumbnailUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_THUMBNAIL_URL));
        entry.durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATION_MS));
        entry.lastPositionMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_POSITION_MS));
        int completedColIdx = cursor.getColumnIndex(COL_IS_COMPLETED);
        if (completedColIdx != -1) {
            entry.isCompleted = cursor.getInt(completedColIdx) == 1;
        } else {
            entry.isCompleted = entry.durationMs > 0 && ((entry.lastPositionMs * 100) / entry.durationMs) >= 90;
        }
        entry.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT));
        return entry;
    }
}
