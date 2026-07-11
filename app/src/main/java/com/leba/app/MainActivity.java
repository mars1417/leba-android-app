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
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
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
    private static final String GITHUB_API = "https://api.github.com/repos/mars1417/leba-android-app/releases/latest";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);

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
        });
    }

    /* ===== 自动更新 ===== */
    private void checkForUpdate() {
        executor.execute(() -> {
            try {
                // 获取当前版本
                int currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                Log.d("AutoUpdate", "Current versionCode: " + currentVer);

                // 请求GitHub API
                URL url = new URL(GITHUB_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "leba-android");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.d("AutoUpdate", "GitHub API returned " + code);
                    conn.disconnect();
                    return;
                }

                // 解析JSON
                StringBuilder sb = new StringBuilder();
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
                }
                conn.disconnect();

                String json = sb.toString();
                String tagName = extractJsonString(json, "tag_name");
                String downloadUrl = extractDownloadUrl(json);

                if (tagName == null || downloadUrl == null) {
                    Log.d("AutoUpdate", "Failed to parse GitHub response");
                    return;
                }

                // 从tag名提取版本号 (v12 -> 12)
                int remoteVer = 0;
                try {
                    remoteVer = Integer.parseInt(tagName.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    Log.d("AutoUpdate", "Invalid tag: " + tagName);
                    return;
                }

                Log.d("AutoUpdate", "Remote: " + remoteVer + " Current: " + currentVer);

                // 有新版 → 弹窗提示
                if (remoteVer > currentVer) {
                    String finalTag = tagName;
                    String finalUrl = downloadUrl;
                    mainHandler.post(() -> showUpdateDialog(finalTag, finalUrl));
                }
            } catch (Exception e) {
                Log.d("AutoUpdate", "Check failed: " + e.getMessage());
            }
        });
    }

    private void showUpdateDialog(String tagName, String downloadUrl) {
        new AlertDialog.Builder(this)
            .setTitle("发现新版本 " + tagName)
            .setMessage("是否下载更新？\n更新后重启即可使用最新版本。")
            .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstall(downloadUrl))
            .setNegativeButton("稍后再说", null)
            .show();
    }

    private void downloadAndInstall(String downloadUrl) {
        executor.execute(() -> {
            try {
                // 下载APK到缓存目录
                File cacheDir = new File(getCacheDir(), "updates");
                cacheDir.mkdirs();
                File apkFile = new File(cacheDir, "leba-center.apk");
                if (apkFile.exists()) apkFile.delete();

                URL url = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);

                try (InputStream is = conn.getInputStream();
                     FileOutputStream os = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                }
                conn.disconnect();

                Log.d("AutoUpdate", "Downloaded: " + apkFile.length() + " bytes");

                // 触发安装
                mainHandler.post(() -> installApk(apkFile));
            } catch (Exception e) {
                Log.d("AutoUpdate", "Download failed: " + e.getMessage());
                mainHandler.post(() -> {
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

    private void installApk(File apkFile) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    /* 极简JSON解析（无Gson依赖） */
    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end == -1 ? null : json.substring(start, end);
    }

    private String extractDownloadUrl(String json) {
        // 找第一个browser_download_url
        String search = "\"browser_download_url\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end == -1 ? null : json.substring(start, end).replace("\\/", "/");
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
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
