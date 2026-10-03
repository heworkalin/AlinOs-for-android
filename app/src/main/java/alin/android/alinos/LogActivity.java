package alin.android.alinos;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.log.AlinLog;
import alin.android.alinos.log.LogLevel;
import alin.android.alinos.log.LogRecord;

/**
 * 统一日志界面（开发者向）。
 *
 * <p>功能：调试等级筛选、tag / 关键字过滤、实时刷新、清空、分享、导出。
 */
public class LogActivity extends AppCompatActivity {

    private static final LogLevel[] LEVELS = {
            LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO,
            LogLevel.WARN, LogLevel.ERROR, LogLevel.CRASH
    };

    private Spinner spLevel;
    private EditText etFilter;
    private TextView tvStatus;
    private CheckBox cbSensitive;
    private ListView lvLogs;

    private LogAdapter adapter;
    private LogLevel currentLevel = LogLevel.DEBUG;
    private String currentFilter = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean paused = false;

    private boolean refreshPending = false;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (refreshPending) {
                refreshPending = false;
                refresh();
            }
        }
    };

    /** 实时监听：新日志到达时刷新（合并节流）。 */
    private final AlinLog.Listener listener = record -> {
        if (paused) return;
        refreshPending = true;
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, 300);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        spLevel = findViewById(R.id.sp_level);
        etFilter = findViewById(R.id.et_filter);
        tvStatus = findViewById(R.id.tv_status);
        cbSensitive = findViewById(R.id.cb_sensitive);
        lvLogs = findViewById(R.id.lv_logs);
        Button btnClear = findViewById(R.id.btn_clear);
        Button btnShare = findViewById(R.id.btn_share);
        Button btnExport = findViewById(R.id.btn_export);

        // 等级 Spinner
        List<String> names = new ArrayList<>();
        for (LogLevel l : LEVELS) names.add(levelLabel(l));
        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        spLevel.setAdapter(levelAdapter);
        currentLevel = AlinLog.getMinLevel();
        spLevel.setSelection(indexOfLevel(currentLevel));
        spLevel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                currentLevel = LEVELS[pos];
                AlinLog.setMinLevel(currentLevel);
                refresh();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        // 过滤
        etFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                currentFilter = s.toString().trim();
                refresh();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        cbSensitive.setChecked(AlinLog.isFilterSensitive());
        cbSensitive.setOnCheckedChangeListener((v, checked) -> {
            AlinLog.setFilterSensitive(checked);
            Toast.makeText(this, checked ? "已开启敏感信息脱敏" : "已关闭脱敏", Toast.LENGTH_SHORT).show();
            refresh();
        });

        btnClear.setOnClickListener(v -> {
            AlinLog.clear();
            refresh();
            Toast.makeText(this, "已清空日志", Toast.LENGTH_SHORT).show();
        });
        btnShare.setOnClickListener(v -> shareLogs());
        btnExport.setOnClickListener(v -> exportLogs());

        adapter = new LogAdapter();
        lvLogs.setAdapter(adapter);

        AlinLog.addListener(listener);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        handler.removeCallbacks(refreshTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AlinLog.removeListener(listener);
        handler.removeCallbacks(refreshTask);
    }

    private int indexOfLevel(LogLevel l) {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == l) return i;
        }
        return 1;
    }

    private String levelLabel(LogLevel l) {
        switch (l) {
            case VERBOSE: return "全部 (V)";
            case DEBUG: return "调试 (D+)";
            case INFO: return "信息 (I+)";
            case WARN: return "警告 (W+)";
            case ERROR: return "错误 (E+)";
            case CRASH: return "崩溃 (C)";
            default: return l.name();
        }
    }

    private void refresh() {
        List<LogRecord> records = AlinLog.query(currentLevel, null, currentFilter, 1000);
        adapter.setData(records);
        tvStatus.setText("显示 " + records.size() + " 条 · 等级 " + currentLevel.shortName
                + (currentFilter.isEmpty() ? "" : " · 过滤「" + currentFilter + "」"));
    }

    // ================================================================
    //  导出 / 分享
    // ================================================================

    private String buildExportText() {
        List<LogRecord> records = AlinLog.query(currentLevel, null, currentFilter, 5000);
        StringBuilder sb = new StringBuilder();
        sb.append("# AlinOs 日志导出\n");
        sb.append("# 时间: ").append(alin.android.alinos.log.LogFormat.fileTime(System.currentTimeMillis())).append('\n');
        sb.append("# 等级: ").append(currentLevel.name()).append('\n');
        if (!currentFilter.isEmpty()) sb.append("# 过滤: ").append(currentFilter).append('\n');
        sb.append("# 条数: ").append(records.size()).append("\n\n");
        for (LogRecord r : records) {
            sb.append(alin.android.alinos.log.LogFormat.fileTime(r.timestamp)).append(' ')
              .append(r.level.shortName).append('/').append(r.tag).append(": ")
              .append(r.message).append('\n');
            if (r.throwable != null && !r.throwable.isEmpty()) {
                sb.append(r.throwable);
                if (!r.throwable.endsWith("\n")) sb.append('\n');
            }
        }
        return sb.toString();
    }

    private void shareLogs() {
        String text = buildExportText();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "AlinOs 日志");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(intent, "分享日志"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static final int REQ_EXPORT = 1001;

    /**
     * 导出：用系统文件管理器（SAF）让用户选择保存位置。
     *
     * <p>不写入应用私有路径：由系统弹窗，用户选目录/文件名，
     * 我们仅获得返回 Uri 的临时写入授权，由系统代为落盘。
     */
    private void exportLogs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,
                "alinos_log_" + alin.android.alinos.log.LogFormat.stamp(System.currentTimeMillis()) + ".txt");
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件管理器: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        final String text = buildExportText();
        new Thread(() -> {
            String err = null;
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new java.io.IOException("openOutputStream returned null");
                os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> Toast.makeText(LogActivity.this,
                    ferr == null ? "日志已保存" : "保存失败: " + ferr,
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    // ================================================================
    //  ListView
    // ================================================================

    private class LogAdapter extends BaseAdapter {
        private final List<LogRecord> data = new ArrayList<>();

        void setData(List<LogRecord> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int pos) { return data.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_log, parent, false);
            }
            LogRecord r = data.get(position);
            TextView tvLevel = v.findViewById(R.id.tv_log_level);
            TextView tvTag = v.findViewById(R.id.tv_log_tag);
            TextView tvTime = v.findViewById(R.id.tv_log_time);
            TextView tvMsg = v.findViewById(R.id.tv_log_msg);

            tvLevel.setText(r.level.shortName);
            tvLevel.getBackground().setTint(levelColor(r.level));
            tvTag.setText(r.tag);
            tvTime.setText(alin.android.alinos.log.LogFormat.time(r.timestamp));

            String msg = r.message;
            if (r.throwable != null && !r.throwable.isEmpty()) {
                msg = msg + "\n" + r.throwable;
            }
            tvMsg.setText(msg);
            return v;
        }

        private int levelColor(LogLevel l) {
            switch (l) {
                case VERBOSE: return 0xFF909399;
                case DEBUG:   return 0xFF409EFF;
                case INFO:    return 0xFF67C23A;
                case WARN:    return 0xFFE6A23C;
                case ERROR:   return 0xFFF56C6C;
                case CRASH:   return 0xFF8E44AD;
                default:      return 0xFF909399;
            }
        }
    }
}
