package alin.android.alinos.db;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ChatRecordBean;
import alin.android.alinos.bean.ChatSessionBean;
import alin.android.alinos.bean.ConfigBean;

public class ChatDBHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "chat_db.db";
    private static final int DB_VERSION = 2;

    private static final String TABLE_SESSION = "chat_session";
    public static final String SESSION_ID = "id";
    public static final String SESSION_NAME = "session_name";
    public static final String SESSION_CONFIG_ID = "config_id";
    public static final String SESSION_CREATE_TIME = "create_time";
    public static final String SESSION_TYPE = "session_type";

    private static final String TABLE_RECORD = "chat_record";
    public static final String RECORD_ID = "id";
    public static final String RECORD_SESSION_ID = "session_id";
    public static final String RECORD_MSG_TYPE = "msg_type";
    public static final String RECORD_SENDER = "sender";
    public static final String RECORD_CONTENT = "content";
    public static final String RECORD_SEND_TIME = "send_time";

    private final Context context;

    public ChatDBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createSessionSql = "CREATE TABLE " + TABLE_SESSION + " (" +
                SESSION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                SESSION_NAME + " TEXT NOT NULL, " +
                SESSION_CONFIG_ID + " INTEGER NOT NULL DEFAULT 1, " +
                SESSION_TYPE + " INTEGER DEFAULT 0, " +
                SESSION_CREATE_TIME + " LONG NOT NULL)";
        db.execSQL(createSessionSql);

        String createRecordSql = "CREATE TABLE " + TABLE_RECORD + " (" +
                RECORD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                RECORD_SESSION_ID + " INTEGER NOT NULL, " +
                RECORD_MSG_TYPE + " INTEGER NOT NULL DEFAULT 0, " +
                RECORD_SENDER + " TEXT NOT NULL, " +
                RECORD_CONTENT + " TEXT NOT NULL, " +
                RECORD_SEND_TIME + " LONG NOT NULL)";
        db.execSQL(createRecordSql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE " + TABLE_SESSION + " ADD COLUMN " + SESSION_TYPE + " INTEGER DEFAULT 0");
        } else {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RECORD);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSION);
            onCreate(db);
        }
    }

    // ==============================================
    // 会话操作（全部安全封装）
    // ==============================================

    public List<ChatSessionBean> getAllSessions() {
        List<ChatSessionBean> list = new ArrayList<>();
        try (SQLiteDatabase db = getReadableDatabase(); Cursor cursor = db.query(TABLE_SESSION, null, null, null, null, null,
                SESSION_CREATE_TIME + " DESC")) {
            if (cursor.moveToFirst()) {
                do {
                    ChatSessionBean session = new ChatSessionBean();
                    session.setId(cursor.getInt(cursor.getColumnIndexOrThrow(SESSION_ID)));
                    session.setSessionName(cursor.getString(cursor.getColumnIndexOrThrow(SESSION_NAME)));
                    session.setConfigId(cursor.getInt(cursor.getColumnIndexOrThrow(SESSION_CONFIG_ID)));
                    session.setCreateTime(cursor.getLong(cursor.getColumnIndexOrThrow(SESSION_CREATE_TIME)));
                    try {
                        session.setSessionType(cursor.getInt(cursor.getColumnIndexOrThrow(SESSION_TYPE)));
                    } catch (Exception e) {
                        session.setSessionType(0);
                    }
                    list.add(session);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("ChatDBHelper", "getAllSessions 异常", e);
        }
        return list;
    }

    public long addSession(ChatSessionBean session) {
        if (session == null) return -1;
        try (SQLiteDatabase db = getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put(SESSION_NAME, session.getSessionName());
            values.put(SESSION_CONFIG_ID, session.getConfigId());
            values.put(SESSION_TYPE, session.getSessionType());
            values.put(SESSION_CREATE_TIME, session.getCreateTime());
            return db.insert(TABLE_SESSION, null, values);
        } catch (Exception e) {
            Log.e("ChatDBHelper", "addSession 异常", e);
            return -1;
        }
    }

    public void updateSession(ChatSessionBean session) {
        if (session == null) return;
        try (SQLiteDatabase db = getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put(SESSION_NAME, session.getSessionName());
            values.put(SESSION_CONFIG_ID, session.getConfigId());
            values.put(SESSION_CREATE_TIME, session.getCreateTime());
            db.update(TABLE_SESSION, values, SESSION_ID + "=?",
                    new String[]{String.valueOf(session.getId())});
        } catch (Exception e) {
            Log.e("ChatDBHelper", "updateSession 异常", e);
        }
    }

    public void deleteSession(int sessionId) {
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            db.delete(TABLE_RECORD, RECORD_SESSION_ID + "=?", new String[]{String.valueOf(sessionId)});
            db.delete(TABLE_SESSION, SESSION_ID + "=?", new String[]{String.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e("ChatDBHelper", "deleteSession 异常", e);
        } finally {
            if (db != null) {
                db.endTransaction();
                db.close();
            }
        }
    }

    // ==============================================
    // 聊天记录操作（全部安全封装）
    // ==============================================

    public List<ChatRecordBean> getRecordsBySessionId(int sessionId) {
        List<ChatRecordBean> list = new ArrayList<>();
        try (SQLiteDatabase db = getReadableDatabase(); Cursor cursor = db.query(TABLE_RECORD, null, RECORD_SESSION_ID + "=?",
                new String[]{String.valueOf(sessionId)}, null, null, RECORD_SEND_TIME + " ASC")) {
            if (cursor.moveToFirst()) {
                do {
                    ChatRecordBean record = new ChatRecordBean();
                    record.setId(cursor.getInt(cursor.getColumnIndexOrThrow(RECORD_ID)));
                    record.setSessionId(cursor.getInt(cursor.getColumnIndexOrThrow(RECORD_SESSION_ID)));
                    record.setMsgType(cursor.getInt(cursor.getColumnIndexOrThrow(RECORD_MSG_TYPE)));
                    record.setSender(cursor.getString(cursor.getColumnIndexOrThrow(RECORD_SENDER)));
                    record.setContent(cursor.getString(cursor.getColumnIndexOrThrow(RECORD_CONTENT)));
                    record.setSendTime(cursor.getLong(cursor.getColumnIndexOrThrow(RECORD_SEND_TIME)));
                    list.add(record);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e("ChatDBHelper", "getRecordsBySessionId 异常", e);
        }
        return list;
    }

    public long addRecord(ChatRecordBean record) {
        if (record == null || record.getSessionId() <= 0) return -1;
        try (SQLiteDatabase db = getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put(RECORD_SESSION_ID, record.getSessionId());
            values.put(RECORD_MSG_TYPE, record.getMsgType());
            values.put(RECORD_SENDER, record.getSender());
            values.put(RECORD_CONTENT, record.getContent());
            values.put(RECORD_SEND_TIME, record.getSendTime());
            return db.insert(TABLE_RECORD, null, values);
        } catch (Exception e) {
            Log.e("ChatDBHelper", "addRecord 异常", e);
            return -1;
        }
    }

    public void updateRecordContent(long recordId, String newContent) {
        if (recordId <= 0 || TextUtils.isEmpty(newContent)) return;
        try (SQLiteDatabase db = getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put(RECORD_CONTENT, newContent);
            db.update(TABLE_RECORD, values, RECORD_ID + "=?", new String[]{String.valueOf(recordId)});
        } catch (Exception e) {
            Log.e("ChatDBHelper", "updateRecordContent 异常", e);
        }
    }

    public String getRecordContentById(long recordId) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        String content = "";
        try {
            db = getReadableDatabase();
            cursor = db.query(TABLE_RECORD, new String[]{RECORD_CONTENT},
                    RECORD_ID + "=?", new String[]{String.valueOf(recordId)}, null, null, null);
            if (cursor.moveToFirst()) {
                content = cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e("ChatDBHelper", "getRecordContentById 异常", e);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
        return content;
    }

    // ==============================================
    // 悬浮窗会话（保留原有功能）
    // ==============================================

    @SuppressLint("Range")
    public ChatSessionBean getOrCreateFloatWindowSession() {
        try (SQLiteDatabase db = getReadableDatabase(); Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_SESSION + " WHERE " + SESSION_NAME + " = ?",
                new String[]{"悬浮窗会话"})) {
            if (cursor.moveToFirst()) {
                ChatSessionBean session = new ChatSessionBean();
                session.setId(cursor.getInt(cursor.getColumnIndex(SESSION_ID)));
                session.setSessionName(cursor.getString(cursor.getColumnIndex(SESSION_NAME)));
                session.setConfigId(cursor.getInt(cursor.getColumnIndex(SESSION_CONFIG_ID)));
                session.setCreateTime(cursor.getLong(cursor.getColumnIndex(SESSION_CREATE_TIME)));
                return session;
            }
        } catch (Exception e) {
            Log.e("ChatDBHelper", "getOrCreateFloatWindowSession 异常", e);
        }

        ConfigDBHelper configDbHelper = new ConfigDBHelper(this.context);
        ConfigBean config = configDbHelper.getFirstConfig();
        int configId = (config != null) ? config.getId() : 1;

        ChatSessionBean newSession = new ChatSessionBean("悬浮窗会话", configId, System.currentTimeMillis());
        long id = addSession(newSession);
        newSession.setId((int) id);
        return newSession;
    }

    public void deleteFloatWindowSession() {
        try (SQLiteDatabase db = getWritableDatabase()) {
            db.delete(TABLE_SESSION, SESSION_TYPE + "=?", new String[]{"1"});
        } catch (Exception e) {
            Log.e("ChatDBHelper", "deleteFloatWindowSession 异常", e);
        }
    }
}