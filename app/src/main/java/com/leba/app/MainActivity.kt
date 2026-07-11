package com.leba.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val GP_URL = "https://mars1417.github.io/lebacenter/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 创建通知渠道
        createNotificationChannel()

        // 请求通知权限（Android 13+）
        requestNotificationPermission()

        // 配置WebView
        webView = findViewById(R.id.webview)
        setupWebView()

        // 加载乐吧入口页
        webView.loadUrl(GP_URL)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // 允许跨域导航（GP → cpolar隧道）
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // 所有链接都在WebView内打开
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 页面标题变化时更新通知
                updateNotificationFromTitle(view.title ?: "乐吧公益中心")
            }
        }

        // 处理JS弹窗和标题
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                supportActionBar?.title = title
            }
        }

        // 添加JS桥接（供页面调用Android通知）
        webView.addJavascriptInterface(
            JavaScriptBridge(this),
            "LebaBridge"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "乐吧通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "来自乐吧的通知消息"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun updateNotificationFromTitle(title: String) {
        // 简化：页面标题包含新通知时触发
        if (title.contains("🔔") || title.contains("通知") || title.contains("提醒")) {
            showNotification("乐吧通知", title)
        }
    }

    fun showNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        var notificationId = (System.currentTimeMillis() % 10000).toInt()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val CHANNEL_ID = "leba_notifications"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }
}

// JavaScript桥接：Web页面可以主动调用Android通知
class JavaScriptBridge(private val context: Context) {
    @JavascriptInterface
    fun notify(title: String, message: String) {
        if (context is MainActivity) {
            context.showNotification(title, message)
        }
    }
}
