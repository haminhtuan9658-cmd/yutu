package com.example.ytnoads

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
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

// Cầu nối giữa trang web và ứng dụng
class Cau(private val act: MainActivity) {
    @JavascriptInterface
    fun capNhat(tieuDe: String, kenh: String, anh: String, phat: Boolean, vt: Double, dai: Double) {
        PlayService.hien?.nhan(tieuDe, kenh, anh, phat, vt.toLong(), dai.toLong())
        act.runOnUiThread { act.capNhatBar(tieuDe, phat) }
    }

    @JavascriptInterface
    fun thuNho() {
        act.runOnUiThread { act.thuNho() }
    }

    @JavascriptInterface
    fun moVideo(url: String) {
        act.runOnUiThread { act.moVideo(url) }
    }
}

class MainActivity : Activity() {

    private lateinit var web: WebView          // duyệt trang chủ, tìm kiếm
    private lateinit var pl: WebView           // phát video
    private lateinit var keo: SwipeRefreshLayout
    private lateinit var goc: FrameLayout
    private lateinit var clip: FrameLayout
    private lateinit var barNen: FrameLayout
    private lateinit var tap: View
    private lateinit var fs: FrameLayout
    private lateinit var tieuDeBar: TextView
    private lateinit var nutPhat: ImageView

