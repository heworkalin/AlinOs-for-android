package alin.android.alinos.dev;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import alin.android.alinos.R;
import alin.android.alinos.localshell.LocalShellExecutor;
import alin.android.alinos.tools.ToolMeta;
import alin.android.alinos.tools.ToolRegistry;
/**
 * 通用工具测试界面。
 * 从 {@link ToolRegistry} 加载所有工具，支持表单/JSON 双模式输入，
 * 异步执行并展示结果。
 */
public class DevToolsActivity extends AppCompatActivity {

    // UI
    private EditText etSearch;
    private ListView lvToolResults;
    private TextView tvSignature;
    private LinearLayout layoutForm;
    private TextView tvNoParams;
    private EditText etJsonEditor;
    private SwitchCompat switchMode;
    private Button btnExecute, btnReset;
    private TextView tvStatus, tvResult;
    private TextView tvViewRaw, tvViewPretty;
    private ScrollView svResult;

    // 状态
    private ToolMeta currentTool;
    private final Map<String, View> paramViews = new HashMap<>();
    private boolean isFormMode = true;
    private boolean isPrettyView = false;
    private JSONObject lastResult = null;
    private List<ToolMeta> allTools;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dev_tools);

        allTools = ToolRegistry.getAllTools();
        bindViews();

        // 注入 Context，此后 AI 通过 create_session 即可自动完成初始化
        LocalShellExecutor.provideContext(this);

        setupToolSelector();
        setupModeToggle();
        setupViewToggle();
        setupButtons();
        setupHelp();
    }

    // ================================================================
    //  View 绑定
    // ================================================================

    private void bindViews() {
        etSearch = findViewById(R.id.actv_tool_selector);
        lvToolResults = findViewById(R.id.lv_tool_results);
        tvSignature = findViewById(R.id.tv_function_signature);
        layoutForm = findViewById(R.id.layout_form);
        tvNoParams = findViewById(R.id.tv_no_params);
        etJsonEditor = findViewById(R.id.et_json_editor);
        switchMode = findViewById(R.id.switch_mode);
        btnExecute = findViewById(R.id.btn_execute);
        btnReset = findViewById(R.id.btn_reset);
        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);
        tvViewRaw = findViewById(R.id.tv_view_raw);
        tvViewPretty = findViewById(R.id.tv_view_pretty);
        svResult = findViewById(R.id.sv_result);

        findViewById(R.id.tv_tool_help).setOnClickListener(v -> showToolHelpDialog());
    }

    // ================================================================
    //  工具选择器（搜索 + 下拉）
    // ================================================================

    private void setupToolSelector() {
        List<String> names = new ArrayList<>();
        for (ToolMeta t : allTools) names.add(t.displayName);

        ArrayAdapter<String> resultsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        lvToolResults.setAdapter(resultsAdapter);

        // 搜索过滤 → 实时显示匹配结果
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                if (query.isEmpty()) {
                    lvToolResults.setVisibility(View.GONE);
                    if (s.length() == 0) clearToolSelection();
                    return;
                }
                List<String> matched = new ArrayList<>();
                for (String n : names) {
                    if (n.toLowerCase().contains(query)) matched.add(n);
                }
                resultsAdapter.clear();
                if (!matched.isEmpty()) {
                    resultsAdapter.addAll(matched);
                    resultsAdapter.notifyDataSetChanged();
                    lvToolResults.setVisibility(View.VISIBLE);
                } else {
                    lvToolResults.setVisibility(View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 点击匹配项 → 选中工具
        lvToolResults.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            String name = (String) parent.getItemAtPosition(position);
            ToolMeta tool = ToolRegistry.findTool(name);
            if (tool != null) {
                onToolSelected(tool);
                etSearch.setText(name);
                lvToolResults.setVisibility(View.GONE);
            }
        });

        // 搜索框焦点丢失 → 隐藏列表
        etSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                lvToolResults.postDelayed(() -> lvToolResults.setVisibility(View.GONE), 200);
            }
        });
    }

    private void clearToolSelection() {
        currentTool = null;
        tvSignature.setText("");
        tvNoParams.setVisibility(View.VISIBLE);
        tvNoParams.setText("请选择工具");
        layoutForm.removeViews(1, Math.max(0, layoutForm.getChildCount() - 1));
        etJsonEditor.setText("");
        paramViews.clear();
        tvResult.setText("等待执行...");
        tvStatus.setText("就绪");
        lastResult = null;
    }

    // ================================================================
    //  工具选择后的处理
    // ================================================================

    private void onToolSelected(ToolMeta tool) {
        currentTool = tool;

        // 签名
        StringBuilder sig = new StringBuilder(tool.functionName + "(");
        for (int i = 0; i < tool.params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(tool.params[i].name);
        }
        sig.append(")");
        tvSignature.setText(sig.toString());

        // 生成参数模板
        JSONObject tmpl = ToolRegistry.buildParamTemplate(tool);

        // 构建表单
        layoutForm.removeViews(1, Math.max(0, layoutForm.getChildCount() - 1));
        paramViews.clear();

        if (tool.params.length == 0) {
            tvNoParams.setVisibility(View.VISIBLE);
            tvNoParams.setText("此工具无参数");
        } else {
            tvNoParams.setVisibility(View.GONE);
            for (ToolMeta.Param p : tool.params) {
                View row = createParamRow(p, tmpl.optString(p.name, ""));
                layoutForm.addView(row);
            }
        }

        // JSON 模式同步（含 _tool 字段）
        try {
            tmpl.put("_tool", tool.displayName);
        } catch (Exception ignored) {}
        etJsonEditor.setText(formatJson(tmpl));
        tvResult.setText("等待执行...");
        tvStatus.setText("就绪");
        lastResult = null;
    }

    // ================================================================
    //  动态表单生成
    // ================================================================

    private View createParamRow(ToolMeta.Param param, String defaultVal) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);

        // 标签
        TextView label = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        label.setLayoutParams(lp);
        label.setTextSize(13);
        String labelText = param.name;
        if (param.required) labelText += " *";
        label.setText(labelText);
        if (param.description != null && !param.description.isEmpty()) {
            label.setContentDescription(param.description);
        }
        row.addView(label);

        // 控件
        View control;
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f);

        switch (param.type) {
            case "boolean": {
                SwitchCompat sw = new SwitchCompat(this);
                sw.setLayoutParams(clp);
                sw.setChecked(Boolean.parseBoolean(defaultVal));
                control = sw;
                break;
            }
            case "enum": {
                Spinner spinner = new Spinner(this);
                spinner.setLayoutParams(clp);
                ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, param.enumValues);
                sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(sa);
                if (defaultVal != null && !defaultVal.isEmpty()) {
                    for (int i = 0; i < param.enumValues.length; i++) {
                        if (param.enumValues[i].equals(defaultVal)) {
                            spinner.setSelection(i);
                            break;
                        }
                    }
                }
                control = spinner;
                break;
            }
            default: {
                EditText et = new EditText(this);
                et.setLayoutParams(clp);
                et.setTextSize(13);
                et.setText(defaultVal);
                et.setHint(param.defaultValue != null ? param.defaultValue : "");
                et.setSingleLine(true);
                et.setPadding(8, 4, 8, 4);
                et.setBackground(ContextCompat.getDrawable(this, R.drawable.shape_edittext));
                if ("int".equals(param.type) || "long".equals(param.type)) {
                    et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                }
                control = et;
                break;
            }
        }
        paramViews.put(param.name, control);
        row.addView(control);

        // 长按 label 显示描述
        label.setOnLongClickListener(v -> {
            Toast.makeText(this, param.name + ": " + param.description, Toast.LENGTH_SHORT).show();
            return true;
        });

        return row;
    }

    // ================================================================
    //  表单 ↔ JSON 模式切换
    // ================================================================

    private void setupModeToggle() {
        switchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 切到 JSON 模式：表单 → JSON
                switchToJsonMode();
            } else {
                // 切到表单模式：JSON → 表单
                switchToFormMode();
            }
        });
    }

    private void switchToJsonMode() {
        isFormMode = false;
        layoutForm.setVisibility(View.GONE);
        etJsonEditor.setVisibility(View.VISIBLE);

        // 表单 → JSON，自动注入 _tool 字段
        if (currentTool != null) {
            JSONObject json = formToJson();
            try {
                json.put("_tool", currentTool.displayName);
            } catch (Exception ignored) {}
            etJsonEditor.setText(formatJson(json));
        }
    }

    private void switchToFormMode() {
        isFormMode = true;
        etJsonEditor.setVisibility(View.GONE);
        layoutForm.setVisibility(View.VISIBLE);

        // JSON → 表单（仅在解析成功时更新）
        String jsonText = etJsonEditor.getText().toString().trim();
        if (!jsonText.isEmpty()) {
            try {
                JSONObject parsed = new JSONObject(jsonText);

                // 如果 _tool 指向了不同工具，切换之
                if (parsed.has("_tool")) {
                    String toolName = parsed.optString("_tool", "").trim();
                    ToolMeta target = ToolRegistry.findTool(toolName);
                    if (target != null && (currentTool == null
                            || !target.displayName.equals(currentTool.displayName))) {
                        onToolSelected(target);
                        etSearch.setText(toolName);
                    }
                }

                parsed.remove("_tool");
                jsonToForm(parsed);
            } catch (Exception ignored) {
                // JSON 不合法时不破坏表单
            }
        }
    }

    /** 收集表单数据 → JSONObject。 */
    private JSONObject formToJson() {
        JSONObject json = new JSONObject();
        try {
            for (ToolMeta.Param p : currentTool.params) {
                View v = paramViews.get(p.name);
                if (v == null) continue;
                switch (p.type) {
                    case "boolean":
                        json.put(p.name, ((SwitchCompat) v).isChecked());
                        break;
                    case "int":
                        String iv = ((EditText) v).getText().toString().trim();
                        json.put(p.name, iv.isEmpty() ? 0 : Integer.parseInt(iv));
                        break;
                    case "long":
                        String lv = ((EditText) v).getText().toString().trim();
                        json.put(p.name, lv.isEmpty() ? 0 : Long.parseLong(lv));
                        break;
                    case "enum":
                        json.put(p.name, ((Spinner) v).getSelectedItem().toString());
                        break;
                    default:
                        String sv = ((EditText) v).getText().toString().trim();
                        json.put(p.name, sv);
                        break;
                }
            }
        } catch (Exception ignored) {}
        return json;
    }

    /** JSONObject → 填充表单。 */
    private void jsonToForm(JSONObject json) {
        try {
            for (ToolMeta.Param p : currentTool.params) {
                if (!json.has(p.name)) continue;
                View v = paramViews.get(p.name);
                if (v == null) continue;
                switch (p.type) {
                    case "boolean":
                        ((SwitchCompat) v).setChecked(json.optBoolean(p.name, false));
                        break;
                    case "int":
                    case "long":
                        ((EditText) v).setText(String.valueOf(json.optLong(p.name, 0)));
                        break;
                    case "enum": {
                        String val = json.optString(p.name, "");
                        Spinner spinner = (Spinner) v;
                        for (int i = 0; i < spinner.getCount(); i++) {
                            if (spinner.getItemAtPosition(i).toString().equals(val)) {
                                spinner.setSelection(i);
                                break;
                            }
                        }
                        break;
                    }
                    default:
                        ((EditText) v).setText(json.optString(p.name, ""));
                        break;
                }
            }
        } catch (Exception ignored) {}
    }

    // ================================================================
    //  执行
    // ================================================================

    private void setupButtons() {
        btnExecute.setOnClickListener(v -> executeTool());
        btnReset.setOnClickListener(v -> resetForm());
    }

    private void executeTool() {
        // 收集参数
        final JSONObject params;
        if (isFormMode) {
            if (currentTool == null) {
                Toast.makeText(this, "请先选择工具", Toast.LENGTH_SHORT).show();
                return;
            }
            params = formToJson();
        } else {
            String text = etJsonEditor.getText().toString().trim();
            try {
                params = new JSONObject(text);
            } catch (Exception e) {
                tvStatus.setText("✖ JSON 解析错误: " + e.getMessage());
                tvStatus.setTextColor(0xFFE53935);
                return;
            }

            // JSON 模式：从 _tool 字段解析目标工具
            if (!params.has("_tool")) {
                tvStatus.setText("✖ 缺少 _tool 字段，请指定要调用的工具名称");
                tvStatus.setTextColor(0xFFE53935);
                return;
            }
            String toolName = params.optString("_tool", "").trim();
            if (toolName.isEmpty()) {
                tvStatus.setText("✖ _tool 字段不能为空");
                tvStatus.setTextColor(0xFFE53935);
                return;
            }
            ToolMeta target = ToolRegistry.findTool(toolName);
            if (target == null) {
                tvStatus.setText("✖ 未知工具: " + toolName);
                tvStatus.setTextColor(0xFFE53935);
                return;
            }
            currentTool = target;
            params.remove("_tool");

            // 同步搜索框（不重建表单）
            etSearch.setText(toolName);
            lvToolResults.setVisibility(View.GONE);
        }

        // 校验必填
        for (ToolMeta.Param p : currentTool.params) {
            if (p.required) {
                String val = params.optString(p.name, "");
                if (val.isEmpty()) {
                    tvStatus.setText("✖ 必填参数 \"" + p.name + "\" 为空");
                    tvStatus.setTextColor(0xFFE53935);
                    return;
                }
            }
        }

        btnExecute.setEnabled(false);
        tvStatus.setText("⏳ 执行中...");
        tvStatus.setTextColor(0xFF666666);

        final long startTime = System.currentTimeMillis();
        new Thread(() -> {
            try {
                JSONObject result = currentTool.executor.execute(params);
                final long elapsed = System.currentTimeMillis() - startTime;
                final JSONObject finalResult = result;

                runOnUiThread(() -> {
                    lastResult = finalResult;
                    showResult(finalResult);
                    String status = finalResult.optString("status", "unknown");
                    if ("success".equals(status)) {
                        tvStatus.setText("✔ 执行成功 · 耗时 " + elapsed + "ms");
                        tvStatus.setTextColor(0xFF4CAF50);
                    } else {
                        tvStatus.setText("✖ 执行返回错误 · 耗时 " + elapsed + "ms");
                        tvStatus.setTextColor(0xFFE53935);
                    }
                    btnExecute.setEnabled(true);
                });
            } catch (Exception e) {
                final long elapsed = System.currentTimeMillis() - startTime;
                runOnUiThread(() -> {
                    tvStatus.setText("✖ 异常: " + e.getClass().getSimpleName()
                            + " · 耗时 " + elapsed + "ms");
                    tvStatus.setTextColor(0xFFE53935);
                    tvResult.setText(e.getMessage() + "\n\n" + getStackTraceString(e));
                    btnExecute.setEnabled(true);
                });
            }
        }).start();
    }

    private void resetForm() {
        if (currentTool == null) return;
        JSONObject tmpl = ToolRegistry.buildParamTemplate(currentTool);
        jsonToForm(tmpl);
        etJsonEditor.setText(formatJson(tmpl));
        tvResult.setText("等待执行...");
        tvStatus.setText("已重置");
        tvStatus.setTextColor(0xFF666666);
        lastResult = null;
    }

    // ================================================================
    //  结果展示
    // ================================================================

    private void setupViewToggle() {
        tvViewRaw.setOnClickListener(v -> {
            isPrettyView = false;
            tvViewRaw.setTextColor(0xFF1976D2);
            tvViewPretty.setTextColor(0xFF999999);
            if (lastResult != null) tvResult.setText(formatJson(lastResult));
        });
        tvViewPretty.setOnClickListener(v -> {
            isPrettyView = true;
            tvViewPretty.setTextColor(0xFF1976D2);
            tvViewRaw.setTextColor(0xFF999999);
            if (lastResult != null) tvResult.setText(prettyPrint(lastResult, 0));
        });
    }

    private void showResult(JSONObject result) {
        if (isPrettyView) {
            tvResult.setText(prettyPrint(result, 0));
            tvViewPretty.setTextColor(0xFF1976D2);
            tvViewRaw.setTextColor(0xFF999999);
        } else {
            tvResult.setText(formatJson(result));
            tvViewRaw.setTextColor(0xFF1976D2);
            tvViewPretty.setTextColor(0xFF999999);
        }
        svResult.post(() -> svResult.scrollTo(0, 0));
    }

    /** 格式化 JSON（缩进 2 空格）。 */
    private String formatJson(JSONObject obj) {
        try {
            return obj.toString(2);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /** 可阅读的键值对展平。 */
    private String prettyPrint(JSONObject obj, int depth) {
        StringBuilder sb = new StringBuilder();
        String indent = repeat("  ", depth);
        try {
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.get(key);
                sb.append(indent).append("• ").append(key).append(": ");
                if (val instanceof JSONObject) {
                    sb.append("\n");
                    sb.append(prettyPrint((JSONObject) val, depth + 1));
                } else if (val instanceof JSONArray) {
                    sb.append("[数组, ").append(((JSONArray) val).length()).append(" 项]\n");
                    for (int i = 0; i < Math.min(((JSONArray) val).length(), 5); i++) {
                        sb.append(indent).append("  [").append(i).append("]: ");
                        Object item = ((JSONArray) val).get(i);
                        if (item instanceof JSONObject) {
                            sb.append("\n");
                            sb.append(prettyPrint((JSONObject) item, depth + 2));
                        } else {
                            sb.append(item).append("\n");
                        }
                    }
                    if (((JSONArray) val).length() > 5) {
                        sb.append(indent).append("  ... 还有 ")
                                .append(((JSONArray) val).length() - 5).append(" 项\n");
                    }
                } else if (val instanceof String) {
                    String s = (String) val;
                    if (s.length() > 200) {
                        sb.append("\"").append(s.substring(0, 200)).append("...\"\n");
                    } else {
                        sb.append("\"").append(s).append("\"\n");
                    }
                } else {
                    sb.append(val).append("\n");
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private String getStackTraceString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("  at ").append(e.toString()).append("\n");
        }
        return sb.toString();
    }

    // ================================================================
    //  工具帮助（? 按钮）
    // ================================================================

    /** 展示所有已注册工具的说明列表。 */
    private void showToolHelpDialog() {
        StringBuilder sb = new StringBuilder();
        for (ToolMeta tool : ToolRegistry.getAllTools()) {
            sb.append("▸ ").append(tool.displayName).append("\n");
            sb.append("  ").append(tool.description).append("\n");
            if (tool.params.length > 0) {
                sb.append("  参数:\n");
                for (ToolMeta.Param p : tool.params) {
                    sb.append("    ").append(p.name);
                    if (p.required) sb.append(" *");
                    sb.append("  [").append(p.type).append("]");
                    if (p.defaultValue != null && !p.defaultValue.isEmpty()) {
                        sb.append(" (默认: ").append(p.defaultValue).append(")");
                    }
                    sb.append("\n");
                    if (p.description != null && !p.description.isEmpty()) {
                        sb.append("      ").append(p.description).append("\n");
                    }
                    if (p.enumValues != null && p.enumValues.length > 0) {
                        sb.append("      可选值: ");
                        for (int i = 0; i < p.enumValues.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(p.enumValues[i]);
                        }
                        sb.append("\n");
                    }
                }
            } else {
                sb.append("  无参数\n");
            }
            sb.append("\n");
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("工具参考手册")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    // ================================================================
    //  使用帮助（标题栏右上角）
    // ================================================================

    private void setupHelp() {
        findViewById(R.id.tv_help).setOnClickListener(v -> {
            String help =
                "🧰 工具测试中心 使用说明\n\n"
                + "1. 选择工具\n"
                + "   在顶部输入框搜索/选择要测试的工具\n\n"
                + "2. 填写参数\n"
                + "   表单模式：直接编辑各参数字段\n"
                + "   JSON 模式：切换到 JSON 编辑原始参数\n"
                + "   JSON 必须包含 _tool 字段指明工具名称\n"
                + "   两种模式可随时切换，数据自动同步\n\n"
                + "3. 执行\n"
                + "   点击 ▶ 执行，结果在下方显示\n"
                + "   可切换「原始 JSON」/「可阅读」视图\n\n"
                + "4. 提示\n"
                + "   - 必填参数标记 *\n"
                + "   - 长按参数名可查看说明\n"
                + "   - sessionId 默认为 default\n"
                + "   - 确保 Termux 环境已初始化";
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("帮助")
                    .setMessage(help)
                    .setPositiveButton("知道了", null)
                    .show();
        });
    }
}
