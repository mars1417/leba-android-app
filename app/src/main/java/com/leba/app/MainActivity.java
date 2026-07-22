/**
 * 乐吧公益中心 · Android WebView App
 *
 * 入口URL自动回退机制：
 *   ① GP Pages主入口（海外/墙外用户直通）
 *   ② cpolar隧道备用（国内用户/GP被墙时自动回退）
 *
 * 自动更新：通过cpolar隧道检查新版本 → 下载 → 覆盖安装
 */

package com.leba.app;

import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
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
    private int fallbackIndex = 0;
    private NotificationBridge notifBridge;
    private String currentUpdateBase = null;
    private boolean updateCheckDone = false;

    // 多入口URL，按优先级排列
    private static final String[] ENTRY_URLS = {
        "https://mars1417.github.io/lebacenter/",   // 0: GP Pages（乐吧入口，HTTPS优先）
        "https://639b13a9.r18.vip.cpolar.cn/"       // 1: cpolar隧道（国内备用）
    };

    private static final String CHANNEL_ID = "leba_notifications";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;

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

        notifBridge = new NotificationBridge(this);
        webView.addJavascriptInterface(notifBridge, "AndroidNotif");

        // 加载入口页（失败自动回退下一个URL）
        webView.clearCache(true);
        fallbackIndex = 0;
        loadCurrentUrl();

        // 检查更新（后台线程）
    }

    private void loadCurrentUrl() {
        if (fallbackIndex >= ENTRY_URLS.length) {
            Log.w("EntryUrl", "All entry URLs exhausted");
            return;
        }
        String url = ENTRY_URLS[fallbackIndex] + "?_t=" + System.currentTimeMillis();
        Log.d("EntryUrl", "Trying: " + url);
        webView.loadUrl(url);
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
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    int errCode = error.getErrorCode();
                    Log.w("EntryUrl", "Error loading " + request.getUrl() + " code=" + errCode);
                    // 主框架加载失败 → 自动切下一个入口URL
                    fallbackIndex++;
                    loadCurrentUrl();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectNotifBridgeJS(view);

                // 自动更新：从成功加载的URL提取host用于更新检查
                if (currentUpdateBase == null && !updateCheckDone) {
                    try {
                        java.net.URL parsed = new java.net.URL(url);
                        String host = parsed.getHost();
                        if (host != null && !host.contains("github")) {
                            int port = parsed.getPort();
                            currentUpdateBase = parsed.getProtocol() + "://" + host;
                            if (port > 0 && port != 443) currentUpdateBase += ":" + port;
                        }
                    } catch (Exception e) {}
                    checkForUpdate();
                }
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
                startActivityForResult(Intent.createChooser(intent, "选择照片"), 1001);
                return true;
            }
        });
    }

    /* ===== 自动更新 ===== */
    private void checkForUpdate() {
        executor.execute(() -> {
            try {
                if (updateCheckDone) return;
                updateCheckDone = true;
                int currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                Log.d("AutoUpdate", "Current versionCode: " + currentVer);

                String base = currentUpdateBase != null ? currentUpdateBase : "https://639b13a9.r18.vip.cpolar.cn";
                String checkUrl = base + "/api/apk/check";
                Log.d("AutoUpdate", "Check URL: " + checkUrl);

                URL url = new URL(checkUrl);
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

                String base2 = currentUpdateBase != null ? currentUpdateBase : "https://639b13a9.r18.vip.cpolar.cn";
                URL url = new URL(base2 + "/api/apk/download");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int fileLength = conn.getContentLength();
                final String sizeStr = fileLength > 0
                    ? String.format("%.1f MB", fileLength / (1024f * 1024f))
                    : "未知大小";

                try (InputStream is = conn.getInputStream();
                     FileOutputStream os = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    long lastUpdate = 0;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        total += n;
                        long now = System.currentTimeMillis();
                        if (fileLength > 0 && now - lastUpdate > 200) {
                            final int percent = (int) (total * 100 / fileLength);
                            lastUpdate = now;
                            mainHandler.post(() -> updateProgress(percent, sizeStr));
                        }
                    }
                }
                conn.disconnect();

                mainHandler.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
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

    private int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return 0;
        start += search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
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
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_CODE
                );
            }
        }
    }

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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && uploadMessage != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                Uri uri = data != null ? data.getData() : null;
                if (uri != null) results = new Uri[]{uri};
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
