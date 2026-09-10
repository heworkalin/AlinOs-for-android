package alin.android.alinos.dev;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import alin.android.alinos.R;
import alin.android.alinos.adapter.SshConfigAdapter;
import alin.android.alinos.bean.SshConfigBean;
import alin.android.alinos.db.SshDbHelper;
import alin.android.alinos.localshell.LocalShellExecutor;

/**
 * SSH 配置管理界面。
 * 列表展示已有配置，FAB 弹出对话框添加新配置。
 * 本地模式自动探测端口 + 应用 UID。
 */
public class SshTestActivity extends AppCompatActivity implements SshConfigAdapter.OnSshConfigListener {

    private static final String PKG_TERMUX = "com.termux";
    private static final String URL_OFFICIAL = "https://github.com/termux/termux-app/releases";
    private static final String URL_ZEROTERMUX_APK = "https://d.icdown.club/d/repository/main/ZeroTermux/ZeroTermux-0.118.1.45.apk";
    private static final String TAG = "SshTestActivity";
    private static final String URL_ZEROTERMUX_PROJECT = "https://github.com/hanxinhao000/ZeroTermux";
    private static final String CODE_MIRROR = "sed -i 's@^\\(deb.*stable main\\)$@#\\1\\ndeb https://mirrors.tuna.tsinghua.edu.cn/termux/termux-packages-24 stable main@' $PREFIX/etc/apt/sources.list && yes | apt update && yes | apt upgrade\n";
    private static final String CODE_INSTALL_SSH_PREFIX = "pkg install openssh termux-auth termux-services -y && source $PREFIX/etc/profile.d/start-services.sh && sv-enable sshd && ";

    private RecyclerView rvSshList;
    private FloatingActionButton fabAdd;
    private SshDbHelper mDbHelper;
    private List<SshConfigBean> mConfigList;
    private SshConfigAdapter mAdapter;

    // 本地探测结果缓存
    private int mTermuxUid = -1;
    private boolean mPort8022Open = false;

    // 连接进度
    private android.app.ProgressDialog mConnectDialog;

    /** 验证流程取消标志：Activity 离开/销毁时置位，防止后台线程完成后重复拉起终端界面 */
    private volatile boolean mVerifyCancelled = false;

    @Override
    protected void onStop() {
        super.onStop();
        // 界面不可见：取消尚未完成的验证流程，避免“已关闭的界面又被拉起”
        if (isFinishing()) {
            mVerifyCancelled = true;
        }
    }

    @Override
    protected void onDestroy() {
        mVerifyCancelled = true;
        cleanupAllTempKeys();
        dismissConnectingDialog();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_test);



        mDbHelper = new SshDbHelper(this);
        // 清理上次遗留的临时密钥文件（崩溃/被杀时可能残留）
        cleanupAllTempKeys();
        rvSshList = findViewById(R.id.rv_ssh_list);
        fabAdd = findViewById(R.id.fab_add);

        initList();
        probeLocalEnv();

