/* ============================================================================
 * RIS Multi-Protocol Server (DICOM + Telnet + HTTP) - ANDROID VERSION
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 *
 * 2026-10-11 Hugo
 *
 * ============================================================================ */

package com.hugo.risserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

public class RisServerService extends Service {
    private static final String CHANNEL_ID = "ris_server_channel";
    private static final int NOTIF_ID = 1001;

    public class LocalBinder extends Binder {
        public RisServerService getService() {
            return RisServerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private RisDataStore dataStore;
    private DicomScpServer dicomServer;
    private TelnetHttpServer telnetHttpServer;
    private boolean serverRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        dataStore = new RisDataStore(this);
        dataStore.writeLog("RIS Server starting...");
        dataStore.writeLog(String.format(
                "Config: AET=%s TelnetPort=%d HttpPort=%d DicomPort=%d Timeout=%d DebugLog=%d",
                dataStore.aeCalled, dataStore.telnetPort, dataStore.httpPort,
                dataStore.dicomPort, dataStore.telnetTimeout, dataStore.debugLog));
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Starting..."));
        startServers();
    }

    public RisDataStore getDataStore() {
        return dataStore;
    }

    public boolean isServerRunning() {
        return serverRunning;
    }

    public synchronized void startServers() {
        if (serverRunning) return;
        dicomServer = new DicomScpServer(dataStore);
        telnetHttpServer = new TelnetHttpServer(dataStore);
        dicomServer.start();
        telnetHttpServer.start();
        serverRunning = true;
        dataStore.writeLog("Server STARTED");
        updateNotification();
        dataStore.notifyStatusChanged();
    }

    public synchronized void stopServers() {
        if (!serverRunning) return;
        if (dicomServer != null) dicomServer.stop();
        if (telnetHttpServer != null) telnetHttpServer.stop();
        serverRunning = false;
        dataStore.writeLog("Server STOPPED");
        updateNotification();
        dataStore.notifyStatusChanged();
    }

    public void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            String status = serverRunning
                    ? String.format("Running | HTTP:%d DICOM:%d Telnet:%d",
                            dataStore.httpPort, dataStore.dicomPort, dataStore.telnetPort)
                    : "Server STOPPED";
            nm.notify(NOTIF_ID, buildNotification(status));
        }
    }

    private Notification buildNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("RIS Multi-Protocol Server")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "RIS Server Status", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopServers();
        super.onDestroy();
    }
}