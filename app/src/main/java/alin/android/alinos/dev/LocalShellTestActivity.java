package alin.android.alinos.dev;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.Arrays;
import java.util.Objects;

import alin.android.alinos.R;
import alin.android.alinos.localshell.LocalShellConstants;
import alin.android.alinos.localshell.LocalShellExecutor;
import alin.android.alinos.localshell.LocalShellService;

/**
 * 独立终端 Activity — 深度融合 TermuxActivity 的全部终端基础设施。
 * 基于termux-app的TermuxActivity，提供完整的终端功能.虽然不再使用内置的服务，单独编译的自己的服务。但是源代码来源仍然是TermuxActivity。需自行评估版权风险。
 * 直接绑定 LocalShellService，支持 SSH 直连、环境安装。
 */
public class LocalShellTestActivity extends AppCompatActivity implements ServiceConnection {

    private static final String LOG_TAG = "LocalShellTestActivity";

    // ========== 终端基础设施（原 TermuxActivity） ==========

    protected TermuxService mTermuxService;
    TerminalView mTerminalView;
    TermuxTerminalViewClient mTermuxTerminalViewClient;
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    private TermuxAppSharedPreferences mPreferences;
    private TermuxAppSharedProperties mProperties;
    TermuxActivityRootView mTermuxActivityRootView;
    View mTermuxActivityBottomSpaceView;
    ExtraKeysView mExtraKeysView;
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;
    TermuxSessionsListViewController mTermuxSessionListViewController;
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();
    Toast mLastToast;
    private boolean mIsVisible;
    private boolean mIsOnResumeAfterOnCreate = false;
    private boolean mIsActivityRecreated = false;
    private boolean mIsInvalidState;
    private int mNavBarHeight;
    private float mTerminalToolbarDefaultHeight;

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_ID = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    // ========== 生命周期 ==========

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        mProperties = TermuxAppSharedProperties.init(this);
        reloadProperties();
        setActivityTheme();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux);

        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();
        setTerminalToolbarView(savedInstanceState);
        setSettingsButtonView();
        setNewSessionButtonView();
        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);

        try {
            onBindToService();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start service", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();
        Logger.logDebug(LOG_TAG, "onStart");
        if (mIsInvalidState) return;
        mIsVisible = true;
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();
        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();
        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Logger.logVerbose(LOG_TAG, "onResume");
        if (mIsInvalidState) return;
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();
        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);
        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        Logger.logDebug(LOG_TAG, "onStop");
        if (mIsInvalidState) return;
        mIsVisible = false;
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();
        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();
        removeTermuxActivityRootViewGlobalLayoutListener();
        try { unregisterTermuxActivityBroadcastReceiver(); } catch (Exception ignored) {}
        try { DrawerLayout drawer = getDrawer(); if (drawer != null) drawer.closeDrawers(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.logDebug(LOG_TAG, "onDestroy");
        if (mIsInvalidState) return;
        if (mTermuxService != null) {
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }
        try { unbindService(this); } catch (Exception ignored) {}
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }

    // ========== ServiceConnection（融合 Termux + LocalShell） ==========

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        LocalShellService.LocalBinder localBinder = (LocalShellService.LocalBinder) binder;
        mTermuxService = localBinder.service;

        setTermuxSessionsListView();

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (isVisible()) {
                // 常规启动：通过 TermuxSessionClient 创建会话（自动挂载到终端视图）
                setupEnvironmentIfNeeded(() -> {
                    if (mTermuxService == null) return;
                    try {
                        mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Session create error", e);
                    }
                });
            } else {
                finishActivityIfNotFinishing();
            }
        } else {
            // SSH 启动：session 已由 SshTestActivity 预先创建，直接挂载
            attachCurrentSession();
        }

        mTermuxService.setTermuxTerminalSessionClient(getTermuxTerminalSessionClient());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");
        finishActivityIfNotFinishing();
    }

    // ========== 终端基础设施方法 ==========

    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();
        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }

    private void setActivityTheme() {
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }

    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    private void setTermuxTerminalViewAndClients() {
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);
        if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.onCreate();
        if (mTermuxTerminalSessionActivityClient != null) mTermuxTerminalSessionActivityClient.onCreate();
    }

    protected void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }

    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;
        setTerminalToolbarHeight();
        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);
        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;
        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;
        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }

    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {});
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(LocalShellTestActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });
        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            DrawerLayout drawer = getDrawer();
            if (drawer != null) {
                if (drawer.isDrawerOpen(Gravity.LEFT)) drawer.closeDrawers();
                else drawer.openDrawer(Gravity.LEFT);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        if (!LocalShellTestActivity.this.isFinishing()) finish();
    }

    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(LocalShellTestActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;
        boolean addAutoFillMenu = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillManager autofillManager = getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) addAutoFillMenu = true;
        }
        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (addAutoFillMenu)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_ID, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();
        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID: mTermuxTerminalViewClient.showUrlSelection(); return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID: mTermuxTerminalViewClient.shareSessionTranscript(); return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT: mTermuxTerminalViewClient.shareSelectedText(); return true;
            case CONTEXT_MENU_AUTOFILL_ID: requestAutoFill(); return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID: onResetTerminalSession(session); return true;
            case CONTEXT_MENU_KILL_PROCESS_ID: showKillSessionDialog(session); return true;
            case CONTEXT_MENU_STYLING_ID: showStylingDialog(); return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON: toggleKeepScreenOn(); return true;
            case CONTEXT_MENU_HELP_ID: return true;
            case CONTEXT_MENU_SETTINGS_ID: return true;
            case CONTEXT_MENU_REPORT_ID: mTermuxTerminalViewClient.reportIssueFromTranscript(); return true;
            default: return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> { dialog.dismiss(); session.finishIfRunning(); });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);
            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }

    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }

    private void requestAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillManager autofillManager = getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) {
                autofillManager.requestAutofill(mTerminalView);
            }
        }
    }

    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;
                if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    LocalShellTestActivity.this, requestCode, false, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(LocalShellTestActivity.this, LOG_TAG,
                            getString(R.string.msg_storage_permission_granted_on_request));
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(LocalShellTestActivity.this, LOG_TAG,
                            getString(R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION)
            requestStoragePermission(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION)
            requestStoragePermission(true);
    }

    // ========== Getter/Setter ==========

    public int getNavBarHeight() { return mNavBarHeight; }
    public TermuxActivityRootView getTermuxActivityRootView() { return mTermuxActivityRootView; }
    public View getTermuxActivityBottomSpaceView() { return mTermuxActivityBottomSpaceView; }
    public ExtraKeysView getExtraKeysView() { return mExtraKeysView; }
    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() { return mTermuxTerminalExtraKeys; }
    public void setExtraKeysView(ExtraKeysView v) { mExtraKeysView = v; }
    public DrawerLayout getDrawer() { return (DrawerLayout) findViewById(R.id.drawer_layout); }
    public ViewPager getTerminalToolbarViewPager() { return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager); }
    public float getTerminalToolbarDefaultHeight() { return mTerminalToolbarDefaultHeight; }
    public boolean isTerminalViewSelected() { return getTerminalToolbarViewPager().getCurrentItem() == 0; }
    public boolean isTerminalToolbarTextInputViewSelected() { return getTerminalToolbarViewPager().getCurrentItem() == 1; }
    public void termuxSessionListNotifyUpdated() { mTermuxSessionListViewController.notifyDataSetChanged(); }
    public boolean isVisible() { return mIsVisible; }
    public boolean isOnResumeAfterOnCreate() { return mIsOnResumeAfterOnCreate; }
    public boolean isActivityRecreated() { return mIsActivityRecreated; }
    public TermuxService getTermuxService() { return mTermuxService; }
    public TerminalView getTerminalView() { return mTerminalView; }
    public TermuxTerminalViewClient getTermuxTerminalViewClient() { return mTermuxTerminalViewClient; }
    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() { return mTermuxTerminalSessionActivityClient; }
    @Nullable public TerminalSession getCurrentSession() { return mTerminalView != null ? mTerminalView.getCurrentSession() : null; }
    public TermuxAppSharedPreferences getPreferences() { return mPreferences; }
    public TermuxAppSharedProperties getProperties() { return mProperties; }

    // ========== 静态工具方法 ==========

    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        try { unregisterReceiver(mTermuxActivityBroadcastReceiver); }
        catch (IllegalArgumentException e) { Logger.logDebug(LOG_TAG, "Broadcast receiver was not registered"); }
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;
        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);
                String action = Objects.requireNonNull(intent.getAction());
                if (action.equals(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)) {
                    TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                } else if (action.equals(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)) {
                    reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                } else if (action.equals(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)) {
                    requestStoragePermission(false);
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();
            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }
        setMargins();
        setTerminalToolbarHeight();
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();
        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();
        if (recreateActivity) LocalShellTestActivity.this.recreate();
    }

    // ========== Session 挂载 ==========

    /** 将 mTermuxService 中的最后一个 session 挂载到终端视图 */
    private void attachCurrentSession() {
        setTermuxSessionsListView();
        if (mTermuxService != null && !mTermuxService.isTermuxSessionsEmpty()) {
            getTermuxTerminalSessionClient().setCurrentSession(
                mTermuxService.getLastTermuxSession().getTerminalSession());
        }
        if (getCurrentSession() != null) {
            getTerminalView().onScreenUpdated();
        }
    }

    // ========== Service 绑定 ==========

    protected Intent createServiceIntent() {
        return new Intent(this, LocalShellService.class);
    }

    protected void onBindToService() {
        Intent serviceIntent = createServiceIntent();
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");
    }

    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, LocalShellTestActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    // ========== 环境安装（LocalShell 独有） ==========

    private void setupEnvironmentIfNeeded(Runnable whenDone) {
        String prefixPath = LocalShellConstants.PREFIX_DIR_PATH;
        java.io.File prefixDir = new java.io.File(prefixPath);
        java.io.File bashFile = new java.io.File(prefixDir, "bin/bash");

        Logger.logInfo(LOG_TAG, "Checking environment at: " + prefixPath);
        Logger.logInfo(LOG_TAG, "  prefix exists: " + prefixDir.isDirectory() + ", bash exists: " + bashFile.exists());

        if (prefixDir.isDirectory() && bashFile.exists()) {
            Logger.logInfo(LOG_TAG, "Environment already installed, skipping extraction");
            whenDone.run();
            return;
        }

        Logger.logInfo(LOG_TAG, "Environment not found, starting TAR extraction...");
        try { Toast.makeText(this, "正在安装环境，请稍候...", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}

        final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("正在解压环境文件 (约60MB)...");
        progressDialog.setCancelable(false);
        progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        try { progressDialog.show(); } catch (Exception ignored) {}

        new Thread(() -> {
            try {
                String libDir = getApplicationInfo().nativeLibraryDir;
                java.io.File libTar = new java.io.File(libDir, "libtar.so");

                String abi = android.os.Build.SUPPORTED_ABIS[0];
                String tarGzName;
                if (abi.startsWith("arm64")) tarGzName = "files.default.aarch64.tar.gz.so";
                else if (abi.startsWith("armeabi")) tarGzName = "files.default.arm.tar.gz.so";
                else if (abi.startsWith("x86_64")) tarGzName = "files.default.x86_64.tar.gz.so";
                else if (abi.startsWith("x86")) tarGzName = "files.default.i686.tar.gz.so";
                else {
                    Logger.logError(LOG_TAG, "Unsupported ABI: " + abi);
                    runOnUiThread(() -> { try { progressDialog.dismiss(); } catch (Exception ignored) {} whenDone.run(); });
                    return;
                }

                java.io.File cacheDir = getCacheDir();
                java.io.File tmpTarGz = new java.io.File(cacheDir, tarGzName);
                java.io.InputStream in = getAssets().open(tarGzName);
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmpTarGz);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                in.close(); out.close();

                String destDir = getApplicationContext().getFilesDir().getParent();
                String[] extractCmd = {libTar.getAbsolutePath(), "-xf", tmpTarGz.getAbsolutePath(), "-C", destDir};
                Process extractProc = Runtime.getRuntime().exec(extractCmd);
                int extractExit = extractProc.waitFor();

                if (extractExit != 0) {
                    tmpTarGz.delete();
                    runOnUiThread(() -> {
                        try { progressDialog.dismiss(); } catch (Exception ignored) {}
                        try { Toast.makeText(LocalShellTestActivity.this, "环境安装失败 (exit=" + extractExit + ")", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                        whenDone.run();
                    });
                    return;
                }

                tmpTarGz.delete();
                String binDir = LocalShellConstants.BIN_DIR_PATH;
                Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "-R", "755", binDir}).waitFor();

                new java.io.File(LocalShellConstants.HOME_DIR_PATH).mkdirs();
                new java.io.File(LocalShellConstants.TMP_DIR_PATH).mkdirs();

                runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Exception ignored) {}
                    try { Toast.makeText(LocalShellTestActivity.this, "环境安装完成", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                    whenDone.run();
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "TAR extraction error", e);
                runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Exception ignored) {}
                    try { Toast.makeText(LocalShellTestActivity.this, "环境安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                    whenDone.run();
                });
            }
        }).start();
    }
}
