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
 * 全局配置 + 模型注册数据库（SQLite）。
 *
 * 两张表：
 *  - configs：界面配置键值（asr_engine / asr_model / tts_engine / tts_model / tts_speed / kws_keyword / kws_threshold ...）
 *  - models ：模型注册表（内置 + 自定义），绑定实际路径，调用方一律从本表取路径
 *
 * 关键能力：
 *  - get/setConfig：界面预选配置持久化
 *  - registerModel：下载完成 / 自定义导入后注册模型（同 path 幂等更新）
 *  - getModels(type)：按类型取已注册模型
 *  - scanAndBuild(modelDir)：扫描用户已下载的模型目录，自动建库（幂等）
 */
public class AppConfigStore extends SQLiteOpenHelper {

    private static final String TAG = "AppConfigStore";
    private static final String DB_NAME = "app_config.db";
    private static final int DB_VERSION = 1;

    // configs 表
    private static final String T_CONFIG = "configs";
    private static final String C_KEY = "key";
    private static final String C_VALUE = "value";

    // models 表
    private static final String T_MODEL = "models";
    private static final String C_ID = "id";
    private static final String C_TYPE = "type";       // asr/tts/kws/speaker/vad
    private static final String C_SUBTYPE = "subtype"; // paraformer/sensevoice/whisper/melo/... / custom
    private static final String C_NAME = "name";
    private static final String C_PATH = "path";
    private static final String C_BUILTIN = "builtin"; // 1=内置下载, 0=自定义
    private static final String C_META = "meta";       // 备注/说明
    private static final String C_CREATED = "created_at";

    private static AppConfigStore instance;

    public static synchronized AppConfigStore getInstance(Context ctx) {
        if (instance == null) instance = new AppConfigStore(ctx);
        return instance;
    }

