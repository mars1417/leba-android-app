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
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private int fallbackIndex = 0;
    private NotificationBridge notifBridge;
    private String currentUpdateBase = null;
    // 🔴 v56: 本地HTTP服务器（内置开场视频根治方案——appassets假域名在国产ROM MediaPlayer直连必挂）
    private IntroHttpServer introServer;
    private boolean updateCheckDone = false;
    private boolean skipUpdateDialog = false;

    // 多入口URL，按优先级排列
    private static final String[] ENTRY_URLS = {
        "https://mars1417.github.io/lebacenter/",   // 0: GP Pages（乐吧入口，HTTPS优先）
        "https://leba-website.vip.cpolar.cn/"       // 1: cpolar隧道（国内备用，2026-08-04更新为活跃隧道）
    };
    // 2026-08-03: 隧道地址动态化——启动时从GP拉取url.json获取当前隧道，不再写死
    // 好处：cpolar免费版隧道重启域名会变，APK无需重新打包也能找到最新服务器
    private static final String URL_JSON_ENDPOINT = "https://mars1417.github.io/lebacenter/url.json";

    private static final String CHANNEL_ID = "leba_notifications";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private ValueCallback<Uri[]> uploadMessage;
    // 2026-08-03 本地assets视频加载器（官方WebViewAssetLoader，支持Range/206媒体流）
    private androidx.webkit.WebViewAssetLoader assetLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createNotificationChannel();
        requestNotificationPermission();

        webView = findViewById(R.id.webview);
        // 🔴 v56: 启动本地HTTP服务器（127.0.0.1）——内置开场视频播放根治方案
        //   appassets假域名在国产ROM MediaPlayer直连管线必挂(错误4 Format error)，
        //   127.0.0.1真实地址+标准HTTP任何ROM都能播
        introServer = new IntroHttpServer(this);
        introServer.start();
        // 🔴 v56: 增量更新——静默检查服务器intro-version.json，有新版视频下载到缓存目录
        //   （本地服务器优先服务缓存 → 换视频=服务器更新=APK自动拉取=零重装，Boss要求不整包安装）
        checkIntroVideoUpdate();
        // 2026-08-03 初始化assetLoader：https://appassets.androidplatform.net/assets/ → APK内置assets/
        assetLoader = new androidx.webkit.WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        setupWebView();

        notifBridge = new NotificationBridge(this);
        webView.addJavascriptInterface(notifBridge, "AndroidNotif");

        // 方案B：启动先检查更新 → 无新版直接进入系统；有新版弹窗（立即更新/稍后）
        // 2026-07-31 用户要求：打开APP优先更新，更新完再进系统
        // 2026-08-03 修复卡顿：不再clearCache(true)（会清掉视频缓存导致每次重下3.9MB），
        //   页面用?_t=时间戳保证最新，视频文件走HTTP缓存自动复用
        // 2026-08-03 v38 修复第二次没动画：清缓存只清WebView页面缓存，不动内置assets视频。
        //   旧页面缓存的JS可能选错视频源 → 动画消失。每次启动清一次保证加载最新入口页。
        webView.clearCache(true);
        webView.clearHistory();
        fallbackIndex = 0;
        checkForUpdate();
    }

    private void loadCurrentUrl() {
        if (fallbackIndex >= ENTRY_URLS.length) {
            Log.w("EntryUrl", "All entry URLs exhausted");
            loadErrorPage();
            return;
        }
        String url = ENTRY_URLS[fallbackIndex] + "?_t=" + System.currentTimeMillis() + "&apk=1";
        Log.d("EntryUrl", "Trying: " + url);
        webView.loadUrl(url);
    }

    /** 双入口都失败时显示友好错误页（2026-08-01 v30 天使E007建议） */
    private void loadErrorPage() {
        String html = "<html><body style='background:#f5f5f7;text-align:center;padding-top:120px;font-family:sans-serif;'>"
            + "<div style='font-size:64px;'>⚠️</div>"
            + "<h2 style='color:#1d1d1f;margin:16px 0 8px;'>网络连接失败</h2>"
            + "<p style='color:#86868b;font-size:16px;margin-bottom:32px;'>请检查网络后重试</p>"
            + "<a href='leba-retry://' style='display:inline-block;background:#007AFF;color:#fff;padding:12px 48px;border-radius:24px;text-decoration:none;font-size:16px;'>重试</a>"
            + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        // 🔴 v52修复【Boss实测v51闪退跳登录】：appassets在部分手机(国产ROM)WebViewAssetLoader
        //   拦截失败→内置视频onerror→go()闪退。终极方案：放开file://直读android_asset，
        //   JS里appassets失败自动切 file:///android_asset/ 本地直读，100%可靠不依赖拦截器
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(true);
        // 视频播放优化（2026-08-03 修复APK开机动画卡顿）：
        // 1) 允许自动播放（无需用户手势）
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // 2) 渲染优先级高
        webView.getSettings().setRenderPriority(WebSettings.RenderPriority.HIGH);
        // 3) 缓存模式：默认（视频文件可被HTTP缓存，避免每次启动重下3.9MB）
        webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        // 🔴 v56: 页面是https，本地视频服务器是http://127.0.0.1 → 必须放行混合内容
        //   （loopback地址在Chromium属可信源，但老WebView/国产ROM需显式放行）
        webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 🔴 v56: 明文流量放行127.0.0.1（Android 9+默认禁明文HTTP，本地服务器需要）
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(true);
        // 2026-08-03 v41修复黑屏：不再强制setLayerType(HARDWARE)！
        //   强制硬件层在部分国产ROM上导致WebView视频surface合成冲突→画面黑屏但播放正常。
        //   WebView默认自动硬件加速，无需手动setLayerType。

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 2026-08-03 v41修复：onPageFinished注入太晚（页面JS预加载已执行完→选错源）。
                //   提前到onPageStarted注入，确保页面任何JS在读取标记前它已就绪。
                view.evaluateJavascript("window.__isLebaApk = true;", null);
                // 2026-08-03 v46设备分级：探测本机硬解能力注入档位，网页JS据此选内置视频
                view.evaluateJavascript("window.__videoTier = \"" + detectVideoTier() + "\";", null);
                // 🔴 v56: 注入本地HTTP服务器地址——JS首选它播放内置视频(任何ROM都能播)
                try {
                    view.evaluateJavascript(
                        "window.__introLocal = function(f){ return 'http://127.0.0.1:" + introServer.getPort() +
                        "/intro/" + introServer.getToken() + "/' + f; };",
                        null);
                } catch (Exception e) {
                    Log.w("IntroServer", "inject local url failed: " + e.getMessage());
                }
            }

            /** v46: 探测H.264硬解能力 → 返回视频档位 high/mid/low（开机动画设备分级） */
            private String detectVideoTier() {
                try {
                    MediaCodecList mcl = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                    MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920);
                    f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                    f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
                    boolean highOk = mcl.findDecoderForFormat(f) != null;
                    if (highOk) return "high";
                    MediaFormat f2 = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920);
                    f2.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain);
                    f2.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
                    boolean midOk = mcl.findDecoderForFormat(f2) != null;
                    return midOk ? "mid" : "low";
                } catch (Exception e) {
                    Log.w("VideoTier", "detectVideoTier fallback mid: " + e.getMessage());
                    return "mid";
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 错误页重试按钮：leba-retry:// → 重置入口重新加载
                String url = request.getUrl().toString();
                if (url != null && url.startsWith("leba-retry://")) {
                    fallbackIndex = 0;
                    loadCurrentUrl();
                    return true;
                }
                return false;
            }

            // 2026-08-03 本地高清视频：官方WebViewAssetLoader（支持HTTP Range/206分段请求，
            // MediaPlayer才能流式播放内置assets视频；手动拦截返回200整文件会被播放器拒绝）
            // 🔴 v52修复【Boss实测v51闪退跳登录】：
            //   appassets在部分手机(国产ROM WebView)WebViewAssetLoader拦截失败 → video onerror → go()闪退。
            //   file:// 直读又被Chromium安全策略拒绝(HTTPS页面禁加载file://媒体,错误4 URL safety check)。
            //   终极方案：Java层【手动拦截】appassets URL → 直接从assets读流 + 完整Range/206支持，
            //   不依赖WebViewAssetLoader，100%可控。assetLoader仍作第一尝试(兼容正常手机)。
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 1) 先试官方 assetLoader（正常手机直接走这个）
                try {
                    android.webkit.WebResourceResponse resp = assetLoader.shouldInterceptRequest(request.getUrl());
                    if (resp != null) return resp;
                } catch (Exception e) {
                    Log.w("LocalVideo", "assetLoader failed: " + e.getMessage());
                }
                // 2) 手动兜底：appassets URL → 本地assets读取 + Range/206
                if (url != null && url.startsWith("https://appassets.androidplatform.net/assets/")) {
                    try {
                        String assetPath = url.substring("https://appassets.androidplatform.net/assets/".length());
                        // 解析 Range 头（MediaPlayer 会发 bytes=0- 或 bytes=N- 分段请求）
                        long start = 0;
                        long end = -1;
                        String range = request.getRequestHeaders().get("Range");
                        if (range != null && range.startsWith("bytes=")) {
                            String[] parts = range.substring(6).split("-");
                            try { start = Long.parseLong(parts[0]); } catch (Exception ignore) {}
                            if (parts.length > 1 && !parts[1].isEmpty()) {
                                try { end = Long.parseLong(parts[1]); } catch (Exception ignore) {}
                            }
                        }
                        // 打开 assets 文件
                        AssetFileDescriptor afd = getAssets().openFd(assetPath);
                        long fileLen = afd.getLength();
                        if (end < 0 || end >= fileLen) end = fileLen - 1;
                        long contentLen = end - start + 1;
                        // 🔴 v55修复【Boss实测Format error根因】：getFileDescriptor()是整个APK文件的fd，
                        //   必须用 afd.getStartOffset() 跳到asset实际偏移（v54已修），
                        //   【且必须用LimitedInputStream严格截断长度】！MediaPlayer请求bytes=0-1023探测时，
                        //   流只能返回1024字节——否则读到APK文件其余部分(混入其他文件数据) → Format error！
                        //   官方WebViewAssetLoader内部就是LimitedInputStream截断，之前漏了这步。
                        java.io.FileInputStream fis = new java.io.FileInputStream(afd.getFileDescriptor());
                        long absOffset = afd.getStartOffset() + start;
                        long skipped = 0;
                        while (skipped < absOffset) {
                            long s = fis.skip(absOffset - skipped);
                            if (s <= 0) break;
                            skipped += s;
                        }
                        InputStream limited = new LimitedInputStream(fis, contentLen);
                        // 构造206响应（Range请求）或200（无Range）
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Accept-Ranges", "bytes");
                        headers.put("Access-Control-Allow-Origin", "*");
                        headers.put("Content-Length", String.valueOf(contentLen));
                        if (range != null && range.startsWith("bytes=")) {
                            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + fileLen);
                            return new android.webkit.WebResourceResponse("video/mp4", null, 206, "Partial Content", headers, limited);
                        } else {
                            return new android.webkit.WebResourceResponse("video/mp4", null, 200, "OK", headers, limited);
                        }
                    } catch (Exception e) {
                        Log.w("LocalVideo", "manual intercept failed: " + e.getMessage());
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    int errCode = error.getErrorCode();
                    Log.w("EntryUrl", "Error loading " + request.getUrl() + " code=" + errCode);
                    // 主框架加载失败 → 自动切下一个入口URL；全部耗尽 → 友好错误页
                    fallbackIndex++;
                    if (fallbackIndex >= ENTRY_URLS.length) {
                        loadErrorPage();
                    } else {
                        loadCurrentUrl();
                    }
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
                // 2026-08-03 注入APK专属标记：网页JS用它区分APK环境（不用UA猜，微信UA也会误判）
                view.evaluateJavascript("window.__isLebaApk = true;", null);

                // 从成功加载的URL提取host（供后续下载使用；更新检查已在启动时完成）
                if (currentUpdateBase == null) {
                    try {
                        java.net.URL parsed = new java.net.URL(url);
                        String host = parsed.getHost();
                        if (host != null && !host.endsWith("github.io")) {
                            int port = parsed.getPort();
                            currentUpdateBase = parsed.getProtocol() + "://" + host;
                            if (port > 0 && port != 443) currentUpdateBase += ":" + port;
                        }
                    } catch (Exception e) {}
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

                // ✅ 使用网页 accept 属性动态生成 Intent（支持图片/视频/音频等所有类型）
                // 旧代码写死 image/* 导致视频永远无法选择 —— 2026-07-31 修复
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    // 兜底：通用文件选择器
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), 1001);
                } catch (Exception e) {
                    // 最后兜底：ACTION_GET_CONTENT 全类型
                    if (uploadMessage != null) {
                        uploadMessage.onReceiveValue(null);
                        uploadMessage = null;
                    }
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("*/*");
                    fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
                    startActivityForResult(Intent.createChooser(fallback, "选择文件"), 1001);
                }
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
                if (skipUpdateDialog) {
                    // 用户点了「本次不再提醒」→ 本次进程内不再检查弹窗
                    mainHandler.post(() -> loadCurrentUrl());
                    return;
                }
                int currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                Log.d("AutoUpdate", "Current versionCode: " + currentVer);

                // 2026-08-03: 动态获取当前隧道地址（cpolar免费版域名会变，不再写死）
                // 先从GP拉url.json拿最新地址，失败才用内置默认值
                String base = currentUpdateBase;
                if (base == null) {
                    base = fetchCurrentTunnelUrl();
                    if (base == null || base.isEmpty()) {
                        base = "https://leba-website.vip.cpolar.cn";
                    }
                }
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
                    // 2026-08-03 修复白屏：check失败也必须进系统，更新检查不能阻塞入口
                    mainHandler.post(() -> loadCurrentUrl());
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
                    // 2026-08-03 修复白屏：解析失败也必须进系统
                    mainHandler.post(() -> loadCurrentUrl());
                    return;
                }

                Log.d("AutoUpdate", "Remote: " + remoteVer + " (" + versionName + ") Current: " + currentVer);

                if (remoteVer > currentVer) {
                    String finalTag = versionName != null ? versionName : "v" + remoteVer;
                    mainHandler.post(() -> showUpdateDialog(finalTag));
                } else {
                    // 无新版 → 直接进入系统
                    mainHandler.post(() -> loadCurrentUrl());
                }
            } catch (Exception e) {
                Log.d("AutoUpdate", "Check failed: " + e.getMessage());
                // 检查失败（网络/服务器不可达）→ 直接进入系统，不阻塞
                mainHandler.post(() -> loadCurrentUrl());
            }
        });
    }

    private void showUpdateDialog(String tagName) {
        new AlertDialog.Builder(this)
            .setTitle("发现新版本 " + tagName)
            .setMessage("是否立即更新？\n更新后自动重启进入系统。")
            .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstall())
            .setNegativeButton("稍后再说", (dialog, which) -> loadCurrentUrl())
            .setNeutralButton("本次不再提醒", (dialog, which) -> {
                skipUpdateDialog = true;
                loadCurrentUrl();
            })
            .setCancelable(false)
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

                String base2 = currentUpdateBase;
                if (base2 == null) {
                    base2 = fetchCurrentTunnelUrl();
                    if (base2 == null || base2.isEmpty()) {
                        base2 = "https://leba-website.vip.cpolar.cn";
                    }
                }
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
                    long startTime = System.currentTimeMillis();
                    long lastBytes = 0;
                    long lastTime = startTime;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        total += n;
                        long now = System.currentTimeMillis();
                        if (fileLength > 0 && now - lastUpdate > 200) {
                            final int percent = (int) (total * 100 / fileLength);
                            // 真实网速: 窗口期字节差/时间差 (200ms采样)
                            long winBytes = total - lastBytes;
                            long winMs = now - lastTime;
                            lastBytes = total;
                            lastTime = now;
                            final String speedStr = formatSpeed(winBytes, winMs);
                            lastUpdate = now;
                            // 捕获当前total（lambda需要effectively final）
                            final long fTotal = total;
                            final long fStart = startTime;
                            mainHandler.post(() -> updateProgress(percent, sizeStr, speedStr, fTotal, fStart));
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

    private void updateProgress(int percent, String sizeStr, String speedStr, long totalBytes, long startTime) {
        if (progressBar != null) progressBar.setProgress(percent);
        if (progressText != null) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            String elapsedStr = formatElapsed(elapsedMs);
            String doneStr = totalBytes > 0
                ? String.format("%.1f MB", totalBytes / (1024f * 1024f)) + " / " + sizeStr
                : sizeStr;
            progressText.setText("已下载 " + percent + "%   " + doneStr + "\n"
                + "⚡ " + speedStr + "   ⏱ " + elapsedStr);
        }
    }

    /** 真实网速格式化: B/s → KB/s → MB/s */
    private String formatSpeed(long bytes, long ms) {
        if (ms <= 0) return "计算中...";
        double bps = bytes * 1000.0 / ms;
        if (bps >= 1024 * 1024) return String.format("%.1f MB/s", bps / (1024 * 1024));
        if (bps >= 1024) return String.format("%.0f KB/s", bps / 1024);
        return String.format("%.0f B/s", bps);
    }

    /** 已用时间: mm:ss */
    private String formatElapsed(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void installApk(File apkFile) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    /**
     * 🔴 v56: 增量更新（Boss要求：不整包重装，推送替换指定部分）
     * 静默检查服务器 intro-version.json → 有新版视频下载到 cacheDir/intro/ →
     * 本地HTTP服务器优先服务缓存目录 → 换视频=服务器更新=APK自动拉取=零重装！
     * 对比本地记录(md5)跳过已是最新的文件，只下载变化的部分。
     */
    private void checkIntroVideoUpdate() {
        executor.execute(() -> {
            try {
                String versionUrl = "https://mars1417.github.io/lebacenter/intro-version.json";
                URL url = new URL(versionUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code != 200) { conn.disconnect(); return; }
                StringBuilder sb = new StringBuilder();
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
                }
                conn.disconnect();
                String json = sb.toString();
                // 解析 intro_high/mid/low 的 url + md5
                String[] names = {"intro_high.mp4", "intro_mid.mp4", "intro_low.mp4"};
                File cacheDir = new File(getCacheDir(), "intro");
                for (String name : names) {
                    try {
                        // 定位该视频的JSON块: {"intro_high.mp4":{"url":"...","md5":"..."}}
                        int urlIdx = json.indexOf("\"" + name + "\"");
                        if (urlIdx < 0) continue;
                        String block = json.substring(urlIdx);
                        String dlUrl = extractString(block, "url");
                        String remoteMd5 = extractString(block, "md5");
                        if (dlUrl == null || remoteMd5 == null) continue;
                        File target = new File(cacheDir, name);
                        // 已有缓存且md5一致 → 跳过（零下载）
                        if (target.exists() && target.length() > 0) {
                            String localMd5 = md5File(target);
                            if (remoteMd5.equals(localMd5)) continue;
                        }
                        // 下载新视频到缓存目录（只更新变化的部分）
                        URL dl = new URL(dlUrl);
                        HttpURLConnection dc = (HttpURLConnection) dl.openConnection();
                        dc.setConnectTimeout(10000);
                        dc.setReadTimeout(20000);
                        int dcode = dc.getResponseCode();
                        if (dcode != 200) { dc.disconnect(); continue; }
                        if (!cacheDir.exists()) cacheDir.mkdirs();
                        File tmp = new File(cacheDir, name + ".tmp");
                        try (InputStream dis = dc.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[64 * 1024]; int n;
                            while ((n = dis.read(buf)) != -1) fos.write(buf, 0, n);
                        }
                        dc.disconnect();
                        String gotMd5 = md5File(tmp);
                        if (remoteMd5.equals(gotMd5)) {
                            if (target.exists()) target.delete();
                            tmp.renameTo(target);
                            Log.i("IntroUpdate", name + " 已更新 (md5=" + remoteMd5 + ")");
                        } else {
                            tmp.delete();
                            Log.w("IntroUpdate", name + " md5不符,丢弃: " + gotMd5 + " != " + remoteMd5);
                        }
                    } catch (Exception e) {
                        Log.w("IntroUpdate", name + " 更新失败: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.w("IntroUpdate", "版本检查失败: " + e.getMessage());
            }
        });
    }

    private String md5File(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /**
     * 2026-08-03: 动态获取当前cpolar隧道地址
     * 从GP Pages拉取 url.json（gateway_proxy自愈时自动更新），拿到最新隧道
     * cpolar免费版隧道重启域名会变，这样APK不用重新打包也能找到服务器
     */
    private String fetchCurrentTunnelUrl() {
        try {
            URL url = new URL(URL_JSON_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
            }
            conn.disconnect();
            String json = sb.toString();
            String u = extractString(json, "url");
            if (u != null && u.startsWith("http") && u.length() > 10) {
                Log.d("AutoUpdate", "Dynamic tunnel URL: " + u);
                return u;
            }
        } catch (Exception e) {
            Log.d("AutoUpdate", "fetchCurrentTunnelUrl failed: " + e.getMessage());
        }
        return null;
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
    protected void onDestroy() {
        super.onDestroy();
        // 🔴 v56: 停止本地HTTP服务器，释放端口
        if (introServer != null) introServer.stop();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** 🔴 v55: 严格限制读取长度的输入流——MediaPlayer Range请求只返回请求范围内的字节，
     *  否则读到APK文件其余部分(混入其他文件数据) → Format error！
     *  官方WebViewAssetLoader内部就是此类实现。 */
    private static class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in, long maxLen) {
            super(in);
            remaining = maxLen;
        }
        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(Math.min(n, remaining));
            remaining -= s;
            return s;
        }
    }
}
