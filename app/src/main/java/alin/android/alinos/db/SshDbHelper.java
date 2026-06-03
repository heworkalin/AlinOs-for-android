package alin.android.alinos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.bean.SshConfigBean;

public class SshDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "ssh_config.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE_NAME = "ssh_config";

    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_HOST = "host";
    public static final String COL_PORT = "port";
    public static final String COL_USERNAME = "username";
    public static final String COL_PASSWORD = "password";
    public static final String COL_AUTH_TYPE = "auth_type";
    public static final String COL_KEY_CONTENT = "key_content";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_CONFIG_TYPE = "config_type";

    public SshDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_NAME + " TEXT, " +
                COL_HOST + " TEXT NOT NULL, " +
                COL_PORT + " INTEGER DEFAULT 8022, " +
                COL_USERNAME + " TEXT, " +
                COL_PASSWORD + " TEXT, " +
                COL_AUTH_TYPE + " TEXT DEFAULT 'password', " +
                COL_KEY_CONTENT + " TEXT, " +
                COL_DESCRIPTION + " TEXT, " +
                COL_CONFIG_TYPE + " TEXT DEFAULT 'remote')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COL_DESCRIPTION + " TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COL_CONFIG_TYPE + " TEXT DEFAULT 'remote'");
        }
    }

    public long addConfig(SshConfigBean config) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_NAME, config.getName());
        values.put(COL_HOST, config.getHost());
        values.put(COL_PORT, config.getPort());
        values.put(COL_USERNAME, config.getUsername());
        values.put(COL_PASSWORD, config.getPassword());
        values.put(COL_AUTH_TYPE, config.getAuthType());
        values.put(COL_KEY_CONTENT, config.getKeyContent());
        values.put(COL_DESCRIPTION, config.getDescription());
        values.put(COL_CONFIG_TYPE, config.getConfigType());
        long id = db.insert(TABLE_NAME, null, values);
        db.close();
        return id;
    }

    public void updateConfig(SshConfigBean config) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_NAME, config.getName());
        values.put(COL_HOST, config.getHost());
        values.put(COL_PORT, config.getPort());
        values.put(COL_USERNAME, config.getUsername());
        values.put(COL_PASSWORD, config.getPassword());
        values.put(COL_AUTH_TYPE, config.getAuthType());
        values.put(COL_KEY_CONTENT, config.getKeyContent());
        values.put(COL_DESCRIPTION, config.getDescription());
        values.put(COL_CONFIG_TYPE, config.getConfigType());
        db.update(TABLE_NAME, values, COL_ID + "=?", new String[]{String.valueOf(config.getId())});
        db.close();
    }

    public void deleteConfig(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_NAME, COL_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public List<SshConfigBean> getAllConfigs() {
        List<SshConfigBean> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, null, null, null, null, COL_ID + " DESC");
        if (cursor.moveToFirst()) {
            do {
                list.add(cursorToBean(cursor));
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return list;
    }

    /** 查找 local_termux 配置（仅一条），不存在返回 null。 */
    public SshConfigBean getLocalTermuxConfig() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, COL_CONFIG_TYPE + "=?",
                new String[]{"local_termux"}, null, null, null, "1");
        SshConfigBean config = null;
        if (cursor.moveToFirst()) {
            config = cursorToBean(cursor);
        }
        cursor.close();
        db.close();
        return config;
    }

    public SshConfigBean getConfigById(int id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, COL_ID + "=?", new String[]{String.valueOf(id)}, null, null, null);
        SshConfigBean config = null;
        if (cursor.moveToFirst()) {
            config = cursorToBean(cursor);
        }
        cursor.close();
        db.close();
        return config;
    }

    private SshConfigBean cursorToBean(Cursor cursor) {
        SshConfigBean config = new SshConfigBean();
        config.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID)));
        config.setName(cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)));
        config.setHost(cursor.getString(cursor.getColumnIndexOrThrow(COL_HOST)));
        config.setPort(cursor.getInt(cursor.getColumnIndexOrThrow(COL_PORT)));
        config.setUsername(cursor.getString(cursor.getColumnIndexOrThrow(COL_USERNAME)));
        config.setPassword(cursor.getString(cursor.getColumnIndexOrThrow(COL_PASSWORD)));
        config.setAuthType(cursor.getString(cursor.getColumnIndexOrThrow(COL_AUTH_TYPE)));
        config.setKeyContent(cursor.getString(cursor.getColumnIndexOrThrow(COL_KEY_CONTENT)));
        config.setDescription(cursor.getString(cursor.getColumnIndexOrThrow(COL_DESCRIPTION)));
        config.setConfigType(cursor.getString(cursor.getColumnIndexOrThrow(COL_CONFIG_TYPE)));
        return config;
    }
}
