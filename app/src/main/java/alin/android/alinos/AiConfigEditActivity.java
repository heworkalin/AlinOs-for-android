package alin.android.alinos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import alin.android.alinos.ai.ModelInfo;
import alin.android.alinos.ai.ModelRefresher;
import alin.android.alinos.ai.ModelRegistry;
import alin.android.alinos.ai.ProviderInfo;
import alin.android.alinos.ai.dialect.DialectRegistry;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ConfigDBHelper;

/**
 * AI 配置编辑页（替代原来的弹窗）。
 *
 * <p>结构：
 * <ul>
 *   <li>基础配置：Provider / 协议 / 模型 / 服务器地址 / API Key；</li>
 *   <li>高级参数（默认收起）：上下文窗口（默认 128K）、最大回复 tokens、输入上限、temperature、top_p；</li>
 *   <li>自定义参数：可动态增删的 key-value 列表，保存进 {@code extra_json}。</li>
 * </ul>
 */
public class AiConfigEditActivity extends AppCompatActivity {

    public static final String EXTRA_CONFIG_ID = "config_id";

    private static final String[] API_TYPES = {
            "auto", "openai-completions", "openai-responses", "anthropic-messages"
    };
    private static final String[] API_TYPE_LABELS = {
            "自动（按 Provider 推断）",
            "OpenAI Chat Completions",
            "OpenAI Responses",
            "Anthropic Messages",
    };

    private Spinner spProvider;
    private Spinner spApiType;
    private EditText etModel;
    private EditText etServerUrl;
    private EditText etApiKey;
    private TextView tvModelPrice;
    private TextView tvAdvancedToggle;
    private LinearLayout llAdvanced;
    private EditText etContextWindow;
    private EditText etMaxResponseTokens;
    private EditText etUserInputLimit;
    private EditText etTemperature;
    private EditText etTopP;
    private LinearLayout llCustomParams;
    private Button btnAddParam;