        fabAdd.setOnClickListener(v -> showAddDialog());
        LocalShellExecutor.provideContext(this);
    }

    private void initList() {
        mConfigList = mDbHelper.getAllConfigs();
        mAdapter = new SshConfigAdapter(mConfigList, this);
        rvSshList.setLayoutManager(new LinearLayoutManager(this));
        rvSshList.setAdapter(mAdapter);

        // 自动创建默认 termux 配置：如果数据库中不存在 local_termux，则自动创建
        scheduleAutoCreateTermux();
    }

    private void refreshList() {
        mConfigList = mDbHelper.getAllConfigs();
        mAdapter.refreshData(mConfigList);
    }

    /**
     * 延迟检查 termux 配置。
     * 数据库中已存在 local_termux → 直接使用（刷新列表）
     * 不存在且环境探测完成 → 自动创建一条默认配置
     */
    private void scheduleAutoCreateTermux() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SshConfigBean existing = mDbHelper.getLocalTermuxConfig();
            if (existing == null && mTermuxUid >= 0) {
                // 自动创建默认本地配置
                SshConfigBean defaultConfig = new SshConfigBean(
                        null, "127.0.0.1", 8022, String.valueOf(mTermuxUid),
                        "", "password", null, "本地 Termux 自动创建", "local_termux");
                mDbHelper.addConfig(defaultConfig);
            }
            refreshList(); // 无论是否创建，刷新列表确保显示最新
        }, 500); // 延迟 500ms，确保环境探测完成
    }

    // ================================================================
    //  本地环境探测（后台执行一次，结果供对话框使用）
    // ================================================================

    private void probeLocalEnv() {
        new Thread(() -> {
            mPort8022Open = checkPort("127.0.0.1", 8022, 2000);
            mTermuxUid = getPackageUid(PKG_TERMUX);
        }).start();
    }

    // ================================================================
    //  添加对话框
    // ================================================================

    private void showAddDialog() {
        showSshDialog(new SshConfigBean(), false);
    }

    // ================================================================
    //  添加/编辑对话框
    // ================================================================

    private void showSshDialog(SshConfigBean config, boolean isEdit) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_ssh, null);

        // 别称
        EditText etName = dialogView.findViewById(R.id.et_name);
        // 描述
        EditText etDescription = dialogView.findViewById(R.id.et_description);
        // 顶层 tab
        TextView tabRemote = dialogView.findViewById(R.id.tab_remote); // 本地termux
        TextView tabLocal = dialogView.findViewById(R.id.tab_local);   // 远程
        // 公共字段
        TextView tvLabelHost = dialogView.findViewById(R.id.tv_label_host);
        EditText etIp = dialogView.findViewById(R.id.et_ip);
        EditText etPort = dialogView.findViewById(R.id.et_port);
        EditText etUsername = dialogView.findViewById(R.id.et_username);
        TextView tvUsernameDisplay = dialogView.findViewById(R.id.tv_username_display);
        TextView tvIpExtra = dialogView.findViewById(R.id.tv_ip_extra);
        TextView tvPortExtra = dialogView.findViewById(R.id.tv_port_extra);
        TextView tvUsernameExtra = dialogView.findViewById(R.id.tv_username_extra);
        // 远程认证
        View layoutAuth = dialogView.findViewById(R.id.layout_auth);
        TextView tabAuthPassword = dialogView.findViewById(R.id.tab_auth_password);
        TextView tabAuthKey = dialogView.findViewById(R.id.tab_auth_key);
        EditText etPassword = dialogView.findViewById(R.id.et_password);
        View layoutPasswordInput = dialogView.findViewById(R.id.layout_password_input);
        EditText etKey = dialogView.findViewById(R.id.et_key);
        View layoutKeyRow = dialogView.findViewById(R.id.layout_key_row);
        CheckBox cbKeyEncrypted = dialogView.findViewById(R.id.cb_key_encrypted);
        EditText etKeyPassphrase = dialogView.findViewById(R.id.et_key_passphrase);
        View layoutKeyPassphrase = dialogView.findViewById(R.id.layout_key_passphrase);
        ImageView ivKeyPassphraseToggle = dialogView.findViewById(R.id.iv_key_passphrase_toggle);
        TextView tvPickKeyFile = dialogView.findViewById(R.id.tv_pick_key_file);
        ImageView ivPasswordToggle = dialogView.findViewById(R.id.iv_password_toggle);
        // 本地密码 + 状态 + 帮助
        View layoutLocalPassword = dialogView.findViewById(R.id.layout_local_password);
        EditText etLocalPassword = dialogView.findViewById(R.id.et_local_password);
        ImageView ivLocalPasswordToggle = dialogView.findViewById(R.id.iv_local_password_toggle);
        TextView tvLocalStatus = dialogView.findViewById(R.id.tv_local_status);
        TextView tvLocalHelp = dialogView.findViewById(R.id.tv_local_help);

        // 状态
        final boolean[] isRemoteMode = {true};  // 默认远程
        final boolean[] isPasswordAuth = {true};

        // ---- 密码可见性切换 ----
        ivPasswordToggle.setOnClickListener(v -> {
            int current = etPassword.getInputType();
            boolean isPassword = (current & android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
            if (isPassword) {
                etPassword.setInputType(current & ~android.text.InputType.TYPE_MASK_VARIATION | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                etPassword.setInputType(current & ~android.text.InputType.TYPE_MASK_VARIATION | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            etPassword.setSelection(etPassword.getText().length());
        });

        // ---- 私钥密码可见性切换 ----
        ivKeyPassphraseToggle.setOnClickListener(v -> {
            int current = etKeyPassphrase.getInputType();
            boolean isPassword = (current & android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
            if (isPassword) {
                etKeyPassphrase.setInputType(current & ~android.text.InputType.TYPE_MASK_VARIATION | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                etKeyPassphrase.setInputType(current & ~android.text.InputType.TYPE_MASK_VARIATION | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            etKeyPassphrase.setSelection(etKeyPassphrase.getText().length());
        });

        // ---- 私钥是否已加密（可选框控制密码框显隐） ----
        cbKeyEncrypted.setOnCheckedChangeListener((b, checked) ->
                layoutKeyPassphrase.setVisibility(checked ? View.VISIBLE : View.GONE));

        // ---- 选择密钥文件（功能待接入，先占位） ----
        tvPickKeyFile.setOnClickListener(v ->
                Toast.makeText(this, "密钥文件选择功能开发中，当前请直接粘贴私钥内容", Toast.LENGTH_SHORT).show());

        ivLocalPasswordToggle.setOnClickListener(v -> {
            int current = etLocalPassword.getInputType();
            boolean isPassword = (current & android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
            if (isPassword) {
                etLocalPassword.setInputType(current & ~android.text.InputType.TYPE_MASK_VARIATION | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                etLocalPassword.setInputType(current & ~android.text.InputType.TYPE_MASK_VARIATION | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            etLocalPassword.setSelection(etLocalPassword.getText().length());
        });

        // ---- 顶层 tab 切换 ----
        tabRemote.setOnClickListener(v -> {
            if (!isRemoteMode[0]) return;
            isRemoteMode[0] = false;
            tabRemote.setBackgroundResource(R.drawable.bg_ssh_tab_active);
            tabRemote.setTextColor(0xFFFFFFFF);
            tabLocal.setBackground(null);
            tabLocal.setTextColor(0xFF909399);

            layoutAuth.setVisibility(View.GONE);
            layoutLocalPassword.setVisibility(View.VISIBLE);

            tvLabelHost.setText("IP 地址");
            tvIpExtra.setText("");
            tvPortExtra.setText("");
            tvUsernameExtra.setText("");
            etIp.setText("127.0.0.1");
            etPort.setText("8022");
            etIp.setEnabled(false);
            etPort.setEnabled(false);
            etIp.setHint("127.0.0.1");
            etPort.setHint("8022");
            // 用户名: 显示纯文本
            applyUsernameMode(false, etUsername, tvUsernameDisplay);

            refreshLocalStatus(tvUsernameDisplay, tvLocalStatus, tvLocalHelp);
        });

        tabLocal.setOnClickListener(v -> {
            if (isRemoteMode[0]) return;
            isRemoteMode[0] = true;
            tabLocal.setBackgroundResource(R.drawable.bg_ssh_tab_active);
            tabLocal.setTextColor(0xFFFFFFFF);
            tabRemote.setBackground(null);
            tabRemote.setTextColor(0xFF909399);

            layoutAuth.setVisibility(View.VISIBLE);
            layoutLocalPassword.setVisibility(View.GONE);
            tvLocalStatus.setVisibility(View.GONE);
            tvLocalHelp.setVisibility(View.GONE);

            tvLabelHost.setText("主机名/IP");
            tvIpExtra.setText("");
            tvPortExtra.setText("");
            tvUsernameExtra.setText("");
            etIp.setEnabled(true);
            etPort.setEnabled(true);
            etIp.setHint("example.com");
            // 用户名: 恢复输入框
            applyUsernameMode(true, etUsername, tvUsernameDisplay);
            etPort.setHint("22");
            etUsername.setHint("root");
            if (!isEdit) {
                etIp.setText("");
                etPort.setText("22");
                etUsername.setText("root");
                etPassword.setText("");
                etKey.setText("");
            }

            applyAuthMode(tabAuthPassword, tabAuthKey, layoutPasswordInput, layoutKeyRow, cbKeyEncrypted, etKey, isPasswordAuth[0]);
        });

        // ---- 认证子 tab 切换 ----
        tabAuthPassword.setOnClickListener(v -> {
            isPasswordAuth[0] = true;
            applyAuthMode(tabAuthPassword, tabAuthKey, layoutPasswordInput, layoutKeyRow, cbKeyEncrypted, etKey, true);
        });
        tabAuthKey.setOnClickListener(v -> {
            isPasswordAuth[0] = false;
            applyAuthMode(tabAuthPassword, tabAuthKey, layoutPasswordInput, layoutKeyRow, cbKeyEncrypted, etKey, false);
        });

        // ---- 本地帮助弹窗 ----
        tvLocalHelp.setOnClickListener(v -> showInstallHelpDialog(
                etLocalPassword.getText().toString().trim()));

        // ---- 编辑回显 ----
        if (isEdit) {
            etName.setText(config.getName() != null ? config.getName() : "");
            etDescription.setText(config.getDescription() != null ? config.getDescription() : "");
            etIp.setText(config.getHost());
            etPort.setText(String.valueOf(config.getPort()));
            etUsername.setText(config.getUsername());
            tvUsernameDisplay.setText(config.getUsername());

            // 根据 configType 判断远程/本地模式
            boolean isRemote = "remote".equals(config.getConfigType());
            if (isRemote) {
                isRemoteMode[0] = true;
                // 确保远程布局可见
                layoutAuth.setVisibility(View.VISIBLE);
                layoutLocalPassword.setVisibility(View.GONE);
                if ("key".equals(config.getAuthType())) {
                    isPasswordAuth[0] = false;
                    etKey.setText(config.getKeyContent() != null ? config.getKeyContent() : "");
                    boolean hasPass = config.getKeyPassphrase() != null
                            && !config.getKeyPassphrase().isEmpty();
                    cbKeyEncrypted.setChecked(hasPass);
                    layoutKeyPassphrase.setVisibility(hasPass ? View.VISIBLE : View.GONE);
                    etKeyPassphrase.setText(hasPass ? config.getKeyPassphrase() : "");
                    applyAuthMode(tabAuthPassword, tabAuthKey, layoutPasswordInput, layoutKeyRow, cbKeyEncrypted, etKey, false);
                } else {
                    etPassword.setText(config.getPassword() != null ? config.getPassword() : "");
                    applyAuthMode(tabAuthPassword, tabAuthKey, layoutPasswordInput, layoutKeyRow, cbKeyEncrypted, etKey, true);
                }
            } else {
                // local_termux 模式
                isRemoteMode[0] = false;
                isPasswordAuth[0] = true;
                tabRemote.performClick();
                layoutAuth.setVisibility(View.GONE);
                layoutLocalPassword.setVisibility(View.VISIBLE);
                etLocalPassword.setText(config.getPassword() != null ? config.getPassword() : "");
            }
        }

        // ---- 应用当前模式的初始 visibility ----
        applyUsernameMode(isRemoteMode[0], etUsername, tvUsernameDisplay);
        // 默认远程 tab 高亮 + 同步布局可见性（新建时必须执行，否则沿用 XML 默认的本地界面）
        if (isRemoteMode[0]) {
            tabLocal.setBackgroundResource(R.drawable.bg_ssh_tab_active);
            tabLocal.setTextColor(0xFFFFFFFF);
            tabRemote.setBackground(null);
            tabRemote.setTextColor(0xFF909399);

            layoutAuth.setVisibility(View.VISIBLE);
            layoutLocalPassword.setVisibility(View.GONE);
            tvLocalStatus.setVisibility(View.GONE);
            tvLocalHelp.setVisibility(View.GONE);
            tvLabelHost.setText("主机名/IP");
            tvIpExtra.setText("");
            tvPortExtra.setText("");
            tvUsernameExtra.setText("");
            etIp.setEnabled(true);
            etPort.setEnabled(true);
            etIp.setHint("example.com");
            etPort.setHint("22");
            etUsername.setHint("root");
        } else {
            tabRemote.setBackgroundResource(R.drawable.bg_ssh_tab_active);
            tabRemote.setTextColor(0xFFFFFFFF);
            tabLocal.setBackground(null);
            tabLocal.setTextColor(0xFF909399);

            layoutAuth.setVisibility(View.GONE);
            layoutLocalPassword.setVisibility(View.VISIBLE);
        }

        // ---- 本地模式状态（添加和编辑都执行，编辑不覆盖密码） ----
        fillLocalInfo(tvUsernameDisplay, etLocalPassword, tvLocalStatus, tvLocalHelp, isEdit);

        // ---- 构建对话框 ----
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "编辑SSH配置" : "添加SSH配置")
                .setView(dialogView)
                .setNegativeButton("取消", null);

        builder.setPositiveButton("保存", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button btnSave = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnSave.setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String desc = etDescription.getText().toString().trim();

                String configType = isRemoteMode[0] ? "remote" : "local_termux";

                String host;
                int port;
                String username;
                String authType;
                String password = null;
                String keyContent = null;
                String keyPassphrase = null;

                if (isRemoteMode[0]) {
                    // 远程: 从输入框读取
                    host = etIp.getText().toString().trim();
                    String portStr = etPort.getText().toString().trim();
                    username = etUsername.getText().toString().trim();
                    if (host.isEmpty()) {
                        Toast.makeText(this, "请填写主机名/IP", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (username.isEmpty()) {
                        Toast.makeText(this, "请填写用户名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        port = Integer.parseInt(portStr.isEmpty() ? "22" : portStr);
                    } catch (NumberFormatException e) {
                        port = 22;
                    }
                    if (isPasswordAuth[0]) {
                        authType = "password";
                        password = etPassword.getText().toString().trim();
                    } else {
                        authType = "key";
                        keyContent = etKey.getText().toString().trim();
                        if (keyContent.isEmpty()) {
                            Toast.makeText(this, "请粘贴私钥内容", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 仅当勾选“已加密”时才读取并保存密码
                        keyPassphrase = cbKeyEncrypted.isChecked()
                                ? etKeyPassphrase.getText().toString()
                                : null;
                    }
                } else {
                    // 本地: 使用固定探测值，仅密码从输入框读取
                    if (mTermuxUid < 0) {
                        Toast.makeText(this, "应用未安装或无UID，请使用远程模式", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    host = "127.0.0.1";
                    port = 8022;
                    username = String.valueOf(mTermuxUid);
                    authType = "password";
                    password = etLocalPassword.getText().toString().trim();
                }

                if (isEdit) {
                    config.setName(name);
                    config.setDescription(desc);
                    config.setHost(host);
                    config.setPort(port);
                    config.setUsername(username);
                    config.setPassword(password);
                    config.setAuthType(authType);
                    config.setKeyContent(keyContent);
                    config.setKeyPassphrase(keyPassphrase);
                    config.setConfigType(configType);
                    mDbHelper.updateConfig(config);
                    Toast.makeText(this, "配置已更新", Toast.LENGTH_SHORT).show();
                } else {
                    if ("local_termux".equals(configType)) {
                        SshConfigBean existing = mDbHelper.getLocalTermuxConfig();
                        if (existing != null) {
                            // 新增中不允许覆盖本地配置
                            Toast.makeText(this, "本地配置已存在，请通过长按手动修改", Toast.LENGTH_SHORT).show();
                            return;
                        } else {
                            SshConfigBean newConfig = new SshConfigBean(
                                    name.isEmpty() ? null : name, host, port, username,
                                    password, authType, keyContent, desc.isEmpty() ? null : desc,
                                    "local_termux");
                            newConfig.setKeyPassphrase(keyPassphrase);
                            mDbHelper.addConfig(newConfig);
                            Toast.makeText(this, "本地配置已添加", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        SshConfigBean newConfig = new SshConfigBean(
                                name.isEmpty() ? null : name, host, port, username,
                                password, authType, keyContent, desc.isEmpty() ? null : desc,
                                "remote");
                        newConfig.setKeyPassphrase(keyPassphrase);
                        mDbHelper.addConfig(newConfig);
                        Toast.makeText(this, "远程配置已添加", Toast.LENGTH_SHORT).show();
                    }
                }
                refreshList();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void applyUsernameMode(boolean isRemote, EditText et, TextView tv) {
        et.setVisibility(isRemote ? View.VISIBLE : View.GONE);
        tv.setVisibility(isRemote ? View.GONE : View.VISIBLE);
    }

    /** 仅刷新状态/UID/帮助，不触碰密码（切 tab 时使用）。 */
    private void refreshLocalStatus(TextView tvUsernameDisplay,
                                     TextView tvStatus, TextView tvHelp) {
        tvUsernameDisplay.setText(mTermuxUid >= 0 ? String.valueOf(mTermuxUid) : "root");
        StringBuilder sb = new StringBuilder();
        sb.append(mPort8022Open ? "✔ 8022端口已开放" : "✘ 8022端口未开放");
        if (mTermuxUid >= 0) {
            sb.append("  |  ✔ ").append(PKG_TERMUX).append(" (uid=").append(mTermuxUid).append(")");
        } else {
            sb.append("  |  ✘ ").append(PKG_TERMUX).append(" 未安装");
        }
        tvStatus.setText(sb.toString());
        tvStatus.setVisibility(View.VISIBLE);
        tvHelp.setText(mTermuxUid < 0 || !mPort8022Open
                ? "⚠ 环境异常，点击查看安装帮助" : "查看安装帮助");
        tvHelp.setVisibility(View.VISIBLE);
    }

    private void fillLocalInfo(TextView tvUsernameDisplay, EditText etPassword,
                                TextView tvStatus, TextView tvHelp, boolean isEdit) {
        refreshLocalStatus(tvUsernameDisplay, tvStatus, tvHelp);
    }

    private void applyAuthMode(TextView tabPassword, TextView tabKey,
                                View pwdInput, View keyRow, View keyEncryptedBox,
                                EditText etK, boolean isPassword) {
        if (isPassword) {
            tabPassword.setBackgroundResource(R.drawable.bg_ssh_auth_tab_active);
            tabPassword.setTextColor(0xFFFFFFFF);
            tabKey.setBackground(null);
            tabKey.setTextColor(0xFF909399);
            pwdInput.setVisibility(View.VISIBLE);
            keyRow.setVisibility(View.GONE);
            keyEncryptedBox.setVisibility(View.GONE);
        } else {
            tabKey.setBackgroundResource(R.drawable.bg_ssh_auth_tab_active);
            tabKey.setTextColor(0xFFFFFFFF);
            tabPassword.setBackground(null);
            tabPassword.setTextColor(0xFF909399);
            pwdInput.setVisibility(View.GONE);
            keyRow.setVisibility(View.VISIBLE);
            keyEncryptedBox.setVisibility(View.VISIBLE);
            etK.setVisibility(View.VISIBLE);
        }
    }

    // ================================================================
    //  安装帮助弹窗
    // ================================================================

    private String buildInstallSshCmd(String pwd) {
        if (pwd.isEmpty()) pwd = "123456";
        return CODE_INSTALL_SSH_PREFIX + "echo -e \"" + pwd + "\\n" + pwd + "\\n\\n\" | passwd\n";
    }

    private void showInstallHelpDialog(String currentPassword) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_ssh_help, null);
        String installCmd = buildInstallSshCmd(currentPassword);

        ((TextView) view.findViewById(R.id.tv_code_mirror)).setText(CODE_MIRROR);
        ((TextView) view.findViewById(R.id.tv_code_ssh)).setText(installCmd);

        view.findViewById(R.id.btn_copy_mirror).setOnClickListener(v -> {
            copyToClipboard("mirror", CODE_MIRROR + "\n");
            Toast.makeText(this, "换源命令已复制", Toast.LENGTH_SHORT).show();
            launchTermux();
        });
        view.findViewById(R.id.btn_copy_ssh).setOnClickListener(v -> {
            copyToClipboard("ssh_install", installCmd + "\n");
            Toast.makeText(this, "安装命令已复制", Toast.LENGTH_SHORT).show();
            launchTermux();
        });

        view.findViewById(R.id.tv_link_official).setOnClickListener(v ->
                confirmOpenUrl("官方 Termux", URL_OFFICIAL));
        view.findViewById(R.id.tv_link_zerotermux).setOnClickListener(v ->
                confirmOpenUrl("ZeroTermux 下载", URL_ZEROTERMUX_APK));
        view.findViewById(R.id.tv_link_zerotermux_project).setOnClickListener(v ->
                confirmOpenUrl("ZeroTermux 项目", URL_ZEROTERMUX_PROJECT));

        new AlertDialog.Builder(this)
                .setTitle("安装帮助")
                .setView(view)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmOpenUrl(String title, String url) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("是否使用浏览器访问此链接？\n\n" + url)
                .setPositiveButton("访问", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchTermux() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(PKG_TERMUX);
        if (intent != null) {
            startActivity(intent);
        }
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    // ================================================================
    //  连接进度弹窗
    // ================================================================

    private void showConnectingDialog(String host) {
        runOnUiThread(() -> {
            if (mConnectDialog != null && mConnectDialog.isShowing()) mConnectDialog.dismiss();
            mConnectDialog = new android.app.ProgressDialog(this);
            mConnectDialog.setTitle("正在连接");
            mConnectDialog.setMessage("正在连接 " + host + " ...\n请稍候，验证过程可能需要几秒");
            mConnectDialog.setCancelable(false);
            mConnectDialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
            mConnectDialog.show();
        });
    }

    private void updateConnectingMessage(String msg) {
        runOnUiThread(() -> {
            if (mConnectDialog != null && mConnectDialog.isShowing()) {
                mConnectDialog.setMessage(msg);
            }
        });
    }

    private void dismissConnectingDialog() {
        runOnUiThread(() -> {
            if (mConnectDialog != null && mConnectDialog.isShowing()) {
                mConnectDialog.dismiss();
            }
        });
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private boolean checkPort(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private int getPackageUid(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private String generatePassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$";
        Random r = new Random();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ================================================================
    //  Adapter 回调
    // ================================================================

    @Override
    public void onClick(SshConfigBean config) {
        LocalShellExecutor exec = LocalShellExecutor.getInstance();

        // 搜索该配置的活跃会话，拿到真实会话列表（可能 0/1/多个）
        List<String> aliveSessions = new ArrayList<>();
        JSONObject searchResult = exec.search_session("ssh_" + config.getId(), "name");
        if (searchResult != null && searchResult.optBoolean("found", false)) {
            JSONArray matches = searchResult.optJSONArray("matches");
            if (matches != null) {
                for (int i = 0; i < matches.length(); i++) {
                    JSONObject m = matches.optJSONObject(i);
                    if (m == null) continue;
                    String sid = m.optString("id", "");
                    // 逐个确认会话是否真实存活（防止池中残留/已被清空的僵尸会话）
                    JSONObject st = exec.session_status(sid);
                    JSONObject stData = st != null ? st.optJSONObject("data") : null;
                    if (stData != null && stData.optBoolean("alive", false)) {
                        String label = m.optString("name", config.getHost());
                        aliveSessions.add(sid + "|" + label);
                    }
                }
            }
        }

        if (aliveSessions.isEmpty()) {
            // 没有真实存活会话 → 直接新建
            showConnectingDialog(config.getHost() + ":" + config.getPort());
            new Thread(() -> doSshVerify(config)).start();
            return;
        }

        if (aliveSessions.size() == 1) {
            String sid = aliveSessions.get(0).split("\\|", 2)[0];
            String label = aliveSessions.get(0).split("\\|", 2)[1];
            new AlertDialog.Builder(this)
                .setTitle("已有活跃连接")
                .setMessage(config.getHost() + "\n\n会话：" + label)
                .setPositiveButton("复用已有", (d, w) -> openExistingSession(sid))
                .setNeutralButton("取消", null)
                .setNegativeButton("新建连接", (d, w) -> {
                    showConnectingDialog(config.getHost() + ":" + config.getPort());
                    new Thread(() -> doSshVerify(config)).start();
                })
                .show();
            return;
        }

        // 多个活跃会话 → 列表让用户选择要复用的会话
        final String[] labels = new String[aliveSessions.size()];
        final String[] ids = new String[aliveSessions.size()];
        for (int i = 0; i < aliveSessions.size(); i++) {
            String[] parts = aliveSessions.get(i).split("\\|", 2);
            ids[i] = parts[0];
            labels[i] = parts[1] + "\n" + parts[0];
        }
        new AlertDialog.Builder(this)
            .setTitle("选择要复用的会话（" + aliveSessions.size() + " 个）")
            .setItems(labels, (d, which) -> openExistingSession(ids[which]))
            .setNeutralButton("取消", null)
            .setNegativeButton("新建连接", (d, w) -> {
                showConnectingDialog(config.getHost() + ":" + config.getPort());
                new Thread(() -> doSshVerify(config)).start();
            })
            .show();
    }

    /** 复用已有会话：打开终端界面并精确挂载该会话。 */
    private void openExistingSession(String sessionId) {
        startActivity(new Intent(SshTestActivity.this, LocalShellTestActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LocalShellTestActivity.EXTRA_SESSION_ID, sessionId));
    }

    @Override
    public void onEdit(SshConfigBean config) {
        showSshDialog(config, true);
    }

    @Override
    public void onDelete(SshConfigBean config, int position) {
        mDbHelper.deleteConfig(config.getId());
        mConfigList.remove(position);
        mAdapter.notifyItemRemoved(position);
        Toast.makeText(this, "已删除配置", Toast.LENGTH_SHORT).show();
    }

    // ================================================================
    //  SSH 验证链路
    // ================================================================

    private void doSshVerify(SshConfigBean config) {
        mVerifyCancelled = false;   // 新一次验证，重置取消标志
        // 新建连接使用唯一 ID，不复用旧 session
        doSshVerifyWithId(config, "ssh_" + config.getId() + "_" + System.currentTimeMillis());
    }

    /** 验证流程中检查是否已被取消（界面已关闭）。 */
    private boolean isVerifyCancelled(String sid) {
        if (!mVerifyCancelled && !isFinishing() && !isDestroyed()) return false;
        // 已取消：清理会话
        try { LocalShellExecutor.getInstance().destroy_session(sid); } catch (Exception ignored) {}
        dismissConnectingDialog();
        return true;
    }

    private void doSshVerifyWithId(SshConfigBean config, String sid) {
        LocalShellExecutor exec = LocalShellExecutor.getInstance();

        // 验证用 SSH 命令：ConnectTimeout 只约束握手阶段（不会打断密码交互）
        String sshCmd = "ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new "
                + config.getUsername() + "@" + config.getHost()
                + " -p " + config.getPort();
        // 正式连接命令：不带 ConnectTimeout（跨域/DNS 慢时握手可能超 5 秒，带上会误断）
        String realSshCmd = "ssh -o StrictHostKeyChecking=accept-new "
                + config.getUsername() + "@" + config.getHost()
                + " -p " + config.getPort();
        String password = config.getPassword() != null ? config.getPassword() : "";
        boolean useKey = "key".equals(config.getAuthType());
        String keyContent = config.getKeyContent() != null ? config.getKeyContent() : "";
        String keyPassphrase = config.getKeyPassphrase() != null ? config.getKeyPassphrase() : "";

        if (useKey) {
            if (keyContent.trim().isEmpty()) {
                dismissConnectingDialog();
                runOnUiThread(() -> Toast.makeText(this, "私钥内容为空，请修改配置", Toast.LENGTH_SHORT).show());
                return;
            }
        } else if (password.isEmpty()) {
            dismissConnectingDialog();
            runOnUiThread(() -> Toast.makeText(this, "密码为空，请修改配置", Toast.LENGTH_SHORT).show());
            return;
        }

        // 1. 创建会话
        updateConnectingMessage("正在创建终端会话...");
        exec.create_session(sid, "SSH验证");

        // 密钥模式：先加载私钥到 ssh-agent（有密码则先解密），之后 ssh 无需密码
        if (useKey) {
            updateConnectingMessage("正在加载私钥...");
            String err = loadKeyToAgent(exec, sid, config, keyPassphrase);
            if (err != null) {
                exec.destroy_session(sid);
                dismissConnectingDialog();
                final String msg = err;
                runOnUiThread(() -> showKeyErrorDialog(config, msg));
                return;
            }
        }

        // 2. 启动 SSH 并轮询等待关键提示（密码提示/主机密钥/错误/退出），最多 8 秒
        updateConnectingMessage("正在连接 " + config.getHost() + ":" + config.getPort() + " ...");
        String output = sshConnectAndWait(exec, sid, sshCmd, 8000);

        // 3a. 主机密钥冲突 → 清理后重试
        // 注意：默认端口 22 时 known_hosts 存的是纯 host（不带端口）；非标准端口才是 [host]:port
        if (output.contains("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED")
                || output.contains("Host key verification failed")
                || output.contains("has changed and you have requested strict checking")) {
            updateConnectingMessage("检测到主机密钥变更，正在清理...");
            exec.shell_send_key(sid, "CTRL_C");
            sleep(400);
            // 两种格式都清：带端口的 [host]:port 与纯 host
            exec.shell_exec(sid, "ssh-keygen -R \"[" + config.getHost() + "]:" + config.getPort()
                    + "\" >/dev/null 2>&1 ; ssh-keygen -R \"" + config.getHost() + "\" >/dev/null 2>&1"
                    + " ; echo HOSTKEY_CLEANED", 1200);
            sleep(300);
            output = sshConnectAndWait(exec, sid, sshCmd, 8000);
        }

        // 3b. 首次连接 → yes
        if (output.contains("continue connecting (yes/no")) {
            updateConnectingMessage("首次连接，正在确认主机密钥...");
            exec.shell_write(sid, "yes\r");
            output = sshConnectAndWait(exec, sid, null, 6000);
        }

        // 3c. 输入密码（两次性的第一次：验证阶段）
        // 密钥模式：若 agent 加载成功，SSH 不应再索要密码；若仍提示则说明密钥未生效
        boolean passwordVerified = false;
        if (output.contains("assword:")) {
            if (useKey) {
                // 密钥已加载但仍要求密码 → 认证失败
                exec.shell_send_key(sid, "CTRL_C");
                exec.destroy_session(sid);
                dismissConnectingDialog();
                runOnUiThread(() -> showKeyErrorDialog(config, "私钥认证失败：服务器仍要求密码，请检查私钥是否与服务器 authorized_keys 匹配"));
                return;
            }
            updateConnectingMessage("正在验证密码...");
            int beforeCount = countKeyword(output, "assword:");
            exec.shell_write(sid, password + "\r");

            // 轮询等待验证结果（最多 5 秒）：密码正确进入 shell 或再次提示密码
            long pwdStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - pwdStart < 5000) {
                sleep(600);
                output = execResult(exec.shell_read(sid, "all", 100, false, false, false));
                if (countKeyword(output, "assword:") > beforeCount
                        || output.contains("Permission denied")
                        || output.contains("try again")
                        || output.contains("SSH_EXIT_CODE:")) {
                    break;
                }
            }

            int afterCount = countKeyword(output, "assword:");
            if (afterCount > beforeCount
                    || output.contains("Permission denied")
                    || output.contains("try again")) {
                exec.shell_send_key(sid, "CTRL_C");
                exec.destroy_session(sid);
                dismissConnectingDialog();
                runOnUiThread(() -> Toast.makeText(this, "密码可能存在错误，需修改配置", Toast.LENGTH_SHORT).show());
                return;
            }
            passwordVerified = true;
        }

        // 3c-2. 密钥模式：无密码提示即视为公钥认证成功（加载 agent 后无需交互）
        if (useKey && !passwordVerified
                && !output.contains("Permission denied")
                && !output.contains("Connection refused")
                && !output.contains("Connection timed out")) {
            passwordVerified = true;
        }

        // 3d. 检查 SSH 返回值（仅在密码未验证通过时判断）
        // 密码已验证通过后，SSH 退出/被 timeout 强杀的返回值（124/130/137 等）不代表连接失败，
        // 必须跳过，否则会把成功的连接误判为失败。
        String exitCodeMatch = null;
        int idx = output.indexOf("SSH_EXIT_CODE:");
        if (idx != -1) {
            exitCodeMatch = output.substring(idx + "SSH_EXIT_CODE:".length()).trim();
        }

        if (!passwordVerified && exitCodeMatch != null && !"0".equals(exitCodeMatch)) {
            exec.destroy_session(sid);
            dismissConnectingDialog();
            final String displayMsg;
            if ("124".equals(exitCodeMatch)) {
                displayMsg = "连接超时：主机不可达或 DNS 解析过慢，请检查网络环境";
            } else if ("255".equals(exitCodeMatch)) {
                displayMsg = "连接失败：目标主机拒绝连接或端口未开放";
            } else if ("1".equals(exitCodeMatch)) {
                displayMsg = "认证失败：用户名/密码错误";
            } else {
                displayMsg = "连接失败，SSH 返回码 " + exitCodeMatch;
            }
            runOnUiThread(() -> {
                Toast.makeText(this, displayMsg, Toast.LENGTH_LONG).show();
                if ("local_termux".equals(config.getConfigType())) {
                    showInstallHelpDialog(password);
                }
            });
            return;
        }
        
        // 3e. 兼容旧关键词判断（仅密码未验证通过时；避免历史输出误伤）
        if (!passwordVerified
                && (output.contains("Connection refused") || output.contains("Connection timed out"))) {
            exec.destroy_session(sid);
            dismissConnectingDialog();
            runOnUiThread(() -> {
                Toast.makeText(this, "连接失败，端口未开放或服务未启动", Toast.LENGTH_SHORT).show();
                if ("local_termux".equals(config.getConfigType())) {
                    showInstallHelpDialog(password);
                }
            });
            return;
        }
        // 4. 验证成功 → 销毁临时验证会话，重建干净的 SSH 连接会话
        // 目的：清除验证过程中的 "yes/no"、密码提示等残留输出，给用户一个干净的终端
        // （LocalShellService 已修复：removeTermuxSession 不再 stopSelf，服务不会崩溃）
        updateConnectingMessage("验证成功，正在建立终端会话...");
        exec.destroy_session(sid);
        sleep(400);
        exec.create_session(sid, "SSH连接");
        // 新会话终端 buffer 需要时间就绪，否则后续 shell_read 会返回空
        sleep(1200);
        // 5. 重建后执行 clear && exec ssh + 自动密码/重新加载密钥，建立正式 SSH 连接
        updateConnectingMessage("正在初始化 SSH 连接...");
        if (useKey) {
            // 新会话里 agent 环境丢失，需重新加载私钥（写入文件 + ssh-add，一次性完成）
            String rerr = loadKeyToAgent(exec, sid, config, keyPassphrase);
            if (rerr != null) {
                exec.destroy_session(sid);
                dismissConnectingDialog();
                runOnUiThread(() -> showKeyErrorDialog(config, rerr));
                return;
            }
        }
        exec.shell_write(sid, "clear && exec " + realSshCmd + "\r");
        sleep(2000);
        output = execResult(exec.shell_read(sid, "all", 100, false, false, false));
        if (!useKey && output.contains("assword:")) {
            exec.shell_write(sid, password + "\r");
            sleep(1500);
        }

        // 6. 打开终端界面（传递本次新建的 sid，确保挂载到 SSH 会话而不是本地 termux）
        // 若用户已离开本界面，则不再强制拉起终端，避免“已关闭界面被重新调起”
        if (isVerifyCancelled(sid)) return;
        // 连接已完成（私钥已加载进 agent），立即清理磁盘临时文件，只保留数据库副本
        cleanupAllTempKeys();
        updateConnectingMessage("正在打开终端...");
        dismissConnectingDialog();
        final String finalSid = sid;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            startActivity(
                new Intent(SshTestActivity.this, LocalShellTestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(LocalShellTestActivity.EXTRA_SESSION_ID, finalSid));
        });
    }

    /**
     * 启动 SSH（cmd 非空时）并轮询等待关键输出。
     * 关键提示：密码提示 / 主机密钥确认 / 连接错误 / SSH 退出码。
     * 命中任一提示立即返回，避免像原来的固定 sleep 那样对已经退出的 SSH 继续发密码。
     *
     * @param cmd       为 null 时只轮询，不启动新命令
     * @param maxWaitMs 最长等待毫秒
     */
    private String sshConnectAndWait(LocalShellExecutor exec, String sid, String cmd, long maxWaitMs) {
        if (cmd != null) {
            // timeout 20 兜底防止极端情况卡死；正常情况下轮询会提前返回
            exec.shell_exec(sid, "timeout 20 " + cmd + " ; echo \"SSH_EXIT_CODE:$?\"", 700);
        }
        long start = System.currentTimeMillis();
        String output = "";
        while (System.currentTimeMillis() - start < maxWaitMs) {
            sleep(600);
            output = execResult(exec.shell_read(sid, "all", 100, false, false, false));
            if (output.contains("assword:")
                    || output.contains("yes/no")
                    || output.contains("Connection refused")
                    || output.contains("Connection timed out")
                    || output.contains("Permission denied")
                    || output.contains("SSH_EXIT_CODE:")) {
                break;
            }
        }
        return output;
    }

    /**
     * 将私钥加载到 ssh-agent。
     * 返回 null 表示成功；非 null 为错误提示文案。
     *
     * 流程：启动 agent → 用 heredoc 写入私钥文件（避免特殊字符转义问题）→
     *      根据是否加密选择直接 ssh-add 或带 passphrase 解密加载。
     */
    private String loadKeyToAgent(LocalShellExecutor exec, String sid,
                                  SshConfigBean config, String passphrase) {
        // 临时文件全部用 Java 直接读写（绕过 PTY），更快且不受转义/折行影响
        java.io.File dir = new java.io.File(getFilesDir(), "ssh");
        if (!dir.exists()) dir.mkdirs();
        java.io.File keyFile = new java.io.File(dir, ".key_" + sid.replaceAll("[^A-Za-z0-9_]", "_"));
        java.io.File askFile = null;

        try {
            // 1. 确保 agent 运行并导出环境变量（终端 buffer 可能未就绪，需重试）
            String out = "";
            String agentCmd = "if [ -z \"$SSH_AUTH_SOCK\" ]; then eval $(ssh-agent -s) >/dev/null; fi"
                    + " ; echo AGENT_READY:$SSH_AUTH_SOCK ; echo AGENT_DONE";
            boolean agentOk = false;
            for (int attempt = 0; attempt < 3 && !agentOk; attempt++) {
                exec.shell_exec(sid, agentCmd, 600);
                long agentStart = System.currentTimeMillis();
                while (System.currentTimeMillis() - agentStart < 6000) {
                    sleep(500);
                    out = execResult(exec.shell_read(sid, "all", 100, false, false, false));
                    if (out.contains("AGENT_DONE")) break;
                }
                Log.d(TAG, "agent attempt " + attempt + ": out=[" + out + "]");
                agentOk = out.contains("AGENT_READY:/");
                if (!agentOk) sleep(800);
            }
            if (!agentOk) return "无法启动 ssh-agent，请检查环境";

            // 2. 用 Java 直接写私钥文件（不走终端 heredoc）
            String keyContent = config.getKeyContent().trim();
            if (!keyContent.endsWith("\n")) keyContent += "\n";
            writeFile(keyFile, keyContent);
            // 3. 用 Java 直接校验内容（无需 grep，不受 shell 方言影响）
            String content = readFile(keyFile);
            if (!content.contains("PRIVATE KEY-----")) {
                return "私钥格式无法识别，请确认粘贴完整（需包含 BEGIN/END 行）";
            }
            if (!content.trim().endsWith("-----END OPENSSH PRIVATE KEY-----")
                    && !content.trim().endsWith("-----END RSA PRIVATE KEY-----")
                    && !content.trim().endsWith("-----END EC PRIVATE KEY-----")) {
                return "私钥内容不完整：缺少 END 结束行，请重新完整粘贴";
            }

            // 4. 加载密钥（加密的需带 passphrase）；askpass 脚本也用 Java 写
            String addCmd;
            if (passphrase != null && !passphrase.isEmpty()) {
                askFile = new java.io.File(dir, ".ask_" + sid.replaceAll("[^A-Za-z0-9_]", "_"));
                writeFile(askFile, "#!/bin/sh\nprintf \"%s\\\\n\" " + shellQuote(passphrase) + "\n");
                addCmd = "chmod 700 " + shellPath(askFile) + " " + shellPath(keyFile)
                        + " ; SSH_ASKPASS=" + shellPath(askFile)
                        + " SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-add " + shellPath(keyFile)
                        + " 2>&1 ; echo \"ADD_EXIT:$?\"";
            } else {
                addCmd = "chmod 600 " + shellPath(keyFile)
                        + " ; ssh-add " + shellPath(keyFile) + " 2>&1 ; echo \"ADD_EXIT:$?\"";
            }
            exec.shell_exec(sid, addCmd, 800);

            // 轮询等待 ssh-add 完成（加密私钥解密可能耗时）
            out = "";
            long addStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - addStart < 9000) {
                sleep(500);
                out = execResult(exec.shell_read(sid, "all", 100, false, false, false));
                if (out.contains("ADD_EXIT:")) break;
            }

            // 5. 判断结果
            Log.d(TAG, "ssh-add out=[" + out + "]");
            int idx = out.lastIndexOf("ADD_EXIT:");
            if (idx < 0) {
                return "私钥加载超时（ssh-add 未响应），请检查私钥密码是否正确";
            }
            String exitCode = out.substring(idx + 9).trim().split("\\s")[0];
            if (!"0".equals(exitCode)) {
                if (out.contains("error in libcrypto") || out.contains("incorrect passphrase")
                        || out.contains("Bad passphrase") || out.contains("Error loading key")) {
                    return "私钥解密失败：密码错误，或私钥内容不完整";
                }
                return "私钥加载失败（ssh-add 退出码 " + exitCode + "），请检查私钥内容";
            }
            return null;
        } finally {
            // 6. 加载完成（成功或失败）立即用 Java 删除临时文件，不调用终端
            deleteQuietly(keyFile);
            deleteQuietly(askFile);
        }
    }

    /** 用 Java 写入文件（UTF-8，仅所有者可读写）。返回是否成功。 */
    private boolean writeFile(java.io.File f, String content) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(f);
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.d(TAG, "writeFile failed: " + e);
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
        try {
            f.setReadable(false, false); f.setReadable(true, true);
            f.setWritable(false, false); f.setWritable(true, true);
            f.setExecutable(false, false);
        } catch (Exception ignored) {}
        return true;
    }

    /** 用 Java 读取文件内容。 */
    private String readFile(java.io.File f) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int n = fis.read(buf);
                return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
            } finally {
                try { fis.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 用 Java 删除文件（静默失败）。 */
    private void deleteQuietly(java.io.File f) {
        if (f == null) return;
        try { if (f.exists()) f.delete(); } catch (Exception ignored) {}
    }

    /**
     * 清理所有临时密钥/口令文件（新格式 + 旧版本遗留的固定路径）。
     * 私钥内容只保留在数据库中，磁盘不保留任何副本。
     * 在启动时、验证成功后、onDestroy 等处调用。
     */
    private void cleanupAllTempKeys() {
        // 1. 新格式：files/ssh/.key_* 、.ask_*
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "ssh");
            if (dir.exists() && dir.isDirectory()) {
                java.io.File[] files = dir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        String n = f.getName();
                        if (n.startsWith(".key_") || n.startsWith(".ask_")) {
                            deleteQuietly(f);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. 旧版本遗留的固定路径文件（含明文口令，必须清）
        try {
            java.io.File base = getFilesDir();
            deleteQuietly(new java.io.File(base, ".sshtmp_key"));
            deleteQuietly(new java.io.File(base, ".sshtmp_askpass.sh"));
        } catch (Exception ignored) {}
    }

    /** 终端环境下给路径加引号（防空格/特殊字符）。 */
    private String shellPath(java.io.File f) {
        return "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
    }

    /** 单引号包裹 shell 参数（内部单引号转为 '\''）。 */
    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 密钥相关错误弹窗：提示后引导用户去编辑配置修改。 */
    private void showKeyErrorDialog(SshConfigBean config, String message) {
        new AlertDialog.Builder(this)
                .setTitle("密钥连接失败")
                .setMessage(message)
                .setPositiveButton("编辑配置", (d, w) -> showSshDialog(config, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private String execResult(JSONObject result) {
        if (result == null) return "";
        return result.optString("content", "");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private int countKeyword(String text, String keyword) {
        if (text == null || keyword == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }
}
