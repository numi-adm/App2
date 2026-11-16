package com.rooo.dashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private WebView dashWebView;
    private WebView robloxWebView;
    private LinearLayout browserContainer;
    private TextView browserTitle;

    private int activeAccountIdx = -1;
    private boolean autoFillPending = false;
    private boolean browserOpen = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File dbFile = new File(getFilesDir(), "accounts.json");
        if (!dbFile.exists()) {
            copyAssetToFile("accounts.json", dbFile);
        }
        NativeBridge.initDb(dbFile.getAbsolutePath());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#1e1e1e"));

        // 1. Main Dashboard WebView
        dashWebView = new WebView(this);
        WebSettings ds = dashWebView.getSettings();
        ds.setJavaScriptEnabled(true);
        ds.setDomStorageEnabled(true);
        dashWebView.setBackgroundColor(Color.parseColor("#1e1e1e"));
        dashWebView.setWebChromeClient(new WebChromeClient());
        dashWebView.setWebViewClient(new WebViewClient());
        dashWebView.addJavascriptInterface(new JsBridge(), "NativeApp");
        dashWebView.loadUrl("file:///android_asset/dashboard.html");

        root.addView(dashWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Slide-up In-App Roblox Browser Panel
        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(LinearLayout.VERTICAL);
        browserContainer.setBackgroundColor(Color.parseColor("#181818"));
        browserContainer.setVisibility(View.GONE);

        // Top toolbar for the in-app Roblox browser
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#262626"));
        int pad = dp(8);
        topBar.setPadding(pad, pad, pad, pad);

        browserTitle = new TextView(this);
        browserTitle.setTextColor(Color.WHITE);
        browserTitle.setTextSize(13f);
        browserTitle.setText("Roblox Browser");
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(browserTitle, titleLp);

        Button btnGrab = makeBarButton("ดึง Cookie", "#1d7a34");
        btnGrab.setOnClickListener(v -> {
            int got = grabCookieInternal();
            if (got > 0) {
                notifyDashboard("ดึง cookie สำเร็จ!");
            } else {
                notifyDashboard("ยังไม่มี cookie — ล็อกอินให้เสร็จก่อน");
            }
        });
        topBar.addView(btnGrab);

        Button btnHide = makeBarButton("ย่อหน้าต่าง", "#444444");
        btnHide.setOnClickListener(v -> browserContainer.setVisibility(View.GONE));
        topBar.addView(btnHide);

        Button btnClose = makeBarButton("ปิด", "#a02828");
        btnClose.setOnClickListener(v -> closeRobloxBrowser());
        topBar.addView(btnClose);

        browserContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        robloxWebView = new WebView(this);
        WebSettings rs = robloxWebView.getSettings();
        rs.setJavaScriptEnabled(true);
        rs.setDomStorageEnabled(true);
        rs.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(robloxWebView, true);

        robloxWebView.setWebChromeClient(new WebChromeClient());
        robloxWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (autoFillPending && url != null && url.contains("/login") && activeAccountIdx >= 0) {
                    autoFillPending = false;
                    String js = NativeBridge.buildLoginJs(activeAccountIdx);
                    if (js != null && !js.isEmpty()) {
                        view.evaluateJavascript(js, null);
                    }
                }
            }
        });

        browserContainer.addView(robloxWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(browserContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private Button makeBarButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setBackgroundColor(Color.parseColor(hexColor));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    private void copyAssetToFile(String assetName, File outFile) {
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }

    private void clearBrowserSession(Runnable afterClear) {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(value -> {
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            robloxWebView.clearCache(true);
            robloxWebView.clearHistory();
            if (afterClear != null) afterClear.run();
        });
    }

    private void openAccountInWebView(int idx, boolean cleanSession, boolean autoFill) {
        activeAccountIdx = idx;
        browserOpen = true;
        String user = idx >= 0 ? NativeBridge.getAccountField(idx, "username") : "";
        String cookie = idx >= 0 ? NativeBridge.getAccountField(idx, "cookie") : "";

        browserTitle.setText(user != null && !user.isEmpty()
                ? ("ไอดี: " + user) : "Roblox Login (โหมดเปิดเอง)");
        browserContainer.setVisibility(View.VISIBLE);

        Runnable launch = () -> {
            if (!autoFill && cookie != null && cookie.length() > 100) {
                // Inject .ROBLOSECURITY cookie and open /home directly
                CookieManager cm = CookieManager.getInstance();
                cm.setCookie("https://www.roblox.com",
                        ".ROBLOSECURITY=" + cookie + "; Domain=.roblox.com; Path=/; Secure; HttpOnly");
                cm.flush();
                autoFillPending = false;
                robloxWebView.loadUrl("https://www.roblox.com/home");
            } else {
                autoFillPending = autoFill && (idx >= 0);
                robloxWebView.loadUrl("https://www.roblox.com/login");
            }
        };

        if (cleanSession) {
            clearBrowserSession(launch);
        } else {
            launch.run();
        }
    }

    private int grabCookieInternal() {
        CookieManager cm = CookieManager.getInstance();
        String raw1 = cm.getCookie("https://www.roblox.com");
        String raw2 = cm.getCookie("https://roblox.com");
        String combined = (raw1 != null ? raw1 : "") + "; " + (raw2 != null ? raw2 : "");
        String user = activeAccountIdx >= 0
                ? NativeBridge.getAccountField(activeAccountIdx, "username") : "";
        int savedIdx = NativeBridge.storeCookieFromHeader(
                activeAccountIdx, user, combined, "ดึงจากหน้าต่างในแอป");
        return savedIdx >= 0 ? 1 : 0;
    }

    private void closeRobloxBrowser() {
        browserOpen = false;
        autoFillPending = false;
        robloxWebView.loadUrl("about:blank");
        browserContainer.setVisibility(View.GONE);
    }

    private void notifyDashboard(String msg) {
        runOnUiThread(() -> {
            String safe = msg.replace("\\", "\\\\").replace("'", "\\'");
            dashWebView.evaluateJavascript(
                    "if(window.onNativeCookieUpdate) window.onNativeCookieUpdate('" + safe + "');",
                    null);
        });
    }

    @Override
    public void onBackPressed() {
        if (browserContainer.getVisibility() == View.VISIBLE) {
            if (robloxWebView.canGoBack()) {
                robloxWebView.goBack();
            } else {
                browserContainer.setVisibility(View.GONE);
            }
            return;
        }
        super.onBackPressed();
    }

    private class JsBridge {
        @JavascriptInterface
        public String getAccountsJson() {
            return NativeBridge.getAccountsJson();
        }

        @JavascriptInterface
        public String getLiveStatus() {
            if (browserOpen) {
                return "{\"open\":[" + Math.max(0, activeAccountIdx) + "],\"manual\":[]}";
            }
            return "{\"open\":[],\"manual\":[]}";
        }

        @JavascriptInterface
        public int addAccount(String username, String password) {
            return NativeBridge.addAccount(username, password, "");
        }

        @JavascriptInterface
        public int importText(String text) {
            return NativeBridge.importText(text);
        }

        @JavascriptInterface
        public int deleteIndices(String jsonIndices) {
            try {
                JSONArray arr = new JSONArray(jsonIndices);
                int[] idxs = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    idxs[i] = arr.getInt(i);
                }
                return NativeBridge.deleteIndices(idxs);
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public void openAccount(int idx, boolean incognito) {
            runOnUiThread(() -> openAccountInWebView(idx, incognito, true));
        }

        @JavascriptInterface
        public void manualOpen(int idx, boolean incognito) {
            runOnUiThread(() -> openAccountInWebView(idx, incognito, false));
        }

        @JavascriptInterface
        public void dumpCookie(int idx) {
            runOnUiThread(() -> openAccountInWebView(idx, true, true));
        }

        @JavascriptInterface
        public int grabLiveCookie() {
            return grabCookieInternal();
        }

        @JavascriptInterface
        public void closeBrowser() {
            runOnUiThread(MainActivity.this::closeRobloxBrowser);
        }

        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("roblox", text));
            }
        }
    }
}
