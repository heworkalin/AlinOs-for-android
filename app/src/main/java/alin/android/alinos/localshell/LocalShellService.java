package alin.android.alinos.localshell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import alin.android.alinos.R;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent foreground service for LocalShell sessions.
 * Extends TermuxService so that TermuxActivity's view clients can work with it.
 */
public class LocalShellService extends TermuxService {

    private static final String CHANNEL_ID = "localshell_service";
    private static final int NOTIFICATION_ID = 1;

    private final List<TermuxSession> mOwnSessions = new ArrayList<>();
    private final LocalBinder mBinder = new LocalBinder();
    private com.termux.terminal.TerminalSessionClient mSessionClient;

    @Override
    public void onCreate() {
        // Skip TermuxService.onCreate() — use our own session list
        setupNotificationChannel();
        Notification n = buildNotification();
        if (n != null) startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return this.mBinder;
    }

    @Override
    public void onDestroy() {
        for (TermuxSession s : new ArrayList<>(mOwnSessions)) {
            s.getTerminalSession().finishIfRunning();
        }
        mOwnSessions.clear();
        stopForeground(true);
        super.onDestroy();
    }

    // ── Session management overrides ──

    public synchronized boolean isTermuxSessionsEmpty() {
        return mOwnSessions.isEmpty();
    }

    public synchronized int getTermuxSessionsSize() {
        return mOwnSessions.size();
    }

    public synchronized List<TermuxSession> getTermuxSessions() {
        return mOwnSessions;  // Return the actual list for ArrayAdapter to observe
    }

    @Nullable
    public synchronized TermuxSession getTermuxSession(int index) {
        if (index >= 0 && index < mOwnSessions.size()) return mOwnSessions.get(index);
        return null;
    }

    @Nullable
    public synchronized TermuxSession getLastTermuxSession() {
        return mOwnSessions.isEmpty() ? null : mOwnSessions.get(mOwnSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession ts) {
        for (int i = 0; i < mOwnSessions.size(); i++) {
            if (mOwnSessions.get(i).getTerminalSession().equals(ts)) return i;
        }
        return -1;
    }

    @Nullable
    public synchronized TerminalSession getTerminalSessionForHandle(String handle) {
        for (TermuxSession s : mOwnSessions) {
            if (s.getTerminalSession().mHandle.equals(handle)) return s.getTerminalSession();
        }
        return null;
    }

    @Nullable
    public synchronized TermuxSession getTermuxSessionForTerminalSession(TerminalSession ts) {
        for (TermuxSession s : mOwnSessions) {
            if (s.getTerminalSession().equals(ts)) return s;
        }
        return null;
    }

    public synchronized int removeTermuxSession(TerminalSession ts) {
        int idx = getIndexOfSession(ts);
        if (idx >= 0) {
            mOwnSessions.get(idx).finish(); // triggers TermuxSessionClient which may remove from list
            // Re-check index after finish() callback
            int newIdx = getIndexOfSession(ts);
            if (newIdx >= 0) mOwnSessions.remove(newIdx);
        }
        return idx;
    }

    @Nullable
    public synchronized TermuxSession createTermuxSession(String executablePath, String[] arguments,
                                                           String stdin, String workingDirectory,
                                                           boolean isFailSafe, String sessionName) {
        String shellPath = executablePath != null ? executablePath : "/system/bin/sh";
        ExecutionCommand cmd = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            shellPath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe);
        cmd.shellName = sessionName;
        cmd.setShellCommandShellEnvironment = true;

        TermuxSession session = TermuxSession.execute(this, cmd,
            getTermuxTerminalSessionClient(), termuxSession -> {
                mOwnSessions.remove(termuxSession);
                updateNotification();
                com.termux.app.terminal.TermuxTerminalSessionActivityClient ac = getTermuxTerminalSessionActivityClient();
                if (ac != null) ac.termuxSessionListNotifyUpdated();
            }, new LocalShellEnvironment(), null, false);
        if (session != null) {
            mOwnSessions.add(session);
            updateNotification();
            // Notify session list
            com.termux.app.terminal.TermuxTerminalSessionActivityClient activityClient = getTermuxTerminalSessionActivityClient();
            if (activityClient != null) activityClient.termuxSessionListNotifyUpdated();
        }
        return session;
    }

    @Override
    public synchronized void setTermuxTerminalSessionClient(
            com.termux.app.terminal.TermuxTerminalSessionActivityClient client) {
        mSessionClient = client;
        // Also update own sessions
        for (TermuxSession s : new ArrayList<>(mOwnSessions)) {
            s.getTerminalSession().updateTerminalSessionClient(client);
        }
    }

    // Expose for session list notification
    public synchronized com.termux.app.terminal.TermuxTerminalSessionActivityClient getTermuxTerminalSessionActivityClient() {
        if (mSessionClient instanceof com.termux.app.terminal.TermuxTerminalSessionActivityClient)
            return (com.termux.app.terminal.TermuxTerminalSessionActivityClient) mSessionClient;
        return null;
    }

    @Override
    public synchronized void unsetTermuxTerminalSessionClient() {
        mSessionClient = null;
    }

    // Override getTermuxTerminalSessionClient to return our session client (avoids null mShellManager path)
    @Override
    public synchronized com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {
        if (mSessionClient instanceof com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase)
            return (com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase) mSessionClient;
        return super.getTermuxTerminalSessionClient();
    }

    @Override
    public boolean wantsToStop() {
        return false;
    }

    @Override
    public TermuxSession getTermuxSessionForShellName(String name) {
        for (TermuxSession s : mOwnSessions) {
            String shellName = s.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name)) return s;
        }
        return null;
    }

    // ── Notification (points to LocalShellTestActivity) ──

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "LocalShell", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("LocalShell PTY service");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent();
        intent.setClassName(this, "alin.android.alinos.dev.LocalShellTestActivity");
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT);

        int count = getTermuxSessionsSize();
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setContentTitle("LocalShell")
            .setContentText(count + " session" + (count == 1 ? "" : "s"))
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setColor(0xFF607D8B)
            .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    // ── Static ──

    public static void start(Context context) {
        Intent intent = new Intent(context, LocalShellService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, LocalShellService.class));
    }
}
