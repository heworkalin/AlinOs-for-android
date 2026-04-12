package alin.android.alinos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.ConfigBean;

public class ConfigDBHelper extends SQLiteOpenHelper {
    // 数据库名和版本
    private static final String DB_NAME = "ai_config.db";
    private static final int DB_VERSION = 3; // 版本号升级为3（原2）
    // 表名和字段
    private static final String TABLE_NAME = "config";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TYPE = "type";
    public static final String COLUMN_SERVER_URL = "server_url";
    public static final String COLUMN_API_KEY = "api_key";
    public static final String COLUMN_IS_DEFAULT = "is_default";
    public static final String COLUMN_MODEL = "model"; // 新增：智慧体型号（如gpt-3.5-turbo/llama3）
    public static final String COLUMN_MAX_RESPONSE_TOKENS = "max_response_tokens"; // 模型最大回复消息
    public static final String COLUMN_USER_INPUT_CHAR_LIMIT = "user_input_char_limit"; // 用户输入字符限制
    public static final String COLUMN_MODEL_CONTEXT_WINDOW = "model_context_window"; // 模型最高极限上下文

    public ConfigDBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 创建表（包含新增的model字段）
        String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_TYPE + " TEXT NOT NULL, " +
                COLUMN_SERVER_URL + " TEXT NOT NULL, " +
                COLUMN_API_KEY + " TEXT, " +
                COLUMN_MODEL + " TEXT NOT NULL, " + // 新增字段
                COLUMN_MAX_RESPONSE_TOKENS + " INTEGER DEFAULT 1024, " + // 模型最大回复消息
                COLUMN_USER_INPUT_CHAR_LIMIT + " INTEGER DEFAULT 2000, " + // 用户输入字符限制
                COLUMN_MODEL_CONTEXT_WINDOW + " INTEGER DEFAULT 4096, " + // 模型最高极限上下文
                COLUMN_IS_DEFAULT + " INTEGER DEFAULT 0)"; // 0=否，1=是
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 逐步升级，避免数据丢失
        for (int version = oldVersion; version < newVersion; version++) {
            switch (version) {
                case 1:
                    // 版本1→2：新增model字段
                    db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_MODEL + " TEXT NOT NULL DEFAULT 'gpt-3.5-turbo'");
                    break;
                case 2:
                    // 版本2→3：新增三个限制字段
                    db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_MAX_RESPONSE_TOKENS + " INTEGER DEFAULT 1024");
                    db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_USER_INPUT_CHAR_LIMIT + " INTEGER DEFAULT 2000");
                    db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_MODEL_CONTEXT_WINDOW + " INTEGER DEFAULT 4096");
                    break;
                default:
                    // 未知版本，重建表
                    db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
                    onCreate(db);
                    return;
            }
        }
    }

    // 新增配置
    public long addConfig(ConfigBean config) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TYPE, config.getType());
        values.put(COLUMN_SERVER_URL, config.getServerUrl());
        values.put(COLUMN_API_KEY, config.getApiKey());
        values.put(COLUMN_MODEL, config.getModel()); // 新增字段赋值
        values.put(COLUMN_MAX_RESPONSE_TOKENS, config.getMaxResponseTokens());
        values.put(COLUMN_USER_INPUT_CHAR_LIMIT, config.getUserInputCharLimit());
        values.put(COLUMN_MODEL_CONTEXT_WINDOW, config.getModelContextWindow());
        values.put(COLUMN_IS_DEFAULT, config.isDefault() ? 1 : 0);
        long id = db.insert(TABLE_NAME, null, values);
        db.close();
        return id;
    }

    // 在 ConfigDBHelper 类中新增（与其他查询方法同级）
    // -------------------- 修正后的 getConfigById 方法 --------------------
    public ConfigBean getConfigById(int configId) {
        ConfigBean config = null;
        SQLiteDatabase db = getReadableDatabase();
        // 替换为你原有定义的表名（TABLE_NAME）和字段名（COLUMN_ID）
        Cursor cursor = db.query(TABLE_NAME, null, COLUMN_ID + "=?",
                new String[]{String.valueOf(configId)}, null, null, null);
        if (cursor.moveToFirst()) {
            config = new ConfigBean();
            // 所有字段都使用你原有定义的 COLUMN_XXX 常量
            config.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)));
            config.setType(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TYPE)));
            config.setServerUrl(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SERVER_URL)));
            config.setApiKey(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_API_KEY)));
            config.setModel(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL)));
            config.setMaxResponseTokens(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MAX_RESPONSE_TOKENS)));
            config.setUserInputCharLimit(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_USER_INPUT_CHAR_LIMIT)));
            config.setModelContextWindow(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MODEL_CONTEXT_WINDOW)));
            config.setDefault(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_DEFAULT)) == 1);
        }
        cursor.close();
        db.close();
        return config;
    }

    // 删除配置
    public void deleteConfig(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_NAME, COLUMN_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    // 更新配置
    public void updateConfig(ConfigBean config) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_TYPE, config.getType());
        values.put(COLUMN_SERVER_URL, config.getServerUrl());
        values.put(COLUMN_API_KEY, config.getApiKey());
        values.put(COLUMN_MODEL, config.getModel()); // 新增字段赋值
        values.put(COLUMN_MAX_RESPONSE_TOKENS, config.getMaxResponseTokens());
        values.put(COLUMN_USER_INPUT_CHAR_LIMIT, config.getUserInputCharLimit());
        values.put(COLUMN_MODEL_CONTEXT_WINDOW, config.getModelContextWindow());
        values.put(COLUMN_IS_DEFAULT, config.isDefault() ? 1 : 0);
        db.update(TABLE_NAME, values, COLUMN_ID + "=?", new String[]{String.valueOf(config.getId())});
        db.close();
    }

    // 设置默认配置（先把所有配置设为非默认，再设当前为默认）
    public void setDefaultConfig(int id) {
        SQLiteDatabase db = getWritableDatabase();
        // 所有配置设为非默认
        ContentValues reset = new ContentValues();
        reset.put(COLUMN_IS_DEFAULT, 0);
        db.update(TABLE_NAME, reset, null, null);
        // 当前配置设为默认
        ContentValues setDefault = new ContentValues();
        setDefault.put(COLUMN_IS_DEFAULT, 1);
        db.update(TABLE_NAME, setDefault, COLUMN_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    // 获取所有配置（删除 @Override 注解）
    public List<ConfigBean> getAllConfigs() {
        List<ConfigBean> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, null, null, null, null, COLUMN_ID + " DESC");
        if (cursor.moveToFirst()) {
            do {
                ConfigBean config = new ConfigBean();
                config.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                config.setType(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TYPE)));
                config.setServerUrl(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SERVER_URL)));
                config.setApiKey(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_API_KEY)));
                config.setModel(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL))); // 读取model
                config.setMaxResponseTokens(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MAX_RESPONSE_TOKENS)));
                config.setUserInputCharLimit(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_USER_INPUT_CHAR_LIMIT)));
                config.setModelContextWindow(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MODEL_CONTEXT_WINDOW)));
                config.setDefault(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_DEFAULT)) == 1);
                list.add(config);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return list;
    }

    // 获取默认配置（删除 @Override 注解）
    public ConfigBean getDefaultConfig() {
        ConfigBean config = null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, COLUMN_IS_DEFAULT + "=1", null, null, null, null);
        if (cursor.moveToFirst()) {
            config = new ConfigBean();
            config.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)));
            config.setType(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TYPE)));
            config.setServerUrl(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SERVER_URL)));
            config.setApiKey(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_API_KEY)));
            config.setModel(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL))); // 读取model
            config.setMaxResponseTokens(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MAX_RESPONSE_TOKENS)));
            config.setUserInputCharLimit(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_USER_INPUT_CHAR_LIMIT)));
            config.setModelContextWindow(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MODEL_CONTEXT_WINDOW)));
            config.setDefault(true);
        }
        cursor.close();
        db.close();
        return config;
    }

    // 必须在 ConfigDBHelper.java 中存在的两个方法（复制粘贴即可）
