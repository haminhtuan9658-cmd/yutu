package com.example.ytnoads

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class PlayService : Service() {

    companion object {
        @Volatile var hien: PlayService? = null
        @Volatile var web: WebView? = null
        const val A_PLAY = "ytnoads.PLAY"
        const val A_PAUSE = "ytnoads.PAUSE"
        const val A_NEXT = "ytnoads.NEXT"
        const val A_PREV = "ytnoads.PREV"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ms: MediaSession
    private lateinit var nm: NotificationManager
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    @Volatile var phat = false
    private var tieuDe = ""
    private var kenh = ""
    private var anhUrl = ""
    private var anhBmp: Bitmap? = null
    private var vt = 0L
    private var dai = 0L
    private var vtGoc = 0L
    private var tgGoc = 0L

    private fun js(code: String) {
        main.post { web?.evaluateJavascript(code, null) }
    }

    override fun onCreate() {
        super.onCreate()
        hien = this
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("yt", "Phát nền", NotificationManager.IMPORTANCE_LOW)
        )

        ms = MediaSession(this, "ytnoads")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { lenhPhat() }
            override fun onPause() { lenhDung() }
            override fun onSkipToNext() { lenhSau() }
            override fun onSkipToPrevious() { lenhTruoc() }
            override fun onSeekTo(pos: Long) { lenhTua(pos) }
        })
        ms.isActive = true
        datMeta()
        datTrangThai()

        val n = taoThongBao()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytnoads:play")
        wl?.acquire()

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytnoads:wifi")
        wifi?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_PLAY -> lenhPhat()
            A_PAUSE -> lenhDung()
            A_NEXT -> lenhSau()
            A_PREV -> lenhTruoc()
        }
        return START_NOT_STICKY
    }

    // ---- lệnh điều khiển gửi vào trang YouTube ----
    private fun lenhPhat() {
        phat = true
        capNhatNgay()
        js("var v=document.querySelector('video');if(v)v.play();")
    }

    private fun lenhDung() {
        phat = false
        capNhatNgay()
        js("var v=document.querySelector('video');if(v)v.pause();")
    }

    private fun lenhSau() {
        js("var b=document.querySelector('.ytp-next-button');if(b)b.click();")
    }

    private fun lenhTruoc() {
        js("var v=document.querySelector('video');var b=document.querySelector('.ytp-prev-button');" +
            "if(b&&b.getAttribute('aria-disabled')!=='true'&&b.offsetParent!==null){b.click();}" +
            "else if(v){v.currentTime=0;}")
    }

    private fun lenhTua(viTri: Long) {
        vt = viTri
        capNhatNgay()
        js("var v=document.querySelector('video');if(v)v.currentTime=" + (viTri / 1000.0) + ";")
    }

    // ---- nhận thông tin từ trang web (gọi mỗi giây) ----
    fun nhan(t: String, k: String, a: String, p: Boolean, v: Long, d: Long) {
        main.post {
            val doiMeta = t != tieuDe || k != kenh || a != anhUrl || d != dai
            val doiTT = p != phat
            val lech = abs(v - uocTinh()) > 2500
            tieuDe = t
            kenh = k
            phat = p
            vt = v
            dai = d
            if (a != anhUrl) {
                anhUrl = a
                anhBmp = null
                taiAnh(a)
            }
            if (doiMeta) datMeta()
            if (doiMeta || doiTT || lech) datTrangThai()
            if (doiMeta || doiTT) nm.notify(1, taoThongBao())
        }
    }

    private fun taiAnh(url: String) {
        if (url.isEmpty()) return
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val bm = BitmapFactory.decodeStream(c.inputStream)
                main.post {
                    if (url == anhUrl && bm != null) {
                        anhBmp = bm
                        datMeta()
                        nm.notify(1, taoThongBao())
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun capNhatNgay() {
        datTrangThai()
        nm.notify(1, taoThongBao())
    }

    private fun datMeta() {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, if (tieuDe.isEmpty()) "Youtube" else tieuDe)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, kenh)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dai)
        anhBmp?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        ms.setMetadata(b.build())
    }

    private fun datTrangThai() {
        vtGoc = vt
        tgGoc = SystemClock.elapsedRealtime()
        val acts = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        val st = if (phat) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(acts)
                .setState(st, vt, if (phat) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun uocTinh(): Long =
        if (phat) vtGoc + (SystemClock.elapsedRealtime() - tgGoc) else vtGoc

    private fun nut(hanhDong: String, bieuTuong: Int, ten: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, hanhDong.hashCode(),
            Intent(this, PlayService::class.java).setAction(hanhDong),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, bieuTuong), ten, pi).build()
    }

    private fun taoThongBao(): Notification {
        val mo = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val giua = if (phat)
            nut(A_PAUSE, android.R.drawable.ic_media_pause, "Tạm dừng")
        else
            nut(A_PLAY, android.R.drawable.ic_media_play, "Phát")

        return Notification.Builder(this, "yt")
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(if (tieuDe.isEmpty()) "Youtube" else tieuDe)
            .setContentText(kenh)
            .setLargeIcon(anhBmp)
            .setContentIntent(mo)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(nut(A_PREV, android.R.drawable.ic_media_previous, "Trước"))
            .addAction(giua)
            .addAction(nut(A_NEXT, android.R.drawable.ic_media_next, "Sau"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(ms.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onDestroy() {
        hien = null
        try { ms.release() } catch (e: Exception) { }
        wl?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