    private ConfigDBHelper dbHelper;
    private ConfigBean config;
    private List<ProviderInfo> providers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_config_edit);

        dbHelper = new ConfigDBHelper(this);
        int configId = getIntent().getIntExtra(EXTRA_CONFIG_ID, 0);
        if (configId > 0) {
            config = dbHelper.getConfigById(configId);
        }
        if (config == null) config = new ConfigBean();

        bindViews();
        setupProviders();
        setupApiTypes();
        fillForm();
        setupActions();
    }

    private void bindViews() {
        spProvider = findViewById(R.id.sp_provider);
        spApiType = findViewById(R.id.sp_api_type);
        etModel = findViewById(R.id.et_model);
        etServerUrl = findViewById(R.id.et_server_url);
        etApiKey = findViewById(R.id.et_api_key);
        tvModelPrice = findViewById(R.id.tv_model_price);
        tvAdvancedToggle = findViewById(R.id.tv_advanced_toggle);
        llAdvanced = findViewById(R.id.ll_advanced);
        etContextWindow = findViewById(R.id.et_context_window);
        etMaxResponseTokens = findViewById(R.id.et_max_response_tokens);
        etUserInputLimit = findViewById(R.id.et_user_input_limit);
        etTemperature = findViewById(R.id.et_temperature);
        etTopP = findViewById(R.id.et_top_p);
        llCustomParams = findViewById(R.id.ll_custom_params);
        btnAddParam = findViewById(R.id.btn_add_param);
    }

    // ---------------------------------------------------------------------
    // 初始化
    // ---------------------------------------------------------------------

    private void setupProviders() {
        ModelRegistry registry = ModelRegistry.get(this);
        providers = registry.providers();

        List<String> names = new ArrayList<>();
        for (ProviderInfo p : providers) names.add(p.id);
        names.add("custom（自定义）");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProvider.setAdapter(adapter);
        spProvider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onProviderChanged(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void setupApiTypes() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, Arrays.asList(API_TYPE_LABELS));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spApiType.setAdapter(adapter);
    }

    private void fillForm() {
        // Provider
        String pid = config.getProviderId();
        if (pid != null && !pid.isEmpty()) {
            for (int i = 0; i < providers.size(); i++) {
                if (providers.get(i).id.equals(pid)) {
                    spProvider.setSelection(i);
                    break;
                }
            }
        }
        // 协议
        String api = config.getApiType();
        for (int i = 0; i < API_TYPES.length; i++) {
            if (API_TYPES[i].equals(api)) {
                spApiType.setSelection(i);
                break;
            }
        }
        etModel.setText(config.getModel());
        etServerUrl.setText(config.getServerUrl());
        etApiKey.setText(config.getApiKey());

        etContextWindow.setText(String.valueOf(config.getModelContextWindow()));
        etMaxResponseTokens.setText(String.valueOf(config.getMaxResponseTokens()));
        etUserInputLimit.setText(String.valueOf(config.getUserInputCharLimit()));

        // 高级自定义参数（temperature / top_p 从 extraJson 里取）
        List<String[]> pairs = config.getExtraPairs();
        for (String[] p : pairs) {
            if ("temperature".equals(p[0])) etTemperature.setText(p[1]);
            if ("top_p".equals(p[0])) etTopP.setText(p[1]);
        }

        for (String[] p : pairs) {
            if ("temperature".equals(p[0]) || "top_p".equals(p[0])) continue;
            addCustomParamRow(p[0], p[1]);
        }

        updateModelPrice();
    }

    private void setupActions() {
        tvAdvancedToggle.setOnClickListener(v -> {
            boolean show = llAdvanced.getVisibility() != View.VISIBLE;
            llAdvanced.setVisibility(show ? View.VISIBLE : View.GONE);
            tvAdvancedToggle.setText(show ? "高级参数 ▾" : "高级参数 ▸");
        });

        btnAddParam.setOnClickListener(v -> addCustomParamRow("", ""));

        findViewById(R.id.btn_pick_model).setOnClickListener(v -> showModelPicker());
        findViewById(R.id.btn_refresh_models).setOnClickListener(v -> refreshModels());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        etModel.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) updateModelPrice();
        });
    }

    // ---------------------------------------------------------------------
    // Provider / 模型
    // ---------------------------------------------------------------------

    private void onProviderChanged(int position) {
        if (position < 0 || position >= providers.size()) return;
        ProviderInfo p = providers.get(position);
        String base = p.defaultBaseUrl();
        if (!base.isEmpty() && TextUtils.isEmpty(etServerUrl.getText())) {
            etServerUrl.setText(base);
        }
        // 协议默认跟随 provider
        String api = p.defaultApi();
        for (int i = 0; i < API_TYPES.length; i++) {
            if (API_TYPES[i].equals(api)) {
                spApiType.setSelection(i);
                break;
            }
        }
        updateModelPrice();
    }

    private String currentProviderId() {
        int pos = spProvider.getSelectedItemPosition();
        if (pos < 0 || pos >= providers.size()) return "custom";
        return providers.get(pos).id;
    }

    private String currentApiType() {
        int pos = spApiType.getSelectedItemPosition();
        return (pos < 0 || pos >= API_TYPES.length) ? "auto" : API_TYPES[pos];
    }

    private void updateModelPrice() {
        String modelId = etModel.getText().toString().trim();
        if (modelId.isEmpty()) {
            tvModelPrice.setText("");
            return;
        }
        ModelInfo m = ModelRegistry.get(this).model(currentProviderId(), modelId);
        if (m == null) {
            m = ModelRegistry.get(this).modelById(modelId);
        }
        if (m == null || m.cost == null) {
            tvModelPrice.setText("未收录该模型，费用按服务端实际计费");
            return;
        }
        tvModelPrice.setText(String.format(java.util.Locale.US,
                "价格 $%.4f / $%.4f 每百万 token（输入/输出）· 上下文 %d · 最大输出 %d",
                m.cost.optDouble("input", 0),
                m.cost.optDouble("output", 0),
                m.contextWindow, m.maxTokens));
    }

    /** 从注册表挑选模型（含定价显示）。 */
    private void showModelPicker() {
        ModelRegistry registry = ModelRegistry.get(this);
        String pid = currentProviderId();
        List<ModelInfo> models = registry.models(pid);
        if (models.isEmpty()) {
            models = registry.models();
        }
        if (models.isEmpty()) {
            toast("模型注册表为空");
            return;
        }
        final List<ModelInfo> list = models;
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ModelInfo m = list.get(i);
            String price = "";
            if (m.cost != null) {
                price = String.format(java.util.Locale.US, "  ($%.3f/$%.3f)",
                        m.cost.optDouble("input", 0), m.cost.optDouble("output", 0));
            }
            items[i] = m.id + price;
        }
        new AlertDialog.Builder(this)
                .setTitle(pid + " 的模型（" + list.size() + "）")
                .setItems(items, (d, which) -> {
                    ModelInfo m = list.get(which);
                    etModel.setText(m.id);
                    if (m.contextWindow > 0) {
                        etContextWindow.setText(String.valueOf(m.contextWindow));
                    }
                    if (m.maxTokens > 0) {
                        etMaxResponseTokens.setText(String.valueOf(m.maxTokens));
                    }
                    if (m.baseUrl != null && !m.baseUrl.isEmpty()
                            && TextUtils.isEmpty(etServerUrl.getText())) {
                        etServerUrl.setText(m.baseUrl);
                    }
                    for (int i = 0; i < API_TYPES.length; i++) {
                        if (API_TYPES[i].equals(m.api)) {
                            spApiType.setSelection(i);
                            break;
                        }
                    }
                    updateModelPrice();
                })
                .show();
    }

    /** 轮询服务端获取当前可用模型。 */
    private void refreshModels() {
        final String pid = currentProviderId();
        final String baseUrl = etServerUrl.getText().toString().trim();
        final String apiType = currentApiType();
        final String apiKey = etApiKey.getText().toString().trim();
        toast("正在刷新 " + pid + " 的模型列表…");
        new Thread(() -> {
            ModelRefresher.Result r = ModelRefresher.refresh(this, pid, baseUrl, apiType, apiKey);
            runOnUiThread(() -> {
                if (r.ok()) {
                    toast("刷新成功：发现 " + r.count + " 个模型");
                } else {
                    toast("刷新失败：" + r.error);
                }
            });
        }).start();
    }

    // ---------------------------------------------------------------------
    // 自定义参数
    // ---------------------------------------------------------------------

    private void addCustomParamRow(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 0);

        EditText etKey = new EditText(this);
        etKey.setHint("参数名");
        etKey.setText(key);
        etKey.setTextSize(13);
        LinearLayout.LayoutParams lpKey =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etKey.setLayoutParams(lpKey);

        EditText etValue = new EditText(this);
        etValue.setHint("值");
        etValue.setText(value);
        etValue.setTextSize(13);
        LinearLayout.LayoutParams lpVal =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etValue.setLayoutParams(lpVal);

        Button btnDel = new Button(this);
        btnDel.setText("✕");
        btnDel.setTextSize(12);
        btnDel.setOnClickListener(v -> llCustomParams.removeView(row));

        row.addView(etKey);
        row.addView(etValue);
        row.addView(btnDel);
        llCustomParams.addView(row);
    }

    private List<String[]> collectCustomParams() {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < llCustomParams.getChildCount(); i++) {
            View child = llCustomParams.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            if (row.getChildCount() < 2) continue;
            EditText etKey = (EditText) row.getChildAt(0);
            EditText etValue = (EditText) row.getChildAt(1);
            String k = etKey.getText().toString().trim();
            if (k.isEmpty()) continue;
            out.add(new String[]{k, etValue.getText().toString()});
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // 保存
    // ---------------------------------------------------------------------

    private void save() {
        String model = etModel.getText().toString().trim();
        if (model.isEmpty()) {
            toast("请填写模型 id");
            return;
        }

        config.setProviderId(currentProviderId());
        config.setApiType(currentApiType());
        config.setModel(model);
        config.setServerUrl(etServerUrl.getText().toString().trim());
        config.setApiKey(etApiKey.getText().toString().trim());
        config.setModelContextWindow(intOr(etContextWindow, 131072));
        config.setMaxResponseTokens(intOr(etMaxResponseTokens, 8192));
        config.setUserInputCharLimit(intOr(etUserInputLimit, 200000));

        // temperature / top_p 收进自定义参数
        List<String[]> pairs = collectCustomParams();
        String temp = etTemperature.getText().toString().trim();
        String topP = etTopP.getText().toString().trim();
        if (!temp.isEmpty()) pairs.add(new String[]{"temperature", temp});
        if (!topP.isEmpty()) pairs.add(new String[]{"top_p", topP});
        config.setExtraPairs(pairs);

        // 旧字段兼容：type 用 provider 的首字母大写
        if (TextUtils.isEmpty(config.getType())) {
            config.setType(currentProviderId());
        }

        if (config.getId() > 0) {
            dbHelper.updateConfig(config);
        } else {
            dbHelper.addConfig(config);
        }
        toast("已保存");
        setResult(Activity.RESULT_OK, new Intent());
        finish();
    }

    private static int intOr(EditText et, int def) {
        try {
            String s = et.getText().toString().trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
