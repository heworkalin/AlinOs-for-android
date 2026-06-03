package alin.android.alinos.dev;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_test);

        mDbHelper = new SshDbHelper(this);
        rvSshList = findViewById(R.id.rv_ssh_list);
        fabAdd = findViewById(R.id.fab_add);

        initList();
        probeLocalEnv();

        fabAdd.setOnClickListener(v -> showAddDialog());
    }

    private void initList() {
        mConfigList = mDbHelper.getAllConfigs();
        mAdapter = new SshConfigAdapter(mConfigList, this);
        rvSshList.setLayoutManager(new LinearLayoutManager(this));
        rvSshList.setAdapter(mAdapter);
    }

    private void refreshList() {
        mConfigList = mDbHelper.getAllConfigs();
        mAdapter.refreshData(mConfigList);
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
        EditText etKey = dialogView.findViewById(R.id.et_key);
        // 本地密码 + 状态 + 帮助
        View layoutLocalPassword = dialogView.findViewById(R.id.layout_local_password);
        EditText etLocalPassword = dialogView.findViewById(R.id.et_local_password);
        TextView tvLocalStatus = dialogView.findViewById(R.id.tv_local_status);
        TextView tvLocalHelp = dialogView.findViewById(R.id.tv_local_help);

        // 状态
        final boolean[] isRemoteMode = {false};  // 默认本地
        final boolean[] isPasswordAuth = {true};

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

            applyAuthMode(tabAuthPassword, tabAuthKey, etPassword, etKey, isPasswordAuth[0]);
        });

        // ---- 认证子 tab 切换 ----
        tabAuthPassword.setOnClickListener(v -> {
            isPasswordAuth[0] = true;
            applyAuthMode(tabAuthPassword, tabAuthKey, etPassword, etKey, true);
        });
        tabAuthKey.setOnClickListener(v -> {
            isPasswordAuth[0] = false;
            applyAuthMode(tabAuthPassword, tabAuthKey, etPassword, etKey, false);
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
            if ("key".equals(config.getAuthType())) {
                isRemoteMode[0] = true;
                isPasswordAuth[0] = false;
                tabLocal.performClick();
                etKey.setText(config.getKeyContent() != null ? config.getKeyContent() : "");
            } else {
                etPassword.setText(config.getPassword() != null ? config.getPassword() : "");
                etLocalPassword.setText(config.getPassword() != null ? config.getPassword() : "");
            }
        }

        // ---- 应用当前模式的初始 visibility ----
        applyUsernameMode(isRemoteMode[0], etUsername, tvUsernameDisplay);

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
                            mDbHelper.addConfig(newConfig);
                            Toast.makeText(this, "本地配置已添加", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        SshConfigBean newConfig = new SshConfigBean(
                                name.isEmpty() ? null : name, host, port, username,
                                password, authType, keyContent, desc.isEmpty() ? null : desc,
                                "remote");
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
        if (!isEdit) {
            etPassword.setText(generatePassword());
        }
    }

    private void applyAuthMode(TextView tabPassword, TextView tabKey,
                                EditText etPwd, EditText etK, boolean isPassword) {
        if (isPassword) {
            tabPassword.setBackgroundResource(R.drawable.bg_ssh_auth_tab_active);
            tabPassword.setTextColor(0xFFFFFFFF);
            tabKey.setBackground(null);
            tabKey.setTextColor(0xFF909399);
            etPwd.setVisibility(View.VISIBLE);
            etK.setVisibility(View.GONE);
        } else {
            tabKey.setBackgroundResource(R.drawable.bg_ssh_auth_tab_active);
            tabKey.setTextColor(0xFFFFFFFF);
            tabPassword.setBackground(null);
            tabPassword.setTextColor(0xFF909399);
            etPwd.setVisibility(View.GONE);
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

        // 搜索该配置是否已有活跃会话（session ID 包含 "ssh_" + configId）
        JSONObject searchResult = exec.search_session("ssh_" + config.getId(), "name");
        boolean hasActive = searchResult != null && searchResult.optBoolean("found", false);

        if (hasActive) {
            new AlertDialog.Builder(this)
                .setTitle("已有活跃连接")
                .setMessage(config.getHost() + " 已有活跃会话，请选择：")
                .setPositiveButton("复用已有", (d, w) ->
                    startActivity(new Intent(SshTestActivity.this, LocalShellTestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)))
                .setNeutralButton("取消", null)
                .setNegativeButton("新建连接", (d, w) -> {
                    showConnectingDialog(config.getHost() + ":" + config.getPort());
                    new Thread(() -> doSshVerify(config)).start();
                })
                .show();
        } else {
            showConnectingDialog(config.getHost() + ":" + config.getPort());
            new Thread(() -> doSshVerify(config)).start();
        }
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
        // 新建连接使用唯一 ID，不复用旧 session
        doSshVerifyWithId(config, "ssh_" + config.getId() + "_" + System.currentTimeMillis());
    }

    private void doSshVerifyWithId(SshConfigBean config, String sid) {
        LocalShellExecutor exec = LocalShellExecutor.getInstance();

        String sshCmd = "ssh " + config.getUsername() + "@" + config.getHost()
                + " -p " + config.getPort();
        String password = config.getPassword() != null ? config.getPassword() : "";

        if (password.isEmpty()) {
            dismissConnectingDialog();
            runOnUiThread(() -> Toast.makeText(this, "密码为空，请修改配置", Toast.LENGTH_SHORT).show());
            return;
        }

        // 1. 创建会话
        updateConnectingMessage("正在创建终端会话...");
        exec.create_session(sid, "SSH验证");

        // 2. 发送 SSH 连接
        updateConnectingMessage("正在连接 " + config.getHost() + ":" + config.getPort() + " ...");
        String output = sshConnect(exec, sid, sshCmd);

        // 3a. 主机密钥冲突 → 清理后重试
        if (output.contains("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED")
                || output.contains("Host key verification failed")) {
            updateConnectingMessage("检测到主机密钥变更，正在清理...");
            exec.shell_send_key(sid, "CTRL_C");
            sleep(300);
            exec.shell_exec(sid, "ssh-keygen -R \"[" + config.getHost() + "]:" + config.getPort() + "\"", 1000);
            sleep(200);
            output = sshConnect(exec, sid, sshCmd);
        }

        // 3b. 首次连接 → yes
        if (output.contains("continue connecting (yes/no")) {
            updateConnectingMessage("首次连接，正在确认主机密钥...");
            exec.shell_write(sid, "yes\r");
            sleep(1500);
            output = execResult(exec.shell_read(sid, "all", 100, false, false, false));
        }

        // 3c. 输入密码
        if (output.contains("assword:")) {
            updateConnectingMessage("正在验证密码...");
            int beforeCount = countKeyword(output, "assword:");
            exec.shell_write(sid, password + "\r");
            sleep(2500);
            output = execResult(exec.shell_read(sid, "all", 100, false, false, false));

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
        }

        // 3d. 连接被拒
        if (output.contains("Connection refused") || output.contains("Connection timed out")) {
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
        exec.create_session(sid, "SSH连接");

        // 5. 重建后执行 clear && exec ssh + 自动密码，建立正式 SSH 连接
        updateConnectingMessage("正在初始化 SSH 连接...");
        exec.shell_write(sid, "clear && exec " + sshCmd + "\r");
        sleep(2000);
        output = execResult(exec.shell_read(sid, "all", 100, false, false, false));
        if (output.contains("assword:")) {
            exec.shell_write(sid, password + "\r");
            sleep(1500);
        }

        // 6. 打开终端界面
        updateConnectingMessage("正在打开终端...");
        dismissConnectingDialog();
        runOnUiThread(() -> startActivity(
                new Intent(SshTestActivity.this, LocalShellTestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
    }

    /** 发送 SSH 命令并等待返回结果。 */
    private String sshConnect(LocalShellExecutor exec, String sid, String sshCmd) {
        exec.shell_exec(sid, sshCmd, 4000);
        sleep(500);
        return execResult(exec.shell_read(sid, "all", 100, false, false, false));
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