// -------------------- 通过配置ID获取AI类型 --------------------
public String getConfigTypeByConfigId(int configId) {
    String type = "未知类型";
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = db.query(
            TABLE_NAME,
            new String[]{COLUMN_TYPE}, // 只查询类型字段
            COLUMN_ID + "=?",
            new String[]{String.valueOf(configId)},
            null, null, null
    );
    if (cursor.moveToFirst()) {
        type = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TYPE));
    }
    cursor.close();
    db.close();
    return type;
}

// -------------------- 通过配置ID获取模型名称 --------------------
public String getModelNameByConfigId(int configId) {
    String modelName = "未知模型";
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = db.query(
            TABLE_NAME,
            new String[]{COLUMN_MODEL}, // 只查询模型字段
            COLUMN_ID + "=?",
            new String[]{String.valueOf(configId)},
            null, null, null
    );
    if (cursor.moveToFirst()) {
        modelName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL));
    }
    cursor.close();
    db.close();
    return modelName;
}

// 在 ConfigDBHelper.java 中添加
public ConfigBean getFirstConfig() {
    SQLiteDatabase db = getReadableDatabase();
    ConfigBean config = null;
    
    Cursor cursor = db.query(
        TABLE_NAME,
        null,
        null,
        null,
        null,
        null,
        COLUMN_ID + " ASC",
        "1"  // 限制返回1条
    );
    
    if (cursor != null && cursor.moveToFirst()) {
        config = new ConfigBean();
        config.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)));
        config.setType(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TYPE)));
        config.setServerUrl(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SERVER_URL)));
        config.setApiKey(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_API_KEY)));
        config.setModel(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MODEL)));
        config.setMaxResponseTokens(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MAX_RESPONSE_TOKENS)));
        config.setUserInputCharLimit(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_USER_INPUT_CHAR_LIMIT)));
        config.setModelContextWindow(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MODEL_CONTEXT_WINDOW)));
        config.setDefault(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_DEFAULT)) == 1);
    }
    
    if (cursor != null) {
        cursor.close();
    }
    
    db.close();
    return config;
}
}