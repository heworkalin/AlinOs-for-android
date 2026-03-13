package alin.android.alinos;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

import alin.android.alinos.adapter.ConfigAdapter;
import alin.android.alinos.adapter.OnConfigOperationListener;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ConfigDBHelper;
import alin.android.alinos.net.OllamaApiClient;

public class AiConfigActivity extends AppCompatActivity implements OnConfigOperationListener {
    private RecyclerView rvConfigList;
    private FloatingActionButton fabAdd;
    private ConfigDBHelper mDbHelper;
    private List<ConfigBean> mConfigList;
    private ConfigAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        // 状态栏透明，布局延伸到状态栏
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        setContentView(R.layout.activity_ai_config);

        // 初始化控件
        rvConfigList = findViewById(R.id.rv_config_list);
        fabAdd = findViewById(R.id.fab_add);
        mDbHelper = new ConfigDBHelper(this);

        // 初始化配置列表
        initConfigList();

        // 加号按钮：添加配置
        fabAdd.setOnClickListener(v -> {
            showConfigDialog(new ConfigBean()); // 新增配置，id默认0
        });
    }

    // 初始化配置列表
    private void initConfigList() {
        mConfigList = mDbHelper.getAllConfigs();
        mAdapter = new ConfigAdapter(this, mConfigList, this);
        rvConfigList.setLayoutManager(new LinearLayoutManager(this));
        rvConfigList.setAdapter(mAdapter);
    }

    // 合并：添加/编辑配置弹窗（包含模型输入+Ollama下载逻辑）
    private void showConfigDialog(ConfigBean config) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_config, null);
        RadioGroup rgType = dialogView.findViewById(R.id.rg_type);
        RadioButton rbOpenai = dialogView.findViewById(R.id.rb_openai);
        RadioButton rbOllama = dialogView.findViewById(R.id.rb_ollama);
        EditText etServer = dialogView.findViewById(R.id.et_server);
        EditText etKey = dialogView.findViewById(R.id.et_key);
        EditText etModel = dialogView.findViewById(R.id.et_model);
        Button btnDownloadModel = dialogView.findViewById(R.id.btn_download_model);
        TextView tvKeyTitle = dialogView.findViewById(R.id.tv_key_label);

        boolean isEdit = config.getId() != 0;

        // 类型切换逻辑（保持不变）
        rgType.setOnCheckedChangeListener((group, checkedId) -> {
            tvKeyTitle.setVisibility(checkedId == R.id.rb_ollama ? View.GONE : View.VISIBLE);
            etKey.setVisibility(checkedId == R.id.rb_ollama ? View.GONE : View.VISIBLE);
            btnDownloadModel.setVisibility(checkedId == R.id.rb_ollama ? View.VISIBLE : View.GONE);
            if (checkedId == R.id.rb_openai) {
                etModel.setHint("如gpt-3.5-turbo/gpt-4");
                etServer.setHint("OpenAI服务器地址（如https://api.openai.com）");
            } else {
                etModel.setHint("如llama3/mistral/phi3");
                etServer.setHint("Ollama服务器地址（如http://localhost:11434）");
            }
        });

        // 编辑模式回显数据（保持不变）
        if (isEdit) {
            if (config.getType().equals("OpenAI")) {
                rbOpenai.setChecked(true);
                tvKeyTitle.setVisibility(View.VISIBLE);
                etKey.setVisibility(View.VISIBLE);
                btnDownloadModel.setVisibility(View.GONE);
            } else {
                rbOllama.setChecked(true);
                tvKeyTitle.setVisibility(View.GONE);
                etKey.setVisibility(View.GONE);
                btnDownloadModel.setVisibility(View.VISIBLE);
            }
            etServer.setText(config.getServerUrl());
            etKey.setText(config.getApiKey());
            etModel.setText(config.getModel());
        } else {
            rbOpenai.setChecked(true);
            tvKeyTitle.setVisibility(View.VISIBLE);
            etModel.setHint("gpt-3.5-turbo");
            btnDownloadModel.setVisibility(View.GONE);
        }

        // Ollama下载按钮点击事件（保持不变）
        btnDownloadModel.setOnClickListener(v -> {
            String serverUrl = etServer.getText().toString().trim();
            String modelName = etModel.getText().toString().trim();
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请先填写Ollama服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            if (modelName.isEmpty()) {
                Toast.makeText(this, "请填写要下载的模型名称（如llama3）", Toast.LENGTH_SHORT).show();
                return;
            }
            OllamaApiClient.downloadModel(serverUrl, modelName, new OllamaApiClient.OnDownloadListener() {
                @Override
                public void onSuccess() {
                    Toast.makeText(AiConfigActivity.this, "模型" + modelName + "下载请求已发送", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(AiConfigActivity.this, "模型下载失败：" + error, Toast.LENGTH_SHORT).show();
                }
            });
        });

        // -------------------- 修改重点：构建并显示对话框，自定义确定按钮点击 --------------------
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "编辑AI服务配置" : "添加AI服务配置")
                .setView(dialogView)
                .setNegativeButton("取消", null);  // 取消按钮使用默认行为（关闭）

        // 先设置确定按钮文本，监听器留空（后续覆盖）
        builder.setPositiveButton("保存", null);
        AlertDialog dialog = builder.create();

        // 对话框显示后，获取确定按钮并设置自定义点击监听
        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                // 获取输入内容
                String type = rgType.getCheckedRadioButtonId() == R.id.rb_openai ? "OpenAI" : "Ollama";
                String serverUrl = etServer.getText().toString().trim();
                String apiKey = etKey.getText().toString().trim();
                String model = etModel.getText().toString().trim();

                // 输入校验（失败时只弹Toast，不关闭对话框）
                if (model.isEmpty()) {
                    Toast.makeText(AiConfigActivity.this, "模型名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (serverUrl.isEmpty()) {
                    Toast.makeText(AiConfigActivity.this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                if (type.equals("OpenAI") && apiKey.isEmpty()) {
                    Toast.makeText(AiConfigActivity.this, "OpenAI密钥不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 校验通过，执行保存操作
                if (isEdit) {
                    config.setType(type);
                    config.setServerUrl(serverUrl);
                    config.setApiKey(apiKey);
                    config.setModel(model);
                    mDbHelper.updateConfig(config);
                    Toast.makeText(AiConfigActivity.this, "配置修改成功", Toast.LENGTH_SHORT).show();
                } else {
                    boolean isDefault = mConfigList.isEmpty();
                    ConfigBean newConfig = new ConfigBean(type, serverUrl, apiKey, model, isDefault);
                    mDbHelper.addConfig(newConfig);
                    Toast.makeText(AiConfigActivity.this, "配置添加成功", Toast.LENGTH_SHORT).show();
                }

                // 刷新列表
                refreshConfigList();

                // 保存成功后关闭对话框
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    // 刷新配置列表
    private void refreshConfigList() {
        mConfigList = mDbHelper.getAllConfigs();
        mAdapter = new ConfigAdapter(this, mConfigList, this);
        rvConfigList.setAdapter(mAdapter);
    }

    // -------------------- 实现 OnConfigOperationListener 接口 --------------------
    @Override
    public void onEdit(ConfigBean config) {
        showConfigDialog(config);
    }

    @Override
    public void onSetDefault(ConfigBean newDefaultConfig) {
        // 1. 写入数据库：设置新默认配置
        mDbHelper.setDefaultConfig(newDefaultConfig.getId());
        // 2. 从数据库重新拉取最新的配置列表（关键：同步默认状态）
        List<ConfigBean> latestConfigList = mDbHelper.getAllConfigs();
        // 3. 通知Adapter刷新（传入最新数据）
        mAdapter.refreshData(latestConfigList);
        // 4. 更新Activity的内存列表（避免后续操作使用旧数据）
        mConfigList = latestConfigList;
        // 5. 提示用户
        Toast.makeText(this, "已将" + newDefaultConfig.getType() + "设为默认", Toast.LENGTH_SHORT).show();
    }
    @Override
    public void onDelete(ConfigBean config, int position) {
        mDbHelper.deleteConfig(config.getId());
        mConfigList.remove(position);
        mAdapter.notifyItemRemoved(position);
        Toast.makeText(this, "已删除配置", Toast.LENGTH_SHORT).show();
    }

    public static class ActivityStateManager {
        private static ActivityStateManager instance;
        private boolean isChatActivityVisible = false;

        private ActivityStateManager() {}

        public static synchronized ActivityStateManager getInstance() {
            if (instance == null) {
                instance = new ActivityStateManager();
            }
            return instance;
        }

        public void setChatActivityVisible(boolean visible) {
            this.isChatActivityVisible = visible;
        }

        public boolean isChatActivityVisible() {
            return isChatActivityVisible;
        }
    }
}