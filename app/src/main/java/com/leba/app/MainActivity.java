package com.leba.app;

import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private NotificationBridge notifBridge;
    private static final String GP_URL = "https://mars1417.github.io/lebacenter/";
    private static final String CHANNEL_ID = "leba_notifications";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;
    // 自动更新：通过本地Flask服务器（cpolar穿透，国内可访问）
    private static final String UPDATE_HOST = "https://3d27347f.r23.cpolar.top";
    private static final String CHECK_URL = UPDATE_HOST + "/api/apk/check";
    private static final String DOWNLOAD_URL = UPDATE_HOST + "/api/apk/download";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private ValueCallback<Uri[]> uploadMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createNotificationChannel();
        requestNotificationPermission();

        webView = findViewById(R.id.webview);
        setupWebView();

        // 注册JS桥接：页面JS→Android系统通知
        notifBridge = new NotificationBridge(this);
        webView.addJavascriptInterface(notifBridge, "AndroidNotif");

        // 清除WebView缓存，确保看到最新GP页面
        webView.clearCache(true);
        webView.loadUrl(GP_URL + "?_t=" + System.currentTimeMillis());

        // 检查更新（后台线程）
        checkForUpdate();
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // 所有链接在WebView内打开
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectNotifBridgeJS(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(title);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");

                Intent chooser = Intent.createChooser(intent, "选择照片");
                startActivityForResult(chooser, 1001);
                return true;
            }
        });
    }

    /* ===== 自动更新 ===== */
    private void checkForUpdate() {
        executor.execute(() -> {
            try {
                // 获取当前版本
                int currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                Log.d("AutoUpdate", "Current versionCode: " + currentVer);

                // 请求本地服务器版本信息
                URL url = new URL(CHECK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.d("AutoUpdate", "Server returned " + code);
                    conn.disconnect();
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
                }
                conn.disconnect();

                String json = sb.toString();
                int remoteVer = extractInt(json, "version_code");
                String versionName = extractString(json, "version_name");

                if (remoteVer == 0) {
                    Log.d("AutoUpdate", "Failed to parse version");
                    return;
                }

                Log.d("AutoUpdate", "Remote: " + remoteVer + " (" + versionName + ") Current: " + currentVer);

                if (remoteVer > currentVer) {
                    String finalTag = versionName != null ? versionName : "v" + remoteVer;
                    mainHandler.post(() -> showUpdateDialog(finalTag));
                }
            } catch (Exception e) {
                Log.d("AutoUpdate", "Check failed: " + e.getMessage());
            }
        });
    }

    private void showUpdateDialog(String tagName) {
        new AlertDialog.Builder(this)
            .setTitle("发现新版本 " + tagName)
            .setMessage("是否下载更新？\n更新后重启即可使用最新版本。")
            .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstall())
            .setNegativeButton("稍后再说", null)
            .show();
    }

    private void downloadAndInstall() {
        // 弹出进度条弹窗
        mainHandler.post(() -> {
            LinearLayout layout = new LinearLayout(this);
            layout.setPadding(60, 30, 60, 30);
            layout.setOrientation(LinearLayout.VERTICAL);

            progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgress(0);
            layout.addView(progressBar);

            progressText = new TextView(this);
            progressText.setText("准备下载...");
            progressText.setGravity(Gravity.CENTER);
            progressText.setPadding(0, 16, 0, 0);
            layout.addView(progressText);

            progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在下载更新...")
                .setView(layout)
                .setCancelable(false)
                .show();
        });

        executor.execute(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "updates");
                cacheDir.mkdirs();
                File apkFile = new File(cacheDir, "leba-center.apk");
                if (apkFile.exists()) apkFile.delete();

                URL url = new URL(DOWNLOAD_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int fileLength = conn.getContentLength();
                final String sizeStr = fileLength > 0
                    ? String.format("%.1f MB", fileLength / (1024f * 1024f))
                    : "未知大小";
                Log.d("AutoUpdate", "Download size: " + fileLength);

                try (InputStream is = conn.getInputStream();
                     FileOutputStream os = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    long lastUpdate = 0;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        total += n;
                        // 每200ms更新一次UI，避免频繁刷新
                        long now = System.currentTimeMillis();
                        if (fileLength > 0 && now - lastUpdate > 200) {
                            final int percent = (int) (total * 100 / fileLength);
                            final long speed = total / Math.max(1, (now - System.currentTimeMillis() + 200));
                            lastUpdate = now;
                            mainHandler.post(() -> updateProgress(percent, sizeStr));
                        }
                    }
                }
                conn.disconnect();

                final long finalSize = apkFile.length();
                Log.d("AutoUpdate", "Downloaded: " + finalSize + " bytes");

                mainHandler.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    // 下载完成后立即安装（签名一致，支持覆盖安装）
                    installApk(apkFile);
                });
            } catch (Exception e) {
                Log.d("AutoUpdate", "Download failed: " + e.getMessage());
                mainHandler.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    if (!isFinishing()) {
                        new AlertDialog.Builder(this)
                            .setTitle("下载失败")
                            .setMessage("请检查网络后重试\n" + e.getMessage())
                            .setPositiveButton("确定", null)
                            .show();
                    }
                });
            }
        });
    }

    private void updateProgress(int percent, String sizeStr) {
        if (progressBar != null) progressBar.setProgress(percent);
        if (progressText != null) {
            progressText.setText("已下载 " + percent + "%  /  总大小: " + sizeStr);
        }
    }

    private void installApk(File apkFile) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    /* 极简JSON解析（无Gson依赖） */
    private int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return 0;
        start += search.length();
        // 跳过空格
        while (start < json.length() && json.charAt(start) == ' ') start++;
        // 数字直到逗号或括号
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (NumberFormatException e) { return 0; }
    }

    private String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end == -1 ? null : json.substring(start, end);
    }

    /* ===== 通知系统 ===== */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "乐吧通知", NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("来自乐吧的通知消息");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_CODE
                );
            }
        }
    }

    /* 注入JS：独立轮询通知API */
    private void injectNotifBridgeJS(WebView view) {
        String js =
            "(function(){" +
            "  if(window.__notifBridgeDone) return;" +
            "  window.__notifBridgeDone=true;" +
            "  var _lastId=null;" +
            "  setInterval(function(){" +
            "    try{" +
            "      fetch('/api/notifications/poll',{cache:'no-store'})" +
            "      .then(function(r){return r.json()})" +
            "      .then(function(d){" +
            "        if(d&&d.id&&d.id!=_lastId&&window.AndroidNotif){" +
            "          _lastId=d.id;" +
            "          AndroidNotif.showNotification(d.title||'',d.body||'',d.id);" +
            "        }" +
            "      }).catch(function(){});" +
            "    }catch(e){}" +
            "  },15000);" +
            "  setInterval(function(){" +
            "    try{" +
            "      fetch('/api/notifications/unread-count',{cache:'no-store'})" +
            "      .then(function(r){return r.json()})" +
            "      .then(function(d){" +
            "        if(d&&typeof d.count!=='undefined'&&window.AndroidNotif){" +
            "          if(d.count>0) AndroidNotif.updateBadge(d.count);" +
            "          else AndroidNotif.clearBadge();" +
            "        }" +
            "      }).catch(function(){});" +
            "    }catch(e){}" +
            "  },30000);" +
            "})();";
        view.evaluateJavascript(js, null);
        Log.d("NotifBridge", "JS injected into: " + view.getUrl());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && uploadMessage != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    results = new Uri[]{uri};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