    private AppConfigStore(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_CONFIG + " ("
                + C_KEY + " TEXT PRIMARY KEY, " + C_VALUE + " TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_MODEL + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_TYPE + " TEXT, " + C_SUBTYPE + " TEXT, " + C_NAME + " TEXT, "
                + C_PATH + " TEXT, " + C_BUILTIN + " INTEGER, " + C_META + " TEXT, "
                + C_CREATED + " INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_model_type ON " + T_MODEL + "(" + C_TYPE + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + T_CONFIG);
        db.execSQL("DROP TABLE IF EXISTS " + T_MODEL);
        onCreate(db);
    }

    // ==================== 配置键值 ====================

    public String getConfig(String key, String def) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery("SELECT " + C_VALUE + " FROM " + T_CONFIG
                    + " WHERE " + C_KEY + "=?", new String[]{key});
            String v = c.moveToFirst() ? c.getString(0) : def;
            c.close();
            return v;
        } catch (Exception e) {
            AlinLog.e(TAG, "getConfig 失败: " + key, e);
            return def;
        }
    }

    public void setConfig(String key, String value) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(C_KEY, key);
            cv.put(C_VALUE, value);
            getWritableDatabase().insertWithOnConflict(
                    T_CONFIG, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            AlinLog.e(TAG, "setConfig 失败: " + key, e);
        }
    }

    // ==================== 模型注册表 ====================

    public static class Model {
        public long id;
        public String type;
        public String subtype;
        public String name;
        public String path;
        public boolean builtin;
        public String meta;
        public long createdAt;
    }

    /** 注册模型（同 path 幂等更新，不重复插入） */
    public void registerModel(String type, String subtype, String name,
                              String path, boolean builtin, String meta) {
        if (path == null || path.isEmpty()) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery("SELECT " + C_ID + " FROM " + T_MODEL
                    + " WHERE " + C_PATH + "=?", new String[]{path});
            long now = System.currentTimeMillis();
            ContentValues cv = new ContentValues();
            cv.put(C_TYPE, type);
            cv.put(C_SUBTYPE, subtype);
            cv.put(C_NAME, name);
            cv.put(C_PATH, path);
            cv.put(C_BUILTIN, builtin ? 1 : 0);
            cv.put(C_META, meta == null ? "" : meta);
            cv.put(C_CREATED, now);
            if (c.moveToFirst()) {
                db.update(T_MODEL, cv, C_ID + "=?", new String[]{String.valueOf(c.getLong(0))});
            } else {
                db.insert(T_MODEL, null, cv);
            }
            c.close();
            AlinLog.d(TAG, "注册模型: type=" + type + " subtype=" + subtype + " path=" + path);
        } catch (Exception e) {
            AlinLog.e(TAG, "registerModel 失败", e);
        }
    }

    /** 按类型取模型列表 */
    public List<Model> getModels(String type) {
        List<Model> list = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery("SELECT " + C_ID + "," + C_TYPE + "," + C_SUBTYPE + ","
                    + C_NAME + "," + C_PATH + "," + C_BUILTIN + "," + C_META + "," + C_CREATED
                    + " FROM " + T_MODEL + " WHERE " + C_TYPE + "=? ORDER BY " + C_BUILTIN + " DESC, " + C_CREATED + " DESC",
                    new String[]{type});
            while (c.moveToNext()) {
                Model m = new Model();
                m.id = c.getLong(0);
                m.type = c.getString(1);
                m.subtype = c.getString(2);
                m.name = c.getString(3);
                m.path = c.getString(4);
                m.builtin = c.getInt(5) == 1;
                m.meta = c.getString(6);
                m.createdAt = c.getLong(7);
                list.add(m);
            }
            c.close();
        } catch (Exception e) {
            AlinLog.e(TAG, "getModels 失败: " + type, e);
        }
        return list;
    }

    /** 删除模型记录（自定义模型用；内置模型建议只删记录不删文件由下载界面控制） */
    public boolean removeModel(long id) {
        try {
            return getWritableDatabase().delete(T_MODEL, C_ID + "=?", new String[]{String.valueOf(id)}) > 0;
        } catch (Exception e) {
            AlinLog.e(TAG, "removeModel 失败", e);
            return false;
        }
    }

    // ==================== 扫描已下载模型自动建库 ====================

    /**
     * 扫描 files/voice_models 目录，把已下载的内置模型自动注册到 models 表（幂等）。
     * 供首次使用/升级后自动建库。
     */
    public void scanAndBuild(File modelDir) {
        if (modelDir == null || !modelDir.exists()) return;
        scanAsr(new File(modelDir, "asr"));
        scanTts(new File(modelDir, "tts"));
        scanKws(new File(modelDir, "kws"));
        scanSpeaker(new File(modelDir, "speaker"));
        scanVad(new File(modelDir, "vad"));
        AlinLog.d(TAG, "模型扫描完成: " + modelDir);
    }

    private void scanAsr(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) return;
        for (File s : subs) {
            if (new File(s, "tokens.txt").exists() || hasOnnx(s)) {
                registerModel("asr", s.getName(), s.getName(), s.getAbsolutePath(), true, "内置下载");
            }
        }
    }

    private void scanTts(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) return;
        for (File s : subs) {
            if (hasOnnx(s)) {
                registerModel("tts", s.getName(), s.getName(), s.getAbsolutePath(), true, "内置下载");
            }
        }
    }

    private void scanKws(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) return;
        for (File s : subs) {
            if (new File(s, "tokens.txt").exists()) {
                registerModel("kws", s.getName(), s.getName(), s.getAbsolutePath(), true, "内置下载");
            }
        }
    }

    private void scanSpeaker(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".onnx"));
        if (files == null) return;
        for (File f : files) {
            registerModel("speaker", f.getName(), f.getName(), f.getAbsolutePath(), true, "内置下载");
        }
    }

    private void scanVad(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".onnx"));
        if (files == null) return;
        for (File f : files) {
            registerModel("vad", f.getName(), f.getName(), f.getAbsolutePath(), true, "内置下载");
        }
    }

    private boolean hasOnnx(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".onnx"));
        return files != null && files.length > 0;
    }
}
