package com.rooo.dashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
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
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_SAVE_CSV = 1002;

    private static final String JS_INSPECT_PAGE =
            "(function(){" +
            "  var txt = document.body ? (document.body.innerText || '') : '';" +
            "  try {" +
            "    document.querySelectorAll('*').forEach(function(el){" +
            "      if(el.shadowRoot) txt += '\\n' + (el.shadowRoot.innerText || el.shadowRoot.textContent || '');" +
            "    });" +
            "  } catch(e){}" +
            "  var ark = false;" +
            "  try {" +
            "    ark = Array.from(document.querySelectorAll('iframe')).some(function(f){" +
            "      return /arkose|funcaptcha|captcha/i.test(f.src || '') && (f.offsetWidth > 0 || f.offsetHeight > 0);" +
            "    });" +
            "  } catch(e){}" +
            "  return JSON.stringify({url: location.href, text: txt.slice(0, 3000), arkose: ark});" +
            "})();";

    private WebView dashWebView;
    private WebView robloxWebView;
    private LinearLayout browserContainer;
    private TextView browserTitle;
    private TextView browserStatusBadge;

    private ValueCallback<Uri[]> fileUploadCallback;
    private String pendingCsvExport = "";

    private int activeAccountIdx = -1;
    private boolean autoFillPending = false;
    private boolean browserOpen = false;
    private boolean currentCleanSession = true;
    private boolean currentAutoFill = true;
    private boolean autoCheckMode = false;

    private final List<Integer> accountQueue = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long accountOpenedAt = 0;

    private final Runnable liveInspectorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!browserOpen || browserContainer.getVisibility() != View.VISIBLE) {
                return;
            }
            inspectCurrentRobloxPage();
            mainHandler.postDelayed(this, 1600);
        }
    };

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
        ds.setAllowFileAccess(true);
        ds.setAllowContentAccess(true);
        dashWebView.setBackgroundColor(Color.parseColor("#1e1e1e"));
        dashWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    String[] mimeTypes = {"text/csv", "text/comma-separated-values", "text/plain", "*/*"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    startActivityForResult(Intent.createChooser(intent, "เลือกไฟล์ Sheet (.csv)"), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });
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

        // Top toolbar row 1: Account title & action buttons
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#262626"));
        int pad = dp(6);
        topBar.setPadding(pad, pad, pad, pad);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        browserTitle = new TextView(this);
        browserTitle.setTextColor(Color.WHITE);
        browserTitle.setTextSize(13f);
        browserTitle.setText("Roblox Browser");
        titleCol.addView(browserTitle);

        browserStatusBadge = new TextView(this);
        browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
        browserStatusBadge.setTextSize(11f);
        browserStatusBadge.setText("กำลังโหลดและเช็คสถานะ...");
        titleCol.addView(browserStatusBadge);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleCol, titleLp);

        Button btnGrab = makeBarButton("ดึง Cookie", "#1d7a34");
        btnGrab.setOnClickListener(v -> {
            int got = grabCookieInternal();
            if (got > 0) {
                browserStatusBadge.setText("สถานะ: WORK (บันทึก Cookie แล้ว!)");
                browserStatusBadge.setTextColor(Color.parseColor("#44ff77"));
                notifyDashboard("ดึง cookie สำเร็จ!");
            } else {
                inspectCurrentRobloxPage();
                notifyDashboard("ยังไม่มี cookie — เช็คสถานะบนแถบด้านบน");
            }
        });
        topBar.addView(btnGrab);

        Button btnStopAll = makeBarButton("หน้าหลัก", "#444444");
        btnStopAll.setOnClickListener(v -> stopQueueAndCloseBrowser());
        topBar.addView(btnStopAll);

        Button btnCloseNext = makeBarButton("ปิด / ถัดไป ⏭", "#a02828");
        btnCloseNext.setOnClickListener(v -> closeAndOpenNextAccount());
        topBar.addView(btnCloseNext);

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
                mainHandler.postDelayed(() -> inspectCurrentRobloxPage(), 600);
            }
        });

        browserContainer.addView(robloxWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(browserContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void inspectCurrentRobloxPage() {
        if (!browserOpen || activeAccountIdx < 0) return;

        final int idx = activeAccountIdx;
        robloxWebView.evaluateJavascript(JS_INSPECT_PAGE, value -> {
            if (!browserOpen || activeAccountIdx != idx) return;
            String url = robloxWebView.getUrl() != null ? robloxWebView.getUrl() : "";
            String pageText = "";
            boolean hasArkose = false;

            try {
                if (value != null && value.length() > 2 && !value.equals("null")) {
                    // Unwrap JSON string returned by evaluateJavascript
                    String rawJson = value;
                    if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                        rawJson = new org.json.JSONTokener(rawJson).nextValue().toString();
                    }
                    JSONObject obj = new JSONObject(rawJson);
                    url = obj.optString("url", url);
                    pageText = obj.optString("text", "");
                    hasArkose = obj.optBoolean("arkose", false);
                }
            } catch (Exception ignored) {
            }

            CookieManager cm = CookieManager.getInstance();
            String c1 = cm.getCookie("https://www.roblox.com");
            String c2 = cm.getCookie("https://roblox.com");
            String combinedCookies = (c1 != null ? c1 : "") + "; " + (c2 != null ? c2 : "");

            String resJson = NativeBridge.classifyPage(idx, url, pageText, hasArkose, combinedCookies);
            try {
                JSONObject res = new JSONObject(resJson);
                String st = res.optString("status", "UNKNOWN");
                String note = res.optString("note", "");
                boolean hasCk = res.optBoolean("hasCookie", false);

                if (hasCk) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#44ff77"));
                    notifyDashboard(null);
                    if (autoCheckMode && (System.currentTimeMillis() - accountOpenedAt > 1800)) {
                        mainHandler.postDelayed(this::closeAndOpenNextAccount, 700);
                    }
                } else if (!st.equals("UNKNOWN")) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    if (st.equals("BAN") || st.equals("BADPASS")) {
                        browserStatusBadge.setTextColor(Color.parseColor("#ff6666"));
                        notifyDashboard(null);
                        if (autoCheckMode && (System.currentTimeMillis() - accountOpenedAt > 2200)) {
                            mainHandler.postDelayed(this::closeAndOpenNextAccount, 900);
                        }
                    } else if (st.equals("VERIFY") || st.equals("CAPTCHA") || st.equals("2FA") || st.equals("RATE")) {
                        browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
                        notifyDashboard(null);
                    }
                } else {
                    browserStatusBadge.setText("กำลังล็อกอิน / ตรวจสอบสถานะ...");
                    browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        } else if (requestCode == REQ_SAVE_CSV) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null && pendingCsvExport != null) {
                        out.write(pendingCsvExport.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        notifyDashboard("บันทึกไฟล์ Sheet (.csv) เรียบร้อยแล้ว!");
                    }
                } catch (Exception e) {
                    notifyDashboard("บันทึกไฟล์ไม่สำเร็จ: " + e.getMessage());
                }
            }
        }
    }

    private Button makeBarButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11.5f);
        b.setBackgroundColor(Color.parseColor(hexColor));
        int hp = dp(8);
        int vp = dp(4);
        b.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
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

    private void startAccountQueue(int[] idxs, boolean cleanSession, boolean autoFill, boolean isCheckMode) {
        accountQueue.clear();
        int total = NativeBridge.getAccountCount();
        if (idxs != null && idxs.length > 0) {
            for (int i : idxs) {
                if (i >= 0 && i < total) accountQueue.add(i);
            }
            // If user clicked a single row button (e.g. row #1), queue subsequent rows so "ปิด / ถัดไป" opens #2, #3...
            if (idxs.length == 1 && idxs[0] >= 0) {
                for (int next = idxs[0] + 1; next < total; next++) {
                    accountQueue.add(next);
                }
            }
        }
        currentCleanSession = cleanSession;
        currentAutoFill = autoFill;
        autoCheckMode = isCheckMode;

        if (!accountQueue.isEmpty()) {
            int first = accountQueue.remove(0);
            openAccountInWebView(first, cleanSession, autoFill);
        } else {
            openAccountInWebView(-1, cleanSession, false);
        }
    }

    private void openAccountInWebView(int idx, boolean cleanSession, boolean autoFill) {
        activeAccountIdx = idx;
        browserOpen = true;
        accountOpenedAt = System.currentTimeMillis();

        int total = NativeBridge.getAccountCount();
        String user = idx >= 0 ? NativeBridge.getAccountField(idx, "username") : "";
        String cookie = idx >= 0 ? NativeBridge.getAccountField(idx, "cookie") : "";

        String qInfo = !accountQueue.isEmpty() ? (" [เหลือคิวอีก " + accountQueue.size() + "]") : "";
        browserTitle.setText(idx >= 0
                ? ("#" + (idx + 1) + "/" + total + " " + user + qInfo)
                : "Roblox Login (โหมดเปิดเอง)");
        browserStatusBadge.setText("กำลังเตรียมเซสชันและกรอกรหัส...");
        browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
        browserContainer.setVisibility(View.VISIBLE);

        mainHandler.removeCallbacks(liveInspectorRunnable);

        Runnable launch = () -> {
            if (cookie != null && cookie.length() > 100) {
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
            mainHandler.postDelayed(liveInspectorRunnable, 1500);
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

    /* Called when user presses "ปิด / ถัดไป ⏭": grabs any cookie first, then opens next account in queue! */
    private void closeAndOpenNextAccount() {
        grabCookieInternal();
        notifyDashboard(null);

        if (!accountQueue.isEmpty()) {
            int nextIdx = accountQueue.remove(0);
            openAccountInWebView(nextIdx, currentCleanSession, currentAutoFill);
        } else {
            stopQueueAndCloseBrowser();
            notifyDashboard("ครบทุกไอดีในคิวแล้ว!");
        }
    }

    private void stopQueueAndCloseBrowser() {
        grabCookieInternal();
        accountQueue.clear();
        browserOpen = false;
        autoFillPending = false;
        autoCheckMode = false;
        mainHandler.removeCallbacks(liveInspectorRunnable);
        robloxWebView.loadUrl("about:blank");
        browserContainer.setVisibility(View.GONE);
        notifyDashboard(null);
    }

    private void notifyDashboard(String msg) {
        runOnUiThread(() -> {
            if (msg == null) {
                dashWebView.evaluateJavascript(
                        "if(window.onNativeCookieUpdate) window.onNativeCookieUpdate('');",
                        null);
            } else {
                String safe = msg.replace("\\", "\\\\").replace("'", "\\'");
                dashWebView.evaluateJavascript(
                        "if(window.onNativeCookieUpdate) window.onNativeCookieUpdate('" + safe + "');",
                        null);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (browserContainer.getVisibility() == View.VISIBLE) {
            if (robloxWebView.canGoBack()) {
                robloxWebView.goBack();
            } else {
                stopQueueAndCloseBrowser();
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
                return "{\"open\":[" + Math.max(0, activeAccountIdx) + "],\"manual\":[],\"queue\":" + accountQueue.size() + "}";
            }
            return "{\"open\":[],\"manual\":[],\"queue\":0}";
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
        public int importCsv(String csvText) {
            return NativeBridge.importCsv(csvText);
        }

        @JavascriptInterface
        public String exportCsv(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            String csv = NativeBridge.exportCsv(idxs);
            pendingCsvExport = csv != null ? csv : "";
            runOnUiThread(() -> {
                try {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/csv");
                    intent.putExtra(Intent.EXTRA_TITLE, "roblox_accounts_" + stamp + ".csv");
                    startActivityForResult(intent, REQ_SAVE_CSV);
                } catch (Exception e) {
                    notifyDashboard("ไม่สามารถเปิดหน้าต่างบันทึกไฟล์ได้: " + e.getMessage());
                }
            });
            return pendingCsvExport;
        }

        @JavascriptInterface
        public int deleteIndices(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            return idxs != null ? NativeBridge.deleteIndices(idxs) : 0;
        }

        private int[] parseIntArray(String jsonIndices) {
            if (jsonIndices == null || jsonIndices.isEmpty()) return null;
            try {
                JSONArray arr = new JSONArray(jsonIndices);
                int[] idxs = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    idxs[i] = arr.getInt(i);
                }
                return idxs;
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void openAccountsQueue(String jsonIndices, boolean incognito) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, incognito, true, false));
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, true));
        }

        @JavascriptInterface
        public void manualOpen(int idx, boolean incognito) {
            int[] idxs = idx >= 0 ? new int[]{idx} : null;
            runOnUiThread(() -> startAccountQueue(idxs, incognito, false, false));
        }

        @JavascriptInterface
        public void dumpCookiesQueue(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, false));
        }

        @JavascriptInterface
        public int grabLiveCookie() {
            return grabCookieInternal();
        }

        @JavascriptInterface
        public void closeBrowser() {
            runOnUiThread(MainActivity.this::stopQueueAndCloseBrowser);
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
