package alin.android.alinos.voice;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import alin.android.alinos.log.AlinLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户声纹/声音数据本地数据库（SQLite）。
 *
 * 记录内容：
 *  - keyword     唤醒词（主键）
 *  - embedding   声纹向量（逗号分隔的 float 字符串，如 "0.1,0.2,..."）
 *  - audio_path  用户声音样本（WAV 文件绝对路径，存于 files/voiceprints/）
 *  - created_at  首次保存时间
 *  - updated_at  最近更新/覆盖时间
 *
 * 支持：保存（insert/replace）、更新（同 keyword 覆盖）、删除（连带删除音频文件）。
 */
public class VoiceprintStore extends SQLiteOpenHelper {

    private static final String TAG = "VoiceprintStore";
    private static final String DB_NAME = "voiceprints.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "voiceprints";
    private static final String COL_KEYWORD = "keyword";
    private static final String COL_EMBEDDING = "embedding";
    private static final String COL_AUDIO = "audio_path";
    private static final String COL_CREATED = "created_at";
    private static final String COL_UPDATED = "updated_at";

    private final Context mContext;

    public VoiceprintStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        mContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + COL_KEYWORD + " TEXT PRIMARY KEY, "
                + COL_EMBEDDING + " TEXT NOT NULL, "
                + COL_AUDIO + " TEXT, "
                + COL_CREATED + " INTEGER, "
                + COL_UPDATED + " INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    /** 一条声纹记录 */
    public static class Record {
        public String keyword;
        public float[] embedding;
        public String audioPath;
        public long createdAt;
        public long updatedAt;
    }

    /**
     * 保存（插入或覆盖更新同 keyword 的记录）。
     *
     * @return 若返回 true 表示保存成功；false 表示 keyword 为空或 embedding 为空
     */
    public boolean save(String keyword, float[] embedding, String audioPath) {
        if (keyword == null || keyword.isEmpty() || embedding == null || embedding.length == 0) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put(COL_KEYWORD, keyword);
        cv.put(COL_EMBEDDING, sb.toString());
        cv.put(COL_AUDIO, audioPath);
        cv.put(COL_UPDATED, now);
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery("SELECT " + COL_CREATED + " FROM " + TABLE
                    + " WHERE " + COL_KEYWORD + "=?", new String[]{keyword});
            boolean exists = c.moveToFirst();
            long created = exists ? c.getLong(0) : now;
            c.close();
            cv.put(COL_CREATED, created);
            long id = db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            AlinLog.d(TAG, "保存声纹: keyword=" + keyword + ", embLen=" + embedding.length
                    + ", audio=" + audioPath + (exists ? " (更新)" : " (新增)"));
            return id != -1;
        } catch (Exception e) {
            AlinLog.e(TAG, "保存声纹失败", e);
            return false;
        }
    }

    /** 查询所有记录 */
    public List<Record> getAll() {
        List<Record> list = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery("SELECT " + COL_KEYWORD + "," + COL_EMBEDDING + "," + COL_AUDIO
                    + "," + COL_CREATED + "," + COL_UPDATED + " FROM " + TABLE
                    + " ORDER BY " + COL_UPDATED + " DESC", null);
            while (c.moveToNext()) {
                Record r = new Record();
                r.keyword = c.getString(0);
                r.embedding = parseEmbedding(c.getString(1));
                r.audioPath = c.getString(2);
                r.createdAt = c.getLong(3);
                r.updatedAt = c.getLong(4);
                list.add(r);
            }
            c.close();
        } catch (Exception e) {
            AlinLog.e(TAG, "查询声纹失败", e);
        }
        return list;
    }

    /** 按唤醒词删除记录，并删除关联的音频文件 */
    public boolean delete(String keyword) {
        if (keyword == null || keyword.isEmpty()) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery("SELECT " + COL_AUDIO + " FROM " + TABLE
                    + " WHERE " + COL_KEYWORD + "=?", new String[]{keyword});
            if (c.moveToFirst()) {
                String audio = c.getString(0);
                if (audio != null && !audio.isEmpty()) {
                    File f = new File(audio);
                    if (f.exists() && !f.delete()) {
                        AlinLog.w(TAG, "删除音频文件失败: " + audio);
                    }
                }
            }
            c.close();
            int n = db.delete(TABLE, COL_KEYWORD + "=?", new String[]{keyword});
            AlinLog.d(TAG, "删除声纹: keyword=" + keyword + ", rows=" + n);
            return n > 0;
        } catch (Exception e) {
            AlinLog.e(TAG, "删除声纹失败", e);
            return false;
        }
    }

    /** 声纹保存目录：files/voiceprints/ */
    public File getAudioDir() {
        File dir = new File(mContext.getFilesDir(), "voiceprints");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private float[] parseEmbedding(String s) {
        if (s == null || s.isEmpty()) return new float[0];
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0f;
            }
        }
        return out;
    }
}
