package alin.android.alinos.db;

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
    // 数据库基础配置
    private static final String DB_NAME = "chat_db.db";
    private static final int DB_VERSION = 2;

    // 会话表字段 - 使用正确的常量名
    private static final String TABLE_SESSION = "chat_session";
    public static final String SESSION_ID = "id";
    public static final String SESSION_NAME = "session_name";
    public static final String SESSION_CONFIG_ID = "config_id";
    public static final String SESSION_CREATE_TIME = "create_time";
    public static final String SESSION_TYPE = "session_type";

    // 聊天记录表字段
    private static final String TABLE_RECORD = "chat_record";
    public static final String RECORD_ID = "id";
    public static final String RECORD_SESSION_ID = "session_id";
    public static final String RECORD_MSG_TYPE = "msg_type";
    public static final String RECORD_SENDER = "sender";
    public static final String RECORD_CONTENT = "content";
    public static final String RECORD_SEND_TIME = "send_time";
    private final Context context;

    // 构造函数改为public
    public ChatDBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context; // 保存context引用
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 1. 创建会话表
        String createSessionSql = "CREATE TABLE " + TABLE_SESSION + " (" +
                SESSION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                SESSION_NAME + " TEXT NOT NULL, " +
                SESSION_CONFIG_ID + " INTEGER NOT NULL DEFAULT 1, " +
                SESSION_TYPE + " INTEGER DEFAULT 0, " +
                SESSION_CREATE_TIME + " LONG NOT NULL)";
        db.execSQL(createSessionSql);

        // 2. 创建聊天记录表
        String createRecordSql = "CREATE TABLE " + TABLE_RECORD + " (" +
                RECORD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                RECORD_SESSION_ID + " INTEGER NOT NULL, " +
                RECORD_MSG_TYPE + " INTEGER NOT NULL DEFAULT 0, " +
                RECORD_SENDER + " TEXT NOT NULL, " +
                RECORD_CONTENT + " TEXT NOT NULL, " +
                RECORD_SEND_TIME + " LONG NOT NULL)";
        db.execSQL(createRecordSql);

        Log.d("ChatDBHelper", "数据库表创建成功");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            String addSessionTypeSql = "ALTER TABLE " + TABLE_SESSION +
                    " ADD COLUMN " + SESSION_TYPE + " INTEGER DEFAULT 0";
            db.execSQL(addSessionTypeSql);
        } else {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_RECORD);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSION);
            onCreate(db);
        }
    }

    /**
     * 获取或创建悬浮窗默认会话
     */
    public ChatSessionBean getOrCreateFloatWindowSession() {
        String floatSessionName = "悬浮窗会话";
        
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
            "SELECT * FROM " + TABLE_SESSION + " WHERE " + SESSION_NAME + " = ?",
            new String[]{floatSessionName}
        );
        
        if (cursor != null && cursor.moveToFirst()) {
            ChatSessionBean session = new ChatSessionBean();
            session.setId(cursor.getInt(cursor.getColumnIndex(SESSION_ID)));
            session.setSessionName(cursor.getString(cursor.getColumnIndex(SESSION_NAME)));
            session.setConfigId(cursor.getInt(cursor.getColumnIndex(SESSION_CONFIG_ID)));
            session.setCreateTime(cursor.getLong(cursor.getColumnIndex(SESSION_CREATE_TIME)));
            cursor.close();
            return session;
        }
        
        if (cursor != null) cursor.close();
        
        // 创建新会话
        ConfigDBHelper configDbHelper = new ConfigDBHelper(this.context);
        ConfigBean config = configDbHelper.getFirstConfig();
        int configId = config != null ? config.getId() : 1;
        
        ChatSessionBean newSession = new ChatSessionBean(
            floatSessionName,
            configId,
            System.currentTimeMillis()
        );
        
        long sessionId = addSession(newSession);
        newSession.setId((int) sessionId);
        
        return newSession;
    }
    
    public void deleteFloatWindowSession() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SESSION, SESSION_TYPE + "=?", new String[]{"1"});
        db.close();
    }

    /**
     * 获取所有会话
     */
    public List<ChatSessionBean> getAllSessions() {
        List<ChatSessionBean> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_SESSION, null, null, null, null, null, 
                SESSION_CREATE_TIME + " DESC");
        
        if (cursor.moveToFirst()) {
            do {
                ChatSessionBean session = new ChatSessionBean();
                session.setId(cursor.getInt(cursor.getColumnIndexOrThrow(SESSION_ID)));
                session.setSessionName(cursor.getString(cursor.getColumnIndexOrThrow(SESSION_NAME)));
                session.setConfigId(cursor.getInt(cursor.getColumnIndexOrThrow(SESSION_CONFIG_ID)));
                session.setCreateTime(cursor.getLong(cursor.getColumnIndexOrThrow(SESSION_CREATE_TIME)));
                
                // 获取session_type（可能不存在）
                try {
                    session.setSessionType(cursor.getInt(cursor.getColumnIndexOrThrow(SESSION_TYPE)));
                } catch (Exception e) {
                    session.setSessionType(0); // 默认普通会话
                }
                
                list.add(session);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return list;
    }

    /**
     * 新增会话
     */
    public long addSession(ChatSessionBean session) {
        if (session == null) return -1;

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SESSION_NAME, session.getSessionName());
        values.put(SESSION_CONFIG_ID, session.getConfigId());
        values.put(SESSION_TYPE, session.getSessionType());
        values.put(SESSION_CREATE_TIME, session.getCreateTime());

        long id = db.insert(TABLE_SESSION, null, values);
        db.close();
        return id;
    }

    /**
     * 新增聊天记录
     */
    public long addRecord(ChatRecordBean record) {
        if (record == null || record.getSessionId() <= 0 || 
            TextUtils.isEmpty(record.getSender()) || TextUtils.isEmpty(record.getContent())) {
            return -1;
        }

        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(RECORD_SESSION_ID, record.getSessionId());
        values.put(RECORD_MSG_TYPE, record.getMsgType());
        values.put(RECORD_SENDER, record.getSender());
        values.put(RECORD_CONTENT, record.getContent());
        values.put(RECORD_SEND_TIME, record.getSendTime());

        long id = db.insert(TABLE_RECORD, null, values);
        db.close();
        return id;
    }

    /**
     * 根据会话ID获取聊天记录
     */
    public List<ChatRecordBean> getRecordsBySessionId(int sessionId) {
        List<ChatRecordBean> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_RECORD, null, RECORD_SESSION_ID + "=?",
                new String[]{String.valueOf(sessionId)}, null, null, RECORD_SEND_TIME + " ASC");
        
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
        cursor.close();
        db.close();
        return list;
    }

    /**
     * 删除某个会话的所有记录
     */
    public void deleteRecordsBySessionId(int sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_RECORD, RECORD_SESSION_ID + "=?", 
                new String[]{String.valueOf(sessionId)});
        db.close();
    }

    /**
     * 更新会话
     */
    public void updateSession(ChatSessionBean session) {
        if (session == null) return;
        
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SESSION_NAME, session.getSessionName());
        values.put(SESSION_CONFIG_ID, session.getConfigId());
        values.put(SESSION_CREATE_TIME, session.getCreateTime());
        
        db.update(TABLE_SESSION, values, SESSION_ID + "=?",
                new String[]{String.valueOf(session.getId())});
        db.close();
    }

    /**
     * 删除会话
     */
    public void deleteSession(int sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        
        try {
            db.beginTransaction();
            db.delete(TABLE_RECORD, RECORD_SESSION_ID + "=?",
                    new String[]{String.valueOf(sessionId)});
            db.delete(TABLE_SESSION, SESSION_ID + "=?",
                    new String[]{String.valueOf(sessionId)});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e("ChatDBHelper", "删除会话失败", e);
        } finally {
            db.endTransaction();
            db.close();
        }
    }

        // ====== 新增：更新消息内容（修复找不到方法错误）======
    public void updateRecordContent(long recordId, String newContent) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("content", newContent); // 对应chat_record表的content字段
        db.update("chat_record", values, "id = ?", new String[]{String.valueOf(recordId)});
        db.close();
    }

    // ====== 新增：根据ID获取消息内容（修复找不到方法错误）======
    public String getRecordContentById(long recordId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String content = "";
        Cursor cursor = db.query(
                "chat_record", 
                new String[]{"content"}, 
                "id=?", 
                new String[]{String.valueOf(recordId)}, 
                null, null, null
        );
        if (cursor.moveToFirst()) {
            content = cursor.getString(cursor.getColumnIndexOrThrow("content"));
        }
        cursor.close();
        db.close();
        return content;
    }
}