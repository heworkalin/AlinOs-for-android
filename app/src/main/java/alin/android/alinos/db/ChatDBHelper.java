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
    private static final int DB_VERSION = 3;

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
    // Token相关字段（新增）
    public static final String RECORD_TOKEN_COUNT = "token_count";
    public static final String RECORD_PROMPT_TOKENS = "prompt_tokens";
    public static final String RECORD_COMPLETION_TOKENS = "completion_tokens";
    public static final String RECORD_TOTAL_TOKENS = "total_tokens";

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
                RECORD_SEND_TIME + " LONG NOT NULL, " +
                RECORD_TOKEN_COUNT + " INTEGER DEFAULT 0, " +
                RECORD_PROMPT_TOKENS + " INTEGER DEFAULT 0, " +
                RECORD_COMPLETION_TOKENS + " INTEGER DEFAULT 0, " +
                RECORD_TOTAL_TOKENS + " INTEGER DEFAULT 0)";
        db.execSQL(createRecordSql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // 从版本1或2升级到版本3
            if (oldVersion == 1) {
                // 从版本1升级：先添加session_type字段
                db.execSQL("ALTER TABLE " + TABLE_SESSION + " ADD COLUMN " + SESSION_TYPE + " INTEGER DEFAULT 0");
            }
            // 从版本1或2升级：添加token相关字段到chat_record表
            db.execSQL("ALTER TABLE " + TABLE_RECORD + " ADD COLUMN " + RECORD_TOKEN_COUNT + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_RECORD + " ADD COLUMN " + RECORD_PROMPT_TOKENS + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_RECORD + " ADD COLUMN " + RECORD_COMPLETION_TOKENS + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_RECORD + " ADD COLUMN " + RECORD_TOTAL_TOKENS + " INTEGER DEFAULT 0");
        } else {
            // 版本3或更高，如果需要其他升级，可以在这里添加
            // 目前如果没有特定升级路径，则删除并重建表
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

                    // 新增：读取Token相关字段（使用getColumnIndex，兼容旧版本数据库）
                    int tokenCountIndex = cursor.getColumnIndex(RECORD_TOKEN_COUNT);
                    if (tokenCountIndex != -1) {
                        record.setTokenCount(cursor.getInt(tokenCountIndex));
                    }

                    int promptTokensIndex = cursor.getColumnIndex(RECORD_PROMPT_TOKENS);
                    if (promptTokensIndex != -1) {
                        record.setPromptTokens(cursor.getInt(promptTokensIndex));
                    }

                    int completionTokensIndex = cursor.getColumnIndex(RECORD_COMPLETION_TOKENS);
                    if (completionTokensIndex != -1) {
                        record.setCompletionTokens(cursor.getInt(completionTokensIndex));
                    }

                    int totalTokensIndex = cursor.getColumnIndex(RECORD_TOTAL_TOKENS);
                    if (totalTokensIndex != -1) {
                        record.setTotalTokens(cursor.getInt(totalTokensIndex));
                    }

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
            // 新增：Token相关字段
            values.put(RECORD_TOKEN_COUNT, record.getTokenCount());
            values.put(RECORD_PROMPT_TOKENS, record.getPromptTokens());
            values.put(RECORD_COMPLETION_TOKENS, record.getCompletionTokens());
            values.put(RECORD_TOTAL_TOKENS, record.getTotalTokens());
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

    /**
     * 更新记录的Token信息
     * @param recordId 记录ID
     * @param tokenCount 消息内容本身的token数
     * @param promptTokens 整个prompt的token数（仅AI回复有意义）
     * @param completionTokens AI回复的token数
     * @param totalTokens 总token数
     */
    public void updateRecordTokens(long recordId, int tokenCount, int promptTokens, int completionTokens, int totalTokens) {
        if (recordId <= 0) return;
        try (SQLiteDatabase db = getWritableDatabase()) {
            ContentValues values = new ContentValues();
            values.put(RECORD_TOKEN_COUNT, tokenCount);
            values.put(RECORD_PROMPT_TOKENS, promptTokens);
            values.put(RECORD_COMPLETION_TOKENS, completionTokens);
            values.put(RECORD_TOTAL_TOKENS, totalTokens);
            db.update(TABLE_RECORD, values, RECORD_ID + "=?", new String[]{String.valueOf(recordId)});
        } catch (Exception e) {
            Log.e("ChatDBHelper", "updateRecordTokens 异常", e);
        }
    }

    /**
     * 使用ChatRecordBean更新记录的Token信息
     */
    public void updateRecordTokens(ChatRecordBean record) {
        if (record == null || record.getId() <= 0) return;
        updateRecordTokens(record.getId(), record.getTokenCount(), record.getPromptTokens(),
                record.getCompletionTokens(), record.getTotalTokens());
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