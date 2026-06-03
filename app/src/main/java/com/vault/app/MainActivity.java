package com.vault.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String LAN_URL       = "http://192.168.0.200:8000";
    private static final String TAILSCALE_URL = "http://100.100.10.10:8000";
    private static final int    PROBE_TIMEOUT = 3000;

    private volatile String       mResolvedServer = null;
    private WebView               mWebView;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler         mHandler  = new Handler(Looper.getMainLooper());

    // ── JS Bridge ─────────────────────────────────────────────────────────────
    public class VaultBridge {

        @JavascriptInterface
        public String getServerUrl() {
            return mResolvedServer != null ? mResolvedServer : TAILSCALE_URL;
        }

        @JavascriptInterface
        public String probeAndGetServer() {
            if (probe(LAN_URL))       { mResolvedServer = LAN_URL;       return LAN_URL; }
            if (probe(TAILSCALE_URL)) { mResolvedServer = TAILSCALE_URL; return TAILSCALE_URL; }
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#07070a"));
        setContentView(root);

        mWebView = new WebView(this);
        mWebView.setBackgroundColor(Color.parseColor("#07070a"));
        root.addView(mWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setupWebView();
        mWebView.loadUrl("file:///android_asset/index.html");
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        // Works on all API levels 21+ without any class-load issues
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

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
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setTextZoom(110);

        mWebView.addJavascriptInterface(new VaultBridge(), "VaultBridge");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
        });

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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mWebView.onResume();
        applyImmersive();
    }

    @Override protected void onPause()   { super.onPause();   mWebView.onPause(); }
    @Override protected void onDestroy() { mExecutor.shutdownNow(); mWebView.destroy(); super.onDestroy(); }
}
