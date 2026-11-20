package com.example.ytnoads

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

// WebView luôn coi như đang hiển thị, để video không bị tạm dừng khi tắt màn hình
class BenWebView(c: Context) : WebView(c) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
    override fun onScreenStateChanged(screenState: Int) {
    }
}

// Cầu nối: trang web báo thông tin bài đang phát về ứng dụng
class Cau {
    @JavascriptInterface
    fun capNhat(tieuDe: String, kenh: String, anh: String, phat: Boolean, vt: Double, dai: Double) {
        PlayService.hien?.nhan(tieuDe, kenh, anh, phat, vt.toLong(), dai.toLong())
    }
}

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var keo: SwipeRefreshLayout
    private var manHinhToan: View? = null
    private var callbackToan: WebChromeClient.CustomViewCallback? = null

    private val chanUrl = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google", "/pagead/", "/api/stats/ads", "/ptracking", "get_midroll_info"
    )

    private val jsNen = """
    (function(){
      if (window.__ytNen) return; window.__ytNen = 1;
      try {
        Object.defineProperty(document, 'hidden', {get: function(){ return false; }});
        Object.defineProperty(document, 'webkitHidden', {get: function(){ return false; }});
        Object.defineProperty(document, 'visibilityState', {get: function(){ return 'visible'; }});
        Object.defineProperty(document, 'webkitVisibilityState', {get: function(){ return 'visible'; }});
      } catch(e) {}
      ['visibilitychange','webkitvisibilitychange','blur','pagehide'].forEach(function(t){
        window.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
        document.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
      });
    })();
    """.trimIndent()

    private val jsChan = """
    (function(){
      if (window.__ytNoAds) return; window.__ytNoAds = 1;
      var css = document.createElement('style');
      css.textContent = 'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,' +
        'ytm-companion-slot,ytm-statement-banner-renderer,ad-slot-renderer,' +
        'ytm-display-ad-renderer,.ad-container,.ytp-ad-overlay-container,' +
        'ytm-brand-video-singleton-renderer{display:none!important}';
      document.head.appendChild(css);
      setInterval(function(){
        var p = document.querySelector('.html5-video-player');
        if (p && p.classList.contains('ad-showing')) {
          var v = document.querySelector('video');
          if (v && isFinite(v.duration)) { v.muted = true; v.playbackRate = 16; v.currentTime = v.duration; }
        }
        var b = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
        if (b) b.click();
      }, 300);
    })();
    """.trimIndent()

    private val jsBao = """
    (function(){
      if (window.__ytBao) return; window.__ytBao = 1;
      setInterval(function(){
        var v = document.querySelector('video');
        if (!v || !v.currentSrc) return;
        var md = (navigator.mediaSession && navigator.mediaSession.metadata) ? navigator.mediaSession.metadata : null;
        var title = (md && md.title) ? md.title : document.title.replace(' - YouTube', '');
        var artist = (md && md.artist) ? md.artist : '';
        var art = '';
        if (md && md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src;
        if (!art) {
          var m = location.search.match(/[?&]v=([^&]+)/);
          if (m) art = 'https://i.ytimg.com/vi/' + m[1] + '/hqdefault.jpg';
        }
        var dur = isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0;
        var pos = Math.floor((v.currentTime || 0) * 1000);
        Android.capNhat(title, artist, art, !v.paused && !v.ended, pos, dur);
      }, 1000);
    })();
    """.trimIndent()

    private fun capNhatKeo(url: String?) {
        val path = Uri.parse(url ?: "").path ?: ""
        keo.isEnabled = (path == "/" || path.isEmpty())
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, PlayService::class.java))

        web = BenWebView(this)
        PlayService.web = web

        keo = SwipeRefreshLayout(this)
        keo.addView(web)
        keo.setColorSchemeColors(0xFFE53935.toInt())
        keo.setProgressBackgroundColorSchemeColor(0xFF2A2A2A.toInt())
        keo.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        keo.setOnRefreshListener {
            web.reload()
            keo.postDelayed({ keo.isRefreshing = false }, 8000)
        }
        setContentView(keo)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Cau(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                if (chanUrl.any { url.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(jsNen, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(jsNen, null)
                view.evaluateJavascript(jsChan, null)
                view.evaluateJavascript(jsBao, null)
                keo.isRefreshing = false
                capNhatKeo(url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                capNhatKeo(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                manHinhToan = view
                callbackToan = cb
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                setContentView(view)
            }
            override fun onHideCustomView() {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                setContentView(keo)
                callbackToan?.onCustomViewHidden()
                manHinhToan = null
                callbackToan = null
            }
        }

        web.loadUrl("https://m.youtube.com")
    }

    override fun onPause() {
        val nenPhat = PlayService.hien?.phat == true
        super.onPause()
        CookieManager.getInstance().flush()
        if (nenPhat) {
            val js = "var v=document.querySelector('video');if(v&&v.paused&&!v.ended)v.play();"
            for (d in longArrayOf(300, 1000, 2500, 5000)) {
                web.postDelayed({ web.evaluateJavascript(js, null) }, d)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            manHinhToan != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, PlayService::class.java))
        super.onDestroy()
    }
}