    private var callbackToan: WebChromeClient.CustomViewCallback? = null
    private var coPlayer = false
    private var dangMini = false
    private var dangDo = false
    private var barTieuDeCu = ""
    private var barPhatCu: Boolean? = null

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val chanUrl = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google", "/pagead/", "/api/stats/ads", "/ptracking", "get_midroll_info"
    )

    // Ép trang dùng khung điện thoại để không hiện cột menu bên trái
    private val jsKhung = """
    (function(){
      var muc = 'width=412, user-scalable=no';
      function dat(){
        var m = document.querySelector('meta[name=viewport]');
        if (!m) {
          m = document.createElement('meta');
          m.name = 'viewport';
          (document.head || document.documentElement).appendChild(m);
        }
        if (m.content !== muc) m.content = muc;
      }
      dat();
      if (!window.__ytKhung) { window.__ytKhung = 1; setInterval(dat, 2000); }
    })();
    """.trimIndent()

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

    // Ẩn banner quảng cáo, bỏ qua quảng cáo trong video
    private val jsChan = """
    (function(){
      if (window.__ytNoAds) return; window.__ytNoAds = 1;
      var css = document.createElement('style');
      css.textContent = 'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,' +
        'ytm-companion-slot,ytm-statement-banner-renderer,ad-slot-renderer,' +
        'ytm-display-ad-renderer,.ad-container,.ytp-ad-overlay-container,.ytp-ad-image-overlay,' +
        'ytm-promoted-sparkles-text-search-renderer,ytm-search-pyv-renderer,' +
        'ytm-chip-cloud-renderer,ytm-feed-filter-chip-bar-renderer,ytm-chip-cloud-chip-renderer,.chip-cloud,' +
        'ytm-mini-guide-renderer,ytd-mini-guide-renderer,#mini-guide-background,' +
        'ytm-brand-video-singleton-renderer{display:none!important}' +
        'html,body{overflow-x:hidden!important;max-width:100vw!important}';
      document.head.appendChild(css);
      var tuaQc = false;
      setInterval(function(){
        var p = document.querySelector('.html5-video-player');
        if (!p) return;
        var v = document.querySelector('video');
        var qc = p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting');
        if (qc) {
          tuaQc = true;
          if (v && isFinite(v.duration) && v.duration > 0) {
            v.muted = true; v.playbackRate = 16; v.currentTime = v.duration;
          }
          var b = document.querySelector(
            '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,' +
            '.ytp-ad-skip-button-container button,.ytp-ad-overlay-close-button');
          if (b) b.click();
        } else if (tuaQc) {
          tuaQc = false;
          if (v) { v.playbackRate = 1; v.muted = false; if (v.paused && !v.ended) v.play(); }
        }
      }, 400);
    })();
    """.trimIndent()

    // Tự phát video có tiếng khi mở video (không giới hạn chất lượng)
    private val jsTuPhat = """
    (function(){
      if (window.__ytTuPhat) return; window.__ytTuPhat = 1;
      var daLam = '';
      setInterval(function(){
        if (location.pathname.indexOf('/watch') !== 0) { daLam = ''; return; }
        var khoa = location.search;
        if (daLam === khoa) return;
        var v = document.querySelector('video');
        if (!v || !v.currentSrc) return;
        var p = document.querySelector('.html5-video-player');
        if (p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'))) return;
        var un = document.querySelector('.ytp-unmute,.ytp-unmute-inner,.ytp-unmute-icon');
        if (un) un.click();
        v.muted = false;
        try { v.volume = 1; } catch(e) {}
        if (v.paused && !v.ended) { var r = v.play(); if (r && r.catch) r.catch(function(){}); }
        if (!v.paused && !v.muted) daLam = khoa;
      }, 800);
    })();
    """.trimIndent()

    // Khung phát video rộng hết chiều ngang, tỉ lệ 16:9, bỏ vùng đen bên dưới
    private val jsRong = """
    (function(){
      if (window.__ytRong) return; window.__ytRong = 1;
      var css = document.createElement('style');
      css.textContent = 'html[data-ytw] #player-container-id,html[data-ytw] #player,' +
        'html[data-ytw] .player-container,html[data-ytw] .player-size,' +
        'html[data-ytw] .html5-video-player,html[data-ytw] .html5-video-container' +
        '{width:100vw!important;max-width:100vw!important;height:56.25vw!important;max-height:none!important;min-height:0!important}' +
        'html[data-ytw] video.html5-main-video{width:100vw!important;height:56.25vw!important;left:0!important;top:0!important}';
      document.head.appendChild(css);
      var was = false;
      var mucTieu = [
        'ytm-slim-video-metadata-section-renderer',
        'ytm-slim-video-information-renderer',
        '.slim-video-metadata-header',
        'ytm-slim-owner-renderer',
        'ytm-single-column-watch-next-results-renderer',
        'ytm-item-section-renderer'
      ];
      function boVungDen(){
        var v = document.querySelector('video');
        if (!v) return;
        var vr = v.getBoundingClientRect();
        if (vr.height < 50) return;
        for (var i = 0; i < mucTieu.length; i++) {
          var t = document.querySelector(mucTieu[i]);
          if (!t) continue;
          var tr = t.getBoundingClientRect();
          if (tr.height <= 0) continue;
          var g = tr.top - vr.bottom;
          var cur = parseFloat(t.getAttribute('data-ytm') || '0');
          if (g > 6 || (g < -6 && cur < 0)) {
            cur -= g;
            if (cur > 0) cur = 0;
            t.setAttribute('data-ytm', String(cur));
            t.style.setProperty('margin-top', cur + 'px', 'important');
          }
          break;
        }
      }
      function chinh(){
        var w = location.pathname.indexOf('/watch') === 0;
        var fsc = document.querySelector('.html5-video-player.ytp-fullscreen') || document.fullscreenElement;
        var on = w && !fsc;
        if (on) document.documentElement.setAttribute('data-ytw', '1');
        else document.documentElement.removeAttribute('data-ytw');
        if (on && !was) window.dispatchEvent(new Event('resize'));
        was = on;
        if (on) boVungDen();
      }
      setInterval(chinh, 1200);
      chinh();
    })();
    """.trimIndent()

    // Bấm nút YouTube trong khung phát thì thu nhỏ xuống dưới
    private val jsLogo = """
    (function(){
      if (window.__ytLogo) return; window.__ytLogo = 1;
      document.addEventListener('click', function(e){
        var t = e.target;
        if (!t || !t.closest) return;
        var a = t.closest('ytm-home-logo,.mobile-topbar-logo,a[href="/"],a[aria-label="YouTube Home"]');
        if (!a) return;
        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
        Android.thuNho();
      }, true);
    })();
    """.trimIndent()

    // Trong trang chủ: bấm vào video thì mở ở khung phát riêng
    private val jsLink = """
    (function(){
      if (window.__ytLink) return; window.__ytLink = 1;
      document.addEventListener('click', function(e){
        var t = e.target;
        if (!t || !t.closest) return;
        var a = t.closest('a[href*="/watch"]');
        if (!a) return;
        var h = a.getAttribute('href') || '';
        if (h.indexOf('/watch') !== 0 && h.indexOf('youtube.com/watch') < 0) return;
        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
        Android.moVideo(a.href);
      }, true);
    })();
    """.trimIndent()

    private val jsDoVideo = """
    (function(){
      window.scrollTo(0, 0);
      var v = document.querySelector('video');
      if (!v) return null;
      var r = v.getBoundingClientRect();
      return [r.left, r.top, r.width, r.height, window.innerWidth];
    })();
    """.trimIndent()

    // Báo thông tin bài đang phát về ứng dụng, chỉ khi có thay đổi; tự gỡ khi hình đứng
    private val jsBao = """
    (function(){
      if (window.__ytBao) return; window.__ytBao = 1;
      var lastT = '', lastP = null, lastSend = 0, lastPos = -1, stuck = 0;
      setInterval(function(){
        var v = document.querySelector('video');
        if (!v || !v.currentSrc) return;
        var phat = !v.paused && !v.ended;
        if (phat) {
          var pos0 = v.currentTime || 0;
          if (Math.abs(pos0 - lastPos) < 0.05) { stuck++; } else { stuck = 0; }
          lastPos = pos0;
          if (stuck === 6) { try { v.currentTime = pos0 + 0.1; } catch(e) {} }
        } else { stuck = 0; lastPos = -1; }
        var md = (navigator.mediaSession && navigator.mediaSession.metadata) ? navigator.mediaSession.metadata : null;
        var title = (md && md.title) ? md.title : document.title.replace(' - YouTube', '');
        var now = Date.now();
        if (title === lastT && phat === lastP && now - lastSend < 5000) return;
        lastT = title; lastP = phat; lastSend = now;
        var artist = (md && md.artist) ? md.artist : '';
        var art = '';
        if (md && md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src;
        if (!art) {
          var m = location.search.match(/[?&]v=([^&]+)/);
          if (m) art = 'https://i.ytimg.com/vi/' + m[1] + '/hqdefault.jpg';
        }
        var dur = isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0;
        var pos = Math.floor((v.currentTime || 0) * 1000);
        Android.capNhat(title, artist, art, phat, pos, dur);
      }, 1000);
    })();
    """.trimIndent()

    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(view: View, cb: CustomViewCallback) {
            callbackToan = cb
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            fs.removeAllViews()
            fs.addView(view, FrameLayout.LayoutParams(MP, MP))
            fs.visibility = View.VISIBLE
        }
        override fun onHideCustomView() {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            fs.removeAllViews()
            fs.visibility = View.GONE
            callbackToan?.onCustomViewHidden()
            callbackToan = null
        }
    }

    inner class Khach(private val laPlayer: Boolean) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            val url = req.url.toString()
            if (chanUrl.any { url.contains(it) }) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            view.evaluateJavascript(jsKhung, null)
            view.evaluateJavascript(jsNen, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(jsKhung, null)
            view.evaluateJavascript(jsNen, null)
            view.evaluateJavascript(jsChan, null)
            if (laPlayer) {
                view.evaluateJavascript(jsRong, null)
                view.evaluateJavascript(jsTuPhat, null)
                view.evaluateJavascript(jsBao, null)
                view.evaluateJavascript(jsLogo, null)
            } else {
                view.evaluateJavascript(jsLink, null)
                keo.isRefreshing = false
                capNhatKeo(url)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (!laPlayer) {
                capNhatKeo(url)
                val u = url
                val p = Uri.parse(u ?: "").path ?: ""
                if (u != null && p.startsWith("/watch")) {
                    view.post {
                        if (view.canGoBack()) view.goBack()
                        moVideo(u)
                    }
                }
            }
        }
    }

    private fun capNhatKeo(url: String?) {
        val path = Uri.parse(url ?: "").path ?: ""
        keo.isEnabled = (path == "/" || path.isEmpty())
    }

    private fun datDay(h: Int) {
        val lp = keo.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = h
        keo.layoutParams = lp
    }

    // ---- khung phát video ----
    fun moVideo(url: String) {
        coPlayer = true
        clip.visibility = View.VISIBLE
        phongTo()
        pl.loadUrl(url)
    }

    fun phongTo() {
        if (!coPlayer) return
        dangMini = false
        barNen.visibility = View.GONE
        tap.visibility = View.GONE
        datDay(0)
        clip.layoutParams = FrameLayout.LayoutParams(MP, MP)
        pl.layoutParams = FrameLayout.LayoutParams(MP, MP)
        pl.scaleX = 1f
        pl.scaleY = 1f
        pl.translationX = 0f
        pl.translationY = 0f
    }

    fun thuNho() {
        if (!coPlayer || dangMini || dangDo) return
        dangDo = true
        pl.evaluateJavascript(jsDoVideo) { kq ->
            var l = 0f
            var t = 56f
            var w = 412f
            var h = 232f
            var iw = 412f
            try {
                val a = JSONArray(kq)
                val l2 = a.getDouble(0).toFloat()
                val t2 = a.getDouble(1).toFloat()
                val w2 = a.getDouble(2).toFloat()
                val h2 = a.getDouble(3).toFloat()
                val iw2 = a.getDouble(4).toFloat()
                if (w2 > 0f && h2 > 0f && iw2 > 0f) {
                    l = l2; t = t2; w = w2; h = h2; iw = iw2
                }
            } catch (e: Exception) {
            }
            val W = goc.width
            val H = goc.height
            val f = pl.width.toFloat() / iw
            val vl = l * f
            val vt = t * f
            val vw = w * f
            val vh = h * f
            val barH = dp(72)
            val s = barH / vh
            val clipW = (vw * s).toInt()

            pl.layoutParams = FrameLayout.LayoutParams(W, H)
            pl.pivotX = 0f
            pl.pivotY = 0f
            pl.scaleX = s
            pl.scaleY = s
            pl.translationX = -vl * s
            pl.translationY = -vt * s

            clip.layoutParams = FrameLayout.LayoutParams(clipW, barH, Gravity.BOTTOM or Gravity.START)
            tap.layoutParams = FrameLayout.LayoutParams(clipW, barH, Gravity.BOTTOM or Gravity.START)
            val tl = tieuDeBar.layoutParams as FrameLayout.LayoutParams
            tl.marginStart = clipW + dp(8)
            tieuDeBar.layoutParams = tl

            barNen.visibility = View.VISIBLE
            tap.visibility = View.VISIBLE
            datDay(barH)
            dangMini = true
            dangDo = false
        }
    }

    fun dong() {
        pl.loadUrl("about:blank")
        pl.clearHistory()
        coPlayer = false
        dangMini = false
        dangDo = false
        barNen.visibility = View.GONE
        tap.visibility = View.GONE
        datDay(0)
        clip.layoutParams = FrameLayout.LayoutParams(MP, MP)
        pl.layoutParams = FrameLayout.LayoutParams(MP, MP)
        pl.scaleX = 1f
        pl.scaleY = 1f
        pl.translationX = 0f
        pl.translationY = 0f
        clip.visibility = View.GONE
        PlayService.hien?.nhan("", "", "", false, 0L, 0L)
    }

    fun capNhatBar(tieuDe: String, phat: Boolean) {
        if (tieuDe != barTieuDeCu) {
            barTieuDeCu = tieuDe
            tieuDeBar.text = tieuDe
        }
        if (phat != barPhatCu) {
            barPhatCu = phat
            nutPhat.setImageResource(
                if (phat) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun caiDat(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString.replace("; wv", "")
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= 26) {
            w.settings.safeBrowsingEnabled = false
        }
        w.setBackgroundColor(0xFF0F0F0F.toInt())
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isHorizontalScrollBarEnabled = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Cau(this), "Android")
        w.webChromeClient = chrome
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, PlayService::class.java))

        val ck = CookieManager.getInstance()
        ck.setAcceptCookie(true)
        val cu = ck.getCookie("https://m.youtube.com") ?: ""
        if (!cu.contains("f6=")) {
            ck.setCookie(
                "https://m.youtube.com",
                "PREF=f6=400&hl=vi; Domain=.youtube.com; Path=/; Max-Age=31536000"
            )
        }

        web = BenWebView(this)
        pl = BenWebView(this)
        PlayService.web = pl
        caiDat(web)
        caiDat(pl)
        web.webViewClient = Khach(false)
        pl.webViewClient = Khach(true)

        // trang chủ có vuốt xuống để tải mới
        keo = SwipeRefreshLayout(this)
        keo.addView(web)
        keo.setColorSchemeColors(0xFFE53935.toInt())
        keo.setProgressBackgroundColorSchemeColor(0xFF2A2A2A.toInt())
        keo.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        keo.setOnRefreshListener {
            web.reload()
            keo.postDelayed({ keo.isRefreshing = false }, 8000)
        }

        // khung phát video
        clip = FrameLayout(this)
        clip.visibility = View.GONE
        clip.addView(pl, FrameLayout.LayoutParams(MP, MP))

        // thanh thu nhỏ ở dưới màn hình
        barNen = FrameLayout(this)
        barNen.setBackgroundColor(0xFF1F1F1F.toInt())
        barNen.visibility = View.GONE
        barNen.isClickable = true
        barNen.setOnClickListener { phongTo() }

        tieuDeBar = TextView(this)
        tieuDeBar.setTextColor(0xFFFFFFFF.toInt())
        tieuDeBar.textSize = 14f
        tieuDeBar.maxLines = 2
        tieuDeBar.ellipsize = TextUtils.TruncateAt.END
        val lpTieuDe = FrameLayout.LayoutParams(MP, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
        lpTieuDe.marginStart = dp(136)
        lpTieuDe.marginEnd = dp(100)
        barNen.addView(tieuDeBar, lpTieuDe)

        nutPhat = ImageView(this)
        nutPhat.setImageResource(android.R.drawable.ic_media_pause)
        nutPhat.setColorFilter(0xFFFFFFFF.toInt())
        nutPhat.setPadding(dp(12), dp(12), dp(12), dp(12))
        nutPhat.setOnClickListener {
            pl.evaluateJavascript(
                "var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}",
                null
            )
        }
        val lpPhat = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
        lpPhat.marginEnd = dp(48)
        barNen.addView(nutPhat, lpPhat)

        val nutDong = ImageView(this)
        nutDong.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        nutDong.setColorFilter(0xFFFFFFFF.toInt())
        nutDong.setPadding(dp(12), dp(12), dp(12), dp(12))
        nutDong.setOnClickListener { dong() }
        barNen.addView(nutDong, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))

        // lớp bắt chạm lên khung video nhỏ để phóng to lại
        tap = View(this)
        tap.isClickable = true
        tap.visibility = View.GONE
        tap.setOnClickListener { phongTo() }

        // lớp toàn màn hình
        fs = FrameLayout(this)
        fs.setBackgroundColor(0xFF000000.toInt())
        fs.visibility = View.GONE

        goc = FrameLayout(this)
        goc.setBackgroundColor(0xFF0F0F0F.toInt())
        goc.addView(keo, FrameLayout.LayoutParams(MP, MP))
        goc.addView(barNen, FrameLayout.LayoutParams(MP, dp(72), Gravity.BOTTOM))
        goc.addView(clip, FrameLayout.LayoutParams(MP, MP))
        goc.addView(tap, FrameLayout.LayoutParams(dp(128), dp(72), Gravity.BOTTOM or Gravity.START))
        goc.addView(fs, FrameLayout.LayoutParams(MP, MP))
        setContentView(goc)

        web.loadUrl("https://m.youtube.com")
    }

    override fun onPause() {
        val nenPhat = PlayService.hien?.phat == true
        super.onPause()
        CookieManager.getInstance().flush()
        if (nenPhat) {
            val js = "var v=document.querySelector('video');if(v&&v.paused&&!v.ended)v.play();"
            for (d in longArrayOf(300, 1000, 2500, 5000)) {
                pl.postDelayed({ pl.evaluateJavascript(js, null) }, d)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fs.visibility == View.VISIBLE -> chrome.onHideCustomView()
            coPlayer && !dangMini -> thuNho()
            web.canGoBack() -> web.goBack()
            coPlayer -> moveTaskToBack(true)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, PlayService::class.java))
        super.onDestroy()
    }
}
