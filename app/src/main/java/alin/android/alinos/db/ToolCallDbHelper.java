package alin.android.alinos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ToolCallLogBean;

/**
 * 工具调用日志数据库。
 * 存储每次 AI 工具调用的完整入参/出参/耗时/状态，供 UI 回溯和调试审计。
 */
public class ToolCallDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "tool_call_log.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_NAME = "tool_call_log";

    public static final String COL_ID = "id";
    public static final String COL_SESSION_ID = "session_id";
    public static final String COL_RECORD_ID = "record_id";
    public static final String COL_TOOL_NAME = "tool_name";
    public static final String COL_TOOL_CALL_ID = "tool_call_id";
    public static final String COL_ARGUMENTS = "arguments";
    public static final String COL_RESULT = "result";
    public static final String COL_STATUS = "status";
    public static final String COL_ERROR_MESSAGE = "error_message";
    public static final String COL_DURATION_MS = "duration_ms";
    public static final String COL_CREATED_AT = "created_at";

    public ToolCallDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE_NAME + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_SESSION_ID + " INTEGER NOT NULL, "
                + COL_RECORD_ID + " INTEGER DEFAULT -1, "
                + COL_TOOL_NAME + " TEXT NOT NULL, "
                + COL_TOOL_CALL_ID + " TEXT, "
                + COL_ARGUMENTS + " TEXT, "
                + COL_RESULT + " TEXT, "
                + COL_STATUS + " TEXT DEFAULT 'pending', "
                + COL_ERROR_MESSAGE + " TEXT, "
                + COL_DURATION_MS + " INTEGER DEFAULT 0, "
                + COL_CREATED_AT + " INTEGER NOT NULL)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /** 插入一条工具调用记录，返回 id。 */
    public long insert(ToolCallLogBean bean) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COL_SESSION_ID, bean.getSessionId());
        v.put(COL_RECORD_ID, bean.getRecordId());
        v.put(COL_TOOL_NAME, bean.getToolName());
        v.put(COL_TOOL_CALL_ID, bean.getToolCallId());
        v.put(COL_ARGUMENTS, bean.getArguments());
        v.put(COL_RESULT, bean.getResult());
        v.put(COL_STATUS, bean.getStatus());
        v.put(COL_ERROR_MESSAGE, bean.getErrorMessage());
        v.put(COL_DURATION_MS, bean.getDurationMs());
        v.put(COL_CREATED_AT, bean.getCreatedAt());
        long id = db.insert(TABLE_NAME, null, v);
        db.close();
        return id;
    }

    /** 更新执行结果和状态。 */
    public void updateResult(long id, String result, String status, String errorMessage, long durationMs) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COL_RESULT, result);
        v.put(COL_STATUS, status);
        v.put(COL_ERROR_MESSAGE, errorMessage);
        v.put(COL_DURATION_MS, durationMs);
        db.update(TABLE_NAME, v, COL_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** 更新关联的聊天记录 ID。 */
    public void updateRecordId(long id, int recordId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COL_RECORD_ID, recordId);
        db.update(TABLE_NAME, v, COL_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** 按 sessionId 查询所有调用记录。 */
    public List<ToolCallLogBean> getBySessionId(int sessionId) {
        return query(COL_SESSION_ID + "=?", new String[]{String.valueOf(sessionId)});
    }

    /** 按 recordId 查询。 */
    public List<ToolCallLogBean> getByRecordId(int recordId) {
        return query(COL_RECORD_ID + "=?", new String[]{String.valueOf(recordId)});
    }

    /** 按 id 查询单条。 */
    public ToolCallLogBean getById(int id) {
        List<ToolCallLogBean> list = query(COL_ID + "=?", new String[]{String.valueOf(id)});
        return list.isEmpty() ? null : list.get(0);
    }

    /** 删除某个会话的所有记录。 */
    public void deleteBySessionId(int sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_NAME, COL_SESSION_ID + "=?", new String[]{String.valueOf(sessionId)});
        db.close();
    }

    private List<ToolCallLogBean> query(String where, String[] args) {
        List<ToolCallLogBean> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_NAME, null, where, args, null, null, COL_CREATED_AT + " ASC");
        if (c.moveToFirst()) {
            do {
                list.add(cursorToBean(c));
            } while (c.moveToNext());
        }
        c.close();
        db.close();
        return list;
    }

    private ToolCallLogBean cursorToBean(Cursor c) {
        ToolCallLogBean b = new ToolCallLogBean();
        b.setId(c.getInt(c.getColumnIndexOrThrow(COL_ID)));
        b.setSessionId(c.getInt(c.getColumnIndexOrThrow(COL_SESSION_ID)));
        b.setRecordId(c.getInt(c.getColumnIndexOrThrow(COL_RECORD_ID)));
        b.setToolName(c.getString(c.getColumnIndexOrThrow(COL_TOOL_NAME)));
        b.setToolCallId(c.getString(c.getColumnIndexOrThrow(COL_TOOL_CALL_ID)));
        b.setArguments(c.getString(c.getColumnIndexOrThrow(COL_ARGUMENTS)));
        b.setResult(c.getString(c.getColumnIndexOrThrow(COL_RESULT)));
        b.setStatus(c.getString(c.getColumnIndexOrThrow(COL_STATUS)));
        b.setErrorMessage(c.getString(c.getColumnIndexOrThrow(COL_ERROR_MESSAGE)));
        b.setDurationMs(c.getLong(c.getColumnIndexOrThrow(COL_DURATION_MS)));
        b.setCreatedAt(c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)));
        return b;
    }
}
