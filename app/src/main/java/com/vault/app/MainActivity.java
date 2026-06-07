package com.vault.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.FrameLayout;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;

public class MainActivity extends Activity {

    private static final String LAN_URL       = "http://192.168.0.200:8000";
    private static final String TAILSCALE_URL = "http://100.100.10.10:8000";
    private static final int    PROBE_TIMEOUT = 3000;
    private static final int    PERM_REQUEST  = 101;

    private volatile String       mResolvedServer = null;
    private WebView               mWebView;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler         mHandler  = new Handler(Looper.getMainLooper());

    // Pending JS callback waiting for permission grant
    private String mPendingPermCallback = null;

    // ── JS Bridge ─────────────────────────────────────────────────────────────
    public class VaultBridge {

        /** Hide status bar — call when opening image/video viewer */
        @JavascriptInterface
        public void hideStatusBar() {
            mHandler.post(() -> {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            });
        }

        /** Show status bar — call when closing image/video viewer */
        @JavascriptInterface
        public void showStatusBar() {
            mHandler.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            });
        }

        /** Returns status bar height in CSS pixels (dp) for the sidebar offset */
        @JavascriptInterface
        public int getStatusBarHeight() {
            int resourceId = getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                // Convert px to dp
                float density = getResources().getDisplayMetrics().density;
                return Math.round(getResources().getDimensionPixelSize(resourceId) / density);
            }
            return 24; // safe fallback
        }

        /** Returns last resolved server URL (used at page load) */
        @JavascriptInterface
        public String getServerUrl() {
            return mResolvedServer != null ? mResolvedServer : TAILSCALE_URL;
        }

        /**
         * Async server probe — runs on background thread, calls JS callback
         * with the resolved URL or empty string when done.
         * callback: name of a JS function to call with the result string
         */
        @JavascriptInterface
        public void probeServer(final String callback) {
            mExecutor.execute(() -> {
                // Step 1: try LAN
                notifyProbeStatus(callback, "status", "Trying LAN\u2026");
                if (probe(LAN_URL)) {
                    mResolvedServer = LAN_URL;
                    notifyProbeResult(callback, LAN_URL);
                    return;
                }
                // Step 2: try Tailscale
                notifyProbeStatus(callback, "status", "LAN unavailable \u2014 trying Tailscale\u2026");
                if (probe(TAILSCALE_URL)) {
                    mResolvedServer = TAILSCALE_URL;
                    notifyProbeResult(callback, TAILSCALE_URL);
                    return;
                }
                // Both failed
                notifyProbeResult(callback, "");
            });
        }

        /** Close the app cleanly — called by leaveVault confirmation */
        @JavascriptInterface
        public void exitApp() {
            mHandler.post(() -> {
                // Clear all WebView state
                mWebView.clearCache(true);
                mWebView.clearHistory();
                mWebView.clearFormData();
                mWebView.loadUrl("about:blank");
                finish();
            });
        }

        /**
         * List media subdirectories under a root path.
         * Returns a JSON array of {name, path} objects.
         * Pass "" or "root" to list top-level storage roots.
         */
        @JavascriptInterface
        public String listDirectories(String parentPath) {
            JSONArray result = new JSONArray();
            try {
                List<File> dirs = new ArrayList<>();

                if (parentPath == null || parentPath.isEmpty() || parentPath.equals("root")) {
                    // Top-level: return internal storage + common media folders
                    File extStorage = Environment.getExternalStorageDirectory();
                    if (extStorage != null && extStorage.exists()) {
                        dirs.add(extStorage);
                        // Add common media folders
                        String[] common = {"DCIM", "Pictures", "Movies", "Music",
                                           "Downloads", "Videos", "WhatsApp/Media"};
                        for (String rel : common) {
                            File f = new File(extStorage, rel);
                            if (f.exists() && f.isDirectory()) dirs.add(f);
                        }
                    }
                } else {
                    // List subdirectories of given path
                    File parent = new File(parentPath);
                    if (parent.exists() && parent.isDirectory()) {
                        File[] children = parent.listFiles();
                        if (children != null) {
                            for (File f : children) {
                                if (f.isDirectory() && !f.isHidden()) dirs.add(f);
                            }
                            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                        }
                    }
                }

                for (File d : dirs) {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("name", d.getName());
                    obj.put("path", d.getAbsolutePath());
                    result.put(obj);
                }
            } catch (Exception e) {
                // Return empty array on error
            }
            return result.toString();
        }

        /**
         * List all media files (images, videos, audio) under a directory path.
         * Returns JSON array of {name, path, type, size} objects.
         */
        @JavascriptInterface
        public String listMediaFiles(String dirPath) {
            JSONArray result = new JSONArray();
            try {
                File dir = new File(dirPath);
                if (!dir.exists() || !dir.isDirectory()) return result.toString();
                collectMediaFiles(dir, result, 0);
            } catch (Exception e) { /* ignore */ }
            return result.toString();
        }

        /** Request storage permission, calling jsCallback(granted: bool) when done */
        @JavascriptInterface
        public void requestStoragePermission(String jsCallback) {
            if (hasStoragePermission()) {
                callJs(jsCallback + "(true)");
                return;
            }
            mPendingPermCallback = jsCallback;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                }, PERM_REQUEST);
            } else {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }, PERM_REQUEST);
            }
        }

        /** Check if storage permission is granted */
        @JavascriptInterface
        public boolean hasStoragePermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
            } else {
                return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void notifyProbeStatus(String cb, String type, String msg) {
        String safe = msg.replace("'", "\\'");
        callJs("window._probeUpdate && window._probeUpdate('" + type + "','" + safe + "')");
    }

    private void notifyProbeResult(String cb, String url) {
        String safe = url.replace("'", "\\'");
        callJs(cb + "('" + safe + "')");
    }

    private void callJs(final String js) {
        mHandler.post(() -> {
            if (mWebView != null) mWebView.evaluateJavascript(js, null);
        });
    }

    // Recursively collect media files, max depth 6
    private void collectMediaFiles(File dir, JSONArray result, int depth) {
        if (depth > 6) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                String name = f.getName().toLowerCase();
                String type = null;
                if (name.endsWith(".jpg")||name.endsWith(".jpeg")||name.endsWith(".png")
                    ||name.endsWith(".gif")||name.endsWith(".webp")||name.endsWith(".heic")
                    ||name.endsWith(".bmp")||name.endsWith(".avif")) type = "image";
                else if (name.endsWith(".mp4")||name.endsWith(".mkv")||name.endsWith(".mov")
                    ||name.endsWith(".avi")||name.endsWith(".webm")||name.endsWith(".m4v")) type = "video";
                else if (name.endsWith(".mp3")||name.endsWith(".flac")||name.endsWith(".wav")
                    ||name.endsWith(".aac")||name.endsWith(".ogg")||name.endsWith(".m4a")) type = "audio";
                if (type != null) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("name", f.getName());
                        obj.put("path", f.getAbsolutePath());
                        obj.put("type", type);
                        obj.put("size", f.length());
                        obj.put("modified", f.lastModified() / 1000L);
                        result.put(obj);
                    } catch (Exception ignored) {}
                }
            } else if (f.isDirectory() && !f.isHidden()) {
                collectMediaFiles(f, result, depth + 1);
            }
        }
    }

    // ── Permissions callback ──────────────────────────────────────────────────
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_REQUEST && mPendingPermCallback != null) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            callJs(mPendingPermCallback + "(" + granted + ")");
            mPendingPermCallback = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep title bar removed, status bar visible and solid
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Do NOT use FLAG_TRANSLUCENT_STATUS — it causes layout issues with WebView touch targets

        // Root layout
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#07070a"));
        setContentView(root);

        // WebView fills everything including under the status bar
        mWebView = new WebView(this);
        mWebView.setBackgroundColor(Color.parseColor("#07070a"));
        root.addView(mWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setupWebView();
        mWebView.loadUrl("file:///android_asset/index.html");
    }

    // ── WebView setup ─────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // No cache — private vault
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setTextZoom(110);

        mWebView.addJavascriptInterface(new VaultBridge(), "VaultBridge");
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                // Status bar is solid — no inset injection needed.
                // Set --sat to 0 so topbar/viewer padding calculations are correct.
                view.evaluateJavascript(
                    "document.documentElement.style.setProperty('--sat','0px')", null);
            }
        });

        // Full-screen video support
        mWebView.setWebChromeClient(new WebChromeClient() {
            private View mCustomView;
            private CustomViewCallback mCb;
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (mCustomView != null) { cb.onCustomViewHidden(); return; }
                mCustomView = view; mCb = cb;
                ((FrameLayout) getWindow().getDecorView())
                    .addView(mCustomView, new FrameLayout.LayoutParams(-1, -1));
                mWebView.setVisibility(View.GONE);
            }
            @Override
            public void onHideCustomView() {
                if (mCustomView == null) return;
                ((FrameLayout) getWindow().getDecorView()).removeView(mCustomView);
                mCustomView = null;
                mWebView.setVisibility(View.VISIBLE);
                if (mCb != null) mCb.onCustomViewHidden();
            }
        });
    }

    private boolean probe(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
            c.setConnectTimeout(PROBE_TIMEOUT);
            c.setReadTimeout(PROBE_TIMEOUT);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 400;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Back button — always delegate to JS handler ───────────────────────────
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Inject a JS back event — the page manages its own history stack
            mWebView.evaluateJavascript("window._handleAndroidBack && window._handleAndroidBack()", null);
            return true; // always consume — JS decides whether to show exit modal
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override protected void onResume()  { super.onResume();  mWebView.onResume(); }
    @Override protected void onPause()   { super.onPause();   mWebView.onPause(); }
    @Override protected void onDestroy() {
        mWebView.clearCache(true);
        mWebView.clearHistory();
        mExecutor.shutdownNow();
        mWebView.destroy();
        super.onDestroy();
    }
}
