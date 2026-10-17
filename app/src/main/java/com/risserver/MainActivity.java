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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity implements RisDataStore.LogListener {
    private RisServerService service;
    private boolean bound = false;

    private TextView txtStatus;
    private TextView txtConsole;
    private ScrollView scrollConsole;
    private Button btnStartStop;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RisServerService.LocalBinder lb = (RisServerService.LocalBinder) binder;
            service = lb.getService();
            bound = true;
            service.getDataStore().setLogListener(MainActivity.this);
            onLogUpdated(service.getDataStore().getLogsText());
            refreshStatusUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildProgrammaticUI();

        Intent serviceIntent = new Intent(this, RisServerService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private void buildProgrammaticUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(24, 24, 24, 24);

        // Title Banner
        TextView title = new TextView(this);
        title.setText("RIS Multi-Protocol Server");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.parseColor("#009879"));
        title.setPadding(24, 20, 24, 20);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Status Box
        txtStatus = new TextView(this);
        txtStatus.setText("Starting RIS Server...");
        txtStatus.setTextSize(13);
        txtStatus.setTextColor(Color.parseColor("#80CBC4"));
        txtStatus.setBackgroundColor(Color.parseColor("#1E1E1E"));
        txtStatus.setPadding(24, 18, 24, 18);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, 16, 0, 16);
        root.addView(txtStatus, statusLp);

        // Action Bar (Start/Stop, Web Manager, Settings, Exit)
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setWeightSum(4f);

        btnStartStop = createActionButton("Stop", "#2E7D32");
        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!bound) return;
                if (service.isServerRunning()) service.stopServers();
                else service.startServers();
                refreshStatusUI();
            }
        });

        Button btnWeb = createActionButton("Web UI", "#1976D2");
        btnWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWebManagerDialog();
            }
        });

        Button btnSettings = createActionButton("Settings", "#455A64");
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        Button btnExit = createActionButton("Exit", "#C62828");
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) {
                    service.stopServers();
                    unbindService(connection);
                    bound = false;
                }
                stopService(new Intent(MainActivity.this, RisServerService.class));
                finishAffinity();
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.setMargins(6, 0, 6, 0);
        btnRow.addView(btnStartStop, btnLp);
        btnRow.addView(btnWeb, btnLp);
        btnRow.addView(btnSettings, btnLp);
        btnRow.addView(btnExit, btnLp);
        root.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Console Log Header + Clear button
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setPadding(4, 20, 4, 8);

        TextView lblLog = new TextView(this);
        lblLog.setText("Console Output:");
        lblLog.setTextColor(Color.parseColor("#B0BEC5"));
        lblLog.setTypeface(Typeface.DEFAULT_BOLD);
        logHeader.addView(lblLog, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnClear = new TextView(this);
        btnClear.setText("[Clear Log]");
        btnClear.setTextColor(Color.parseColor("#FF8A65"));
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) service.getDataStore().clearLogs();
            }
        });
        logHeader.addView(btnClear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logHeader);

        // Console Log Scroller
        scrollConsole = new ScrollView(this);
        scrollConsole.setBackgroundColor(Color.parseColor("#0A0A0A"));
        txtConsole = new TextView(this);
        txtConsole.setTypeface(Typeface.MONOSPACE);
        txtConsole.setTextSize(11);
        txtConsole.setTextColor(Color.parseColor("#00E676"));
        txtConsole.setPadding(16, 16, 16, 16);
        scrollConsole.addView(txtConsole);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollConsole, scrollLp);

        setContentView(root);
    }

    private Button createActionButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hexColor));
        return b;
    }

    private void refreshStatusUI() {
        if (!bound || service == null) return;
        RisDataStore ds = service.getDataStore();
        boolean running = service.isServerRunning();
        String ip = getLocalIpAddress();

        String status = String.format(Locale.US,
                "State: %s  |  LAN IP: %s  |  AET: %s\n" +
                "HTTP: :%d  |  Telnet: :%d  |  DICOM: :%d  |  Worklist Rows: %d\n" +
                "Data Folder: %s",
                running ? "RUNNING" : "STOPPED",
                ip, ds.aeCalled, ds.httpPort, ds.telnetPort, ds.dicomPort,
                ds.getPatientRowCount(), ds.dataDir.getAbsolutePath());

        txtStatus.setText(status);
        btnStartStop.setText(running ? "Stop" : "Start");
        btnStartStop.setBackgroundColor(Color.parseColor(running ? "#2E7D32" : "#C62828"));
        service.updateNotification();
    }

    // ===== Built-in WebView Modal for Worklist Manager =====
    private void showWebManagerDialog() {
        if (!bound || !service.isServerRunning()) {
            return;
        }
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#009879"));
        topBar.setPadding(20, 12, 20, 12);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("RIS Worklist Web Manager");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClose = new Button(this);
        btnClose.setText("Close");
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                refreshStatusUI();
            }
        });
        topBar.addView(btnClose);
        layout.addView(topBar);

        WebView webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setCancelable(false).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setCancelable(false).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(message)
                        .setView(input)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(input.getText().toString()); }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setCancelable(false).show();
                return true;
            }
        });

        webView.loadUrl("http://127.0.0.1:" + service.getDataStore().httpPort + "/");
        layout.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg.setContentView(layout);
        dlg.show();
    }

    // ===== Settings Dialog (Port of SettingsWndProc) =====
    private void showSettingsDialog() {
        if (!bound) return;
        final RisDataStore ds = service.getDataStore();

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(40, 24, 40, 24);

        final EditText edtAet = addSettingField(form, "AET Title:", ds.aeCalled, false);
        final EditText edtTelnet = addSettingField(form, "Telnet Port:", String.valueOf(ds.telnetPort), true);
        final EditText edtHttp = addSettingField(form, "HTTP Port:", String.valueOf(ds.httpPort), true);
        final EditText edtDicom = addSettingField(form, "DICOM Port:", String.valueOf(ds.dicomPort), true);
        final EditText edtTimeout = addSettingField(form, "Timeout (sec):", String.valueOf(ds.telnetTimeout), true);
        final EditText edtDelPwd = addSettingField(form, "Delete Password:", ds.delPwd, false);
        final EditText edtOmitted = addSettingField(form, "Omitted DICOM Tags:", ds.omittedFields, false);

        final CheckBox chkDebug = new CheckBox(this);
        chkDebug.setText("Enable Debug Log");
        chkDebug.setChecked(ds.debugLog == 1);
        form.addView(chkDebug);

        scroll.addView(form);

        new AlertDialog.Builder(this)
                .setTitle("Server Settings")
                .setView(scroll)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean wasRunning = service.isServerRunning();
                        if (wasRunning) service.stopServers();

                        ds.setAeTitle(edtAet.getText().toString());
                        try { ds.telnetPort = Integer.parseInt(edtTelnet.getText().toString().trim()); } catch (Exception ignored) {}
                        try { ds.httpPort = Integer.parseInt(edtHttp.getText().toString().trim()); } catch (Exception ignored) {}
                        try { ds.dicomPort = Integer.parseInt(edtDicom.getText().toString().trim()); } catch (Exception ignored) {}
                        try { ds.telnetTimeout = Integer.parseInt(edtTimeout.getText().toString().trim()); } catch (Exception ignored) {}
                        ds.delPwd = edtDelPwd.getText().toString().trim();
                        ds.omittedFields = edtOmitted.getText().toString().trim();
                        ds.debugLog = chkDebug.isChecked() ? 1 : 0;

                        ds.saveConfig();
                        if (wasRunning) service.startServers();
                        refreshStatusUI();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText addSettingField(LinearLayout parent, String label, String val, boolean numeric) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(val);
        et.setSingleLine(true);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(et);
        return et;
    }

    @Override
    public void onLogUpdated(final String fullLog) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                txtConsole.setText(fullLog);
                scrollConsole.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollConsole.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    @Override
    public void onStatusChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshStatusUI();
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface intf = en.nextElement();
                Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            service.getDataStore().setLogListener(null);
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}