package com.rez4ie.fables

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var speed = 1f
    private val dataDir by lazy { File(filesDir, "data").apply { mkdirs() } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/data/", WebViewAssetLoader.InternalStoragePathHandler(this, dataDir))
            .build()
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, u)); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.App && App.back()") { r -> if (r != "true") finish() }
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }
    private fun q(s: String) = JSONObject.quote(s)
    private fun file(rel: String): File {
        val f = File(dataDir, rel).canonicalFile
        require(f.path.startsWith(dataDir.canonicalPath)) { "bad path" }
        return f
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "OnceUponAFable/" + BuildConfigVersion.name(this@MainActivity))
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
            else if (code >= 400) { c.disconnect(); throw Exception("HTTP $code") }
            else return c
        }
        throw Exception("Too many redirects")
    }

    private fun downloadTo(url: String, dest: File, progress: ((Int) -> Unit)? = null) {
        val c = open(url)
        val total = c.contentLengthLong
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        c.inputStream.use { inp -> tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                out.write(buf, 0, n); done += n
                if (progress != null && total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
            }
        } }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
    }

    inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfigVersion.name(this@MainActivity)

        @JavascriptInterface fun get(id: Int, url: String) { thread {
            try { val t = open(url).inputStream.bufferedReader().readText(); js("Bridge.done($id,true,${q(t)})") }
            catch (e: Exception) { js("Bridge.done($id,false,${q(e.message ?: "error")})") }
        } }

        @JavascriptInterface fun download(id: Int, url: String, rel: String) { thread {
            try { downloadTo(url, file(rel)); js("Bridge.done($id,true,${q(rel)})") }
            catch (e: Exception) { js("Bridge.done($id,false,${q(e.message ?: "error")})") }
        } }

        @JavascriptInterface fun exists(rel: String): Boolean = file(rel).exists()
        @JavascriptInterface fun readText(rel: String): String = file(rel).let { if (it.exists()) it.readText() else "" }
        @JavascriptInterface fun writeText(rel: String, text: String) { file(rel).apply { parentFile?.mkdirs(); writeText(text) } }
        @JavascriptInterface fun remove(rel: String) { file(rel).deleteRecursively() }
        @JavascriptInterface fun cacheSize(): Long = dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        @JavascriptInterface fun play(rel: String, fromMs: Int) { main.post {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file(rel).path)
                setOnCompletionListener { js("Bridge.ended()"); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                prepare()
                if (fromMs > 0) seekTo(fromMs)
                applySpeed(this)
                start()
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } }
        @JavascriptInterface fun pause() { main.post { player?.takeIf { it.isPlaying }?.pause(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        @JavascriptInterface fun resume() { main.post { player?.let { it.start(); applySpeed(it) }; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        @JavascriptInterface fun stop() { main.post { player?.release(); player = null; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        @JavascriptInterface fun seek(ms: Int) { main.post { player?.seekTo(ms) } }
        @JavascriptInterface fun setSpeed(s: Float) { main.post { speed = s; player?.let { if (it.isPlaying) applySpeed(it) } } }
        @JavascriptInterface fun position(): Int = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
        @JavascriptInterface fun duration(): Int = try { player?.duration ?: 0 } catch (e: Exception) { 0 }
        @JavascriptInterface fun playing(): Boolean = try { player?.isPlaying ?: false } catch (e: Exception) { false }

        @JavascriptInterface fun openUrl(url: String) { main.post { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

        @JavascriptInterface fun setBars(color: String, dark: Boolean) { main.post {
            val c = Color.parseColor(color)
            window.statusBarColor = c; window.navigationBarColor = c
            web.setBackgroundColor(c)
            var flags = window.decorView.systemUiVisibility
            flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        } }

        @JavascriptInterface fun installUpdate(id: Int, url: String) { thread {
            try {
                val dir = File(cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, "update.apk")
                downloadTo(url, apk) { p -> js("Bridge.progress($id,$p)") }
                main.post {
                    if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                        js("Bridge.done($id,false,'permission')")
                    } else {
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", apk)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        js("Bridge.done($id,true,'')")
                    }
                }
            } catch (e: Exception) { js("Bridge.done($id,false,${q(e.message ?: "error")})") }
        } }
    }

    private fun applySpeed(p: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= 23) try { p.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
    }
}

object BuildConfigVersion {
    fun name(a: Activity): String = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "1.0" } catch (e: Exception) { "1.0" }
}
