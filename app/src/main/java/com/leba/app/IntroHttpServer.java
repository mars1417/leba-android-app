package com.leba.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🔴 v56: 本地HTTP服务器（127.0.0.1）—— APK内置开场视频播放根治方案
 *
 * 为什么需要它：
 *   appassets.androidplatform.net 是"虚拟假域名"，只在 WebView 拦截器里成立。
 *   国产ROM定制WebView播放<video>时走【系统MediaPlayer直连管线】，完全绕过
 *   shouldInterceptRequest → 拿假域名去真实网络解析 → 必然失败 → 错误4 Format error。
 *
 *   127.0.0.1 是真实环回地址 + 标准HTTP协议 → 任何ROM/任何管线的MediaPlayer都能直连播放。
 *
 * 服务内容：
 *   GET /intro/<token>/intro_high.mp4  → 从缓存目录(cacheDir/intro/)或assets读取
 *   支持 HTTP Range/206 分段请求（MediaPlayer必需）
 *
 * 增量更新（Boss要求：不整包重装，推送替换指定部分）：
 *   APK启动时检查服务器 intro-version.json → 有新版本下载视频到 cacheDir/intro/ →
 *   本地服务器优先服务缓存目录 → 换视频 = 服务器更新 = APK自动拉取 = 零重装！
 */
public class IntroHttpServer {
    private static final String TAG = "IntroServer";
    private static final int MAX_VIDEO_BYTES = 25 * 1024 * 1024; // 25MB 上限保护（v59: 12→25，放行17MB原始未压缩版）

    private final Context context;
    private final int port;
    private final String token;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    // assets 内置视频缓存（首次读取后驻留内存，避免频繁IO）
    private final Map<String, byte[]> assetCache = new HashMap<>();
    private final Object lock = new Object();

    public IntroHttpServer(Context context) {
        this.context = context.getApplicationContext();
        this.token = Long.toHexString(System.nanoTime()) + Long.toHexString((long)(Math.random() * 0x7fffffff));
        this.port = findFreePort();
    }

    public int getPort() { return port; }
    public String getToken() { return token; }

    /** 生成页面JS可用的完整URL: http://127.0.0.1:PORT/intro/TOKEN/intro_high.mp4 */
    public String urlFor(String fileName) {
        return "http://127.0.0.1:" + port + "/intro/" + token + "/" + fileName;
    }

    private int findFreePort() {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 18990; // 兜底固定端口
        }
    }

    public void start() {
        if (running) return;
        try {
            serverSocket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
            running = true;
            executor = Executors.newCachedThreadPool();
            new Thread(this::acceptLoop, "IntroHttpServer").start();
            Log.i(TAG, "started on 127.0.0.1:" + port + " token=" + token);
        } catch (IOException e) {
            Log.e(TAG, "start failed: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignore) {}
        if (executor != null) { executor.shutdownNow(); }
        Log.i(TAG, "stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);

            // 只读请求行 + Host 头（视频请求无复杂头，足够）
            String requestLine = readLine(in);
            if (requestLine == null) { close(socket); return; }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            // 读请求头直到空行
            String rangeHeader = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                }
            }

            // 路径校验: /intro/<token>/<file>
            if (!path.startsWith("/intro/" + token + "/")) {
                sendStatus(out, 404, "Not Found");
                close(socket);
                return;
            }
            String fileName = path.substring(("/intro/" + token + "/").length());
            if (!isAllowedFile(fileName)) {
                sendStatus(out, 403, "Forbidden");
                close(socket);
                return;
            }

            byte[] data = loadVideo(fileName);
            if (data == null) {
                sendStatus(out, 404, "Not Found");
                close(socket);
                return;
            }

            // 解析 Range（MediaPlayer 会发 bytes=0- 或 bytes=N-M）
            long start = 0;
            long end = data.length - 1;
            boolean hasRange = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] rp = rangeHeader.substring(6).split("-");
                try { start = Long.parseLong(rp[0]); } catch (Exception ignore) {}
                if (rp.length > 1 && !rp[1].isEmpty()) {
                    try { end = Long.parseLong(rp[1]); } catch (Exception ignore) {}
                }
                if (start >= data.length) {
                    // 超出范围 → 416
                    String body = "Range Not Satisfiable";
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */" + data.length + "\r\n" +
                            "Content-Length: " + body.length() + "\r\n\r\n").getBytes());
                    out.write(body.getBytes());
                    out.flush();
                    close(socket);
                    return;
                }
                if (end >= data.length) end = data.length - 1;
                hasRange = true;
            }
            long contentLen = end - start + 1;

            StringBuilder head = new StringBuilder();
            if (hasRange) {
                head.append("HTTP/1.1 206 Partial Content\r\n");
                head.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(data.length).append("\r\n");
            } else {
                head.append("HTTP/1.1 200 OK\r\n");
            }
            head.append("Content-Type: video/mp4\r\n");
            head.append("Content-Length: ").append(contentLen).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");

            out.write(head.toString().getBytes());
            out.write(data, (int) start, (int) contentLen);
            out.flush();
            close(socket);
        } catch (Exception e) {
            Log.w(TAG, "handle error: " + e.getMessage());
            close(socket);
        }
    }

    private byte[] loadVideo(String fileName) {
        // 1) 优先缓存目录（增量更新下载的新视频）
        File cacheFile = new File(context.getCacheDir(), "intro/" + fileName);
        if (cacheFile.exists() && cacheFile.length() > 0 && cacheFile.length() < MAX_VIDEO_BYTES) {
            try {
                FileInputStream fis = new FileInputStream(cacheFile);
                byte[] d = readAll(fis, (int) cacheFile.length());
                fis.close();
                if (d != null && d.length > 0) return d;
            } catch (Exception e) {
                Log.w(TAG, "cache read failed: " + e.getMessage());
            }
        }
        // 2) assets 内置（驻留内存）
        synchronized (lock) {
            if (assetCache.containsKey(fileName)) return assetCache.get(fileName);
        }
        try {
            InputStream is = context.getAssets().open("intro/" + fileName);
            byte[] d = readAll(is, 0);
            is.close();
            if (d != null && d.length > 0) {
                synchronized (lock) {
                    assetCache.put(fileName, d);
                }
                return d;
            }
        } catch (Exception e) {
            Log.w(TAG, "assets read failed " + fileName + ": " + e.getMessage());
        }
        return null;
    }

    private byte[] readAll(InputStream is, int knownLen) throws IOException {
        ByteArrayOutputStream bos = knownLen > 0 ? new ByteArrayOutputStream(knownLen) : new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_VIDEO_BYTES) throw new IOException("file too large");
        }
        return bos.toByteArray();
    }

    private boolean isAllowedFile(String name) {
        return "intro_high.mp4".equals(name) ||
               "intro_mid.mp4".equals(name) ||
               "intro_low.mp4".equals(name);
    }

    private void sendStatus(OutputStream out, int code, String msg) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + msg + "\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 4096) break;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void close(Socket s) {
        try { s.close(); } catch (IOException ignore) {}
    }
}
