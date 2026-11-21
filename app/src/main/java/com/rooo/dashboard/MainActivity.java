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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_SAVE_CSV = 1002;

    private static final String JS_INSPECT_PAGE =
            "(function(){" +
            "  function deep(el, depth){" +
            "    var out = '';" +
            "    if(!el || depth > 3) return out;" +
            "    try { out += (el.innerText || el.textContent || '') + '\\n'; } catch(e){}" +
            "    try { if(el.shadowRoot) out += deep(el.shadowRoot, depth + 1); } catch(e){}" +
            "    return out;" +
            "  }" +
            "  var dlg = '';" +
            "  try {" +
            "    var sels = ['[role=\"dialog\"]','[class*=modal]','[class*=Modal]','[data-testid*=modal]'];" +
            "    sels.forEach(function(s){" +
            "      try {" +
            "        document.querySelectorAll(s).forEach(function(el){ dlg += deep(el, 0) + '\\n'; });" +
            "      } catch(e){}" +
            "    });" +
            "  } catch(e){}" +
            "  try {" +
            "    Array.from(document.querySelectorAll('iframe')).forEach(function(f){" +
            "      try { var d = f.contentDocument; if(d && d.body) dlg += deep(d.body, 0) + '\\n'; } catch(e){}" +
            "    });" +
            "  } catch(e){}" +
            "  var txt = '';" +
            "  try { txt = deep(document.body, 0); } catch(e){}" +
            "  var ark = false;" +
            "  try {" +
            "    ark = Array.from(document.querySelectorAll('iframe')).some(function(f){" +
            "      return /arkose|funcaptcha|captcha/i.test(f.src || '') && (f.offsetWidth > 0 || f.offsetHeight > 0);" +
            "    });" +
            "  } catch(e){}" +
            "  var all = dlg + '\\n' + txt;" +
            "  return JSON.stringify({url: location.href, text: all.slice(0, 12000), arkose: ark});" +
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
    private String noticedStatus = "";
    private String lastStatus = "";
    private String lastNote = "";
    private String lastGrabStatus = "";
    private int autoRetryCount = 0;
    private long loginCycleStart = 0;
    private long lastRetryAt = 0;
    private int openToken = 0;
    private boolean skipScheduled = false;

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
            mainHandler.postDelayed(this, 1500);
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

        // 2. Slide-up In-App Roblox Browser Panel (Single WebView)
        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(LinearLayout.VERTICAL);
        browserContainer.setBackgroundColor(Color.parseColor("#181818"));
        browserContainer.setVisibility(View.GONE);

        // Top toolbar: Account title & action buttons
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
                boolean ok = "WORK".equals(lastGrabStatus);
                browserStatusBadge.setText("สถานะ: " + lastGrabStatus + " (บันทึก Cookie แล้ว)");
                browserStatusBadge.setTextColor(Color.parseColor(ok ? "#44ff77" : "#ff8844"));
                if (ok) {
                    notifyDashboard("ดึง cookie สำเร็จ!");
                } else {
                    notifyDashboard("บันทึก cookie แล้ว แต่สถานะคือ " + lastGrabStatus
                            + " — " + (lastNote != null && lastNote.length() > 0 ? lastNote : "กด Continue/ยืนยันในหน้าต่างก่อน"));
                }
            } else {
                inspectCurrentRobloxPage();
                notifyDashboard("ยังไม่มี cookie — เช็คสถานะบนแถบด้านบน");
            }
        });
        topBar.addView(btnGrab);

        Button btnRetry = makeBarButton("ลองใหม่", "#8a5a00");
        btnRetry.setOnClickListener(v -> retryCurrentAccount());
        topBar.addView(btnRetry);

        // แถบ "หน้าหลัก" สำหรับหยุดกลางทางและกลับหน้าหลักทันที
        Button btnHome = makeBarButton("หน้าหลัก", "#444444");
        btnHome.setOnClickListener(v -> {
            stopQueueAndCloseBrowser();
            notifyDashboard("หยุดการทำงานและกลับสู่หน้าหลักแล้ว");
        });
        topBar.addView(btnHome);

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
                    loginCycleStart = System.currentTimeMillis();
                    mainHandler.removeCallbacks(stuckCheckRunnable);
                    // เริ่มจับเวลา 10 วินาที — ครบแล้วไม่เปลี่ยนเป็นติดกดเพื่อยืนยัน (VERIFY) จะข้ามไอดีตามเดิม
                    mainHandler.postDelayed(stuckCheckRunnable, 10000);
                }
                mainHandler.postDelayed(() -> inspectCurrentRobloxPage(), 500);
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
                lastStatus = st;
                lastNote = note;

                // รีเซ็ตหน้าเว็บลองใหม่อัตโนมัติ: เฉพาะกรณี "รหัสไม่ตรง" (BADPASS) หรือ "เกิดข้อผิดพลาดไม่ทราบสาเหตุ" (RETRY) เท่านั้น
                if ("BADPASS".equals(st) || "RETRY".equals(st)) {
                    maybeAutoRetry(st);
                }

                if (hasCk) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#44ff77"));
                    notifyDashboard(null);
                    if (autoCheckMode && (System.currentTimeMillis() - accountOpenedAt > 4000)) {
                        mainHandler.postDelayed(this::closeAndOpenNextAccount, 700);
                    }
                } else if ("VERIFY".equals(st)) {
                    // ติดกดเพื่อยืนยัน (Press and hold) — ให้ผู้ใช้กดเองในหน้าต่างได้ ไม่รีเซ็ต ไม่ตัดข้ามทันที
                    browserStatusBadge.setText("สถานะ: VERIFY — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#ffaa00"));
                    notifyDashboard(null);
                } else if (!"UNKNOWN".equals(st)) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);

                    if (isSkipStatus(st)) {
                        /* LOCKED / RECOVERY / 2FA / CAPTCHA / BAN → ข้ามไอดีนี้ (ไม่รีเซ็ตหน้า) */
                        browserStatusBadge.setTextColor(Color.parseColor("#ff8844"));
                        notifyDashboard(null);
                        if (!noticedStatus.equals(st)) {
                            noticedStatus = st;
                            notifyDashboard("ติด " + st + " — ข้ามไอดีนี้ ไปตัวถัดไป");
                        }
                        if (!skipScheduled) {
                            skipScheduled = true;
                            mainHandler.postDelayed(this::skipCurrentAccount, 1200);
                        }
                    } else if ("BADPASS".equals(st)) {
                        browserStatusBadge.setTextColor(Color.parseColor("#ff6666"));
                        notifyDashboard(null);
                    } else if ("RETRY".equals(st)) {
                        browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
                        notifyDashboard(null);
                    } else if ("RATE".equals(st)) {
                        browserStatusBadge.setTextColor(Color.parseColor("#ffaa44"));
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
        noticedStatus = "";
        lastStatus = "";
        lastNote = "";
        autoRetryCount = 0;
        loginCycleStart = 0;
        lastRetryAt = 0;
        skipScheduled = false;
        openToken++;
        mainHandler.removeCallbacks(stuckCheckRunnable);

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
            if (!autoCheckMode && cookie != null && cookie.length() > 100) {
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

    /* สถานะที่ห้ามทับด้วย WORK ตอนดึง cookie */
    private static boolean isBlockingStatus(String st) {
        return "LOCKED".equals(st) || "BAN".equals(st) || "VERIFY".equals(st)
                || "RECOVERY".equals(st) || "CAPTCHA".equals(st) || "2FA".equals(st);
    }

    private int grabCookieInternal() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        String raw1 = cm.getCookie("https://www.roblox.com");
        String raw2 = cm.getCookie("https://roblox.com");
        String combined = (raw1 != null ? raw1 : "") + "; " + (raw2 != null ? raw2 : "");
        String user = activeAccountIdx >= 0
                ? NativeBridge.getAccountField(activeAccountIdx, "username") : "";
        int savedIdx = NativeBridge.storeCookieFromHeader(
                activeAccountIdx, user, combined, "ดึงจากหน้าต่างในแอป");
        if (savedIdx >= 0) {
            String st = lastStatus != null ? lastStatus : "";
            String note = "ดึงจากหน้าต่างในแอป";
            if (isBlockingStatus(st)) {
                if (lastNote != null && lastNote.length() > 0) note = lastNote;
            } else {
                st = "WORK";
            }
            NativeBridge.updateStatus(savedIdx, st, note);
            lastGrabStatus = st;
            lastNote = note;
        }
        return savedIdx >= 0 ? 1 : 0;
    }

    /* ---------- เช็ค cookie ว่าตรงกับชื่อในชีตไหม ---------- */
    private void startCookieCheck(int[] idxs) {
        final int total = NativeBridge.getAccountCount();
        final List<Integer> list = new ArrayList<>();
        if (idxs != null && idxs.length > 0) {
            for (int i : idxs) {
                if (i >= 0 && i < total) list.add(i);
            }
        } else {
            for (int i = 0; i < total; i++) list.add(i);
        }
        if (list.isEmpty()) {
            notifyDashboard("ไม่มีไอดีให้เช็ค");
            return;
        }
        notifyDashboard("กำลังเช็ค cookie " + list.size() + " ไอดี...");

        final ExecutorService pool = Executors.newFixedThreadPool(5);
        final AtomicInteger left = new AtomicInteger(list.size());
        for (final int idx : list) {
            pool.submit(() -> {
                try {
                    checkOneCookie(idx);
                } catch (Throwable ignored) {
                } finally {
                    if (left.decrementAndGet() == 0) {
                        pool.shutdown();
                        notifyDashboard("เช็ค cookie เสร็จแล้ว " + list.size() + " ไอดี");
                    }
                }
            });
        }
    }

    private void checkOneCookie(int idx) {
        String user = NativeBridge.getAccountField(idx, "username");
        String cookie = NativeBridge.getAccountField(idx, "cookie");
        if (cookie == null || cookie.length() < 100) {
            NativeBridge.updateStatus(idx, "NOCOOKIE",
                    "ยังไม่มี cookie — เปิดไอดีแล้วล็อกอิน/ดึง cookie ก่อน");
            return;
        }
        String name = null;
        int code = 0;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://users.roblox.com/v1/users/authenticated");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Cookie", ".ROBLOSECURITY=" + cookie);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                JSONObject jo = new JSONObject(bo.toString("UTF-8"));
                name = jo.optString("name", "");
            }
        } catch (Throwable e) {
            code = -1;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        String status;
        String note;
        if (name != null && name.length() > 0) {
            if (user == null || user.length() == 0) {
                NativeBridge.setUsername(idx, name);
                user = name;
            }
            if (user != null && user.length() > 0 && !name.equalsIgnoreCase(user)) {
                status = "MISMATCH";
                note = "cookie เป็นของบัญชี '" + name + "' ไม่ตรงกับชื่อในชีต '" + user + "'";
            } else {
                status = "WORK";
                note = "cookie ตรงกับชื่อผู้ใช้ '" + name + "' — ใช้ได้";
            }
        } else if (code == 401 || code == 403) {
            status = "BADPASS";
            note = "cookie หมดอายุ/ใช้ไม่ได้";
        } else {
            status = "ERROR";
            note = "ติดต่อ Roblox ไม่ได้ (code " + code + ")";
        }
        NativeBridge.updateStatus(idx, status, note);
    }

    /* ปุ่ม "ลองใหม่": เริ่มไอดีปัจจุบันใหม่ */
    private void retryCurrentAccount() {
        if (!browserOpen) {
            notifyDashboard("ยังไม่มีหน้าต่างที่เปิดอยู่ — กดเช็ก/ดึง cookie ก่อน");
            return;
        }
        int idx = activeAccountIdx;
        if (idx >= 0) {
            notifyDashboard("ลองใหม่ไอดี #" + (idx + 1) + "...");
            openAccountInWebView(idx, true, currentAutoFill);
        } else {
            notifyDashboard("ลองใหม่ (โหมดเปิดเอง)...");
            clearBrowserSession(() -> {
                robloxWebView.loadUrl("https://www.roblox.com/login");
                mainHandler.postDelayed(liveInspectorRunnable, 1500);
            });
        }
    }

    /* ข้ามไอดี: สถานะที่ไม่ต้องรอและไม่ต้องรีเซ็ตหน้า */
    private static boolean isSkipStatus(String st) {
        return "LOCKED".equals(st) || "RECOVERY".equals(st)
                || "2FA".equals(st) || "CAPTCHA".equals(st) || "BAN".equals(st);
    }

    private void skipCurrentAccount() {
        if (!browserOpen) return;
        int idx = activeAccountIdx;
        String st = lastStatus == null ? "" : lastStatus;
        if (idx >= 0) {
            notifyDashboard("ข้ามไอดี #" + (idx + 1)
                    + (st.isEmpty() ? " (ยังไม่ผ่าน)" : " (ติด " + st + ")")
                    + " — ไปตัวถัดไป");
        }
        closeAndOpenNextAccount();
    }

    /*
     * ถ้าครบ 10 วินาทีแล้วจากตรงล็อกอิน ไม่เปลี่ยนเป็นติดกดเพื่อยืนยัน (VERIFY)
     * และยังไม่ได้ Cookie ให้เปลี่ยนเป็นเหมือนเดิม คือข้ามไอดีนั้นไปตัวถัดไป
     */
    private final Runnable stuckCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!browserOpen || activeAccountIdx < 0 || !currentAutoFill) return;
            if (loginCycleStart == 0) return;
            if (System.currentTimeMillis() - loginCycleStart < 9500) {
                mainHandler.postDelayed(this, 1500);
                return;
            }
            if (skipScheduled) return;
            String st = lastStatus != null ? lastStatus : "";
            if ("WORK".equals(st)) return;
            if ("VERIFY".equals(st)) {
                // ติดกดเพื่อยืนยัน (Press and hold) — ให้ผู้ใช้กดเองในหน้าต่าง ไม่ตัดข้าม ไม่รีเซ็ต
                return;
            }

            // ครบ 10 วินาทีแล้วยังไม่ผ่านและไม่ขึ้น Verify → ข้ามไอดีนี้เหมือนเดิม
            skipScheduled = true;
            browserStatusBadge.setText("ครบ 10 วินาทีแล้วยังไม่ผ่าน — ข้ามไอดีนี้");
            browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
            notifyDashboard("ครบ 10 วินาทีแล้วยังไม่ผ่าน — ข้ามไอดีนี้");
            mainHandler.postDelayed(() -> skipCurrentAccount(), 500);
        }
    };

    /*
     * ปรับแค่ "รหัสไม่ตรง" (BADPASS) กับ "เกิดข้อผิดพลาดไม่ทราบสาเหตุ" (RETRY) ที่รีเซ็ตหน้า
     */
    private void maybeAutoRetry(String st) {
        if (!browserOpen || !currentAutoFill || activeAccountIdx < 0) return;
        if (!"BADPASS".equals(st) && !"RETRY".equals(st)) return;
        long now = System.currentTimeMillis();
        if (now - lastRetryAt < 3000) return;
        if (autoRetryCount >= 2) return;
        lastRetryAt = now;
        retryLoginNow(st);
    }

    /* รีเซ็ตหน้าเว็บและลองใหม่อัตโนมัติ (เฉพาะ BADPASS และ RETRY) */
    private void retryLoginNow(String reason) {
        if (activeAccountIdx < 0) return;
        autoRetryCount++;
        final int token = openToken;
        lastStatus = "";
        noticedStatus = "";
        loginCycleStart = 0;
        mainHandler.removeCallbacks(stuckCheckRunnable);
        mainHandler.removeCallbacks(liveInspectorRunnable);
        String label = "BADPASS".equals(reason) ? "รหัสไม่ตรง" : "เกิดข้อผิดพลาดไม่ทราบสาเหตุ";
        browserStatusBadge.setText("↻ ลองใหม่อัตโนมัติ (" + label + ") ครั้งที่ " + autoRetryCount + "/2");
        browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
        notifyDashboard("ลองใหม่อัตโนมัติ (" + label + ") ครั้งที่ " + autoRetryCount + "/2");
        clearBrowserSession(() -> {
            if (!browserOpen || token != openToken || activeAccountIdx < 0) return;
            robloxWebView.loadUrl("about:blank");
            mainHandler.postDelayed(() -> {
                if (!browserOpen || token != openToken || activeAccountIdx < 0) return;
                autoFillPending = true;
                robloxWebView.loadUrl("https://www.roblox.com/login");
                mainHandler.postDelayed(liveInspectorRunnable, 1200);
            }, 180);
        });
    }

    /* กด "ปิด / ถัดไป ⏭": เซฟ cookie ก่อนแล้วเปิดไอดีถัดไปในคิว */
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

    /* หยุดการทำงานทั้งหมดและกลับหน้าหลัก (หยุดกลางทาง) */
    private void stopQueueAndCloseBrowser() {
        grabCookieInternal();
        accountQueue.clear();
        browserOpen = false;
        autoFillPending = false;
        autoCheckMode = false;
        openToken++;
        mainHandler.removeCallbacks(stuckCheckRunnable);
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
        public void openAccountsQueue(String jsonIndices, boolean incognito, int concurrency) {
            openAccountsQueue(jsonIndices, incognito);
        }

        @JavascriptInterface
        public void openAccountsQueue(String jsonIndices, boolean incognito) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, incognito, true, false));
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices, int concurrency) {
            checkAccountsQueue(jsonIndices);
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, true));
        }

        @JavascriptInterface
        public void checkCookies(String jsonIndices, int concurrency) {
            checkCookies(jsonIndices);
        }

        @JavascriptInterface
        public void checkCookies(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startCookieCheck(idxs));
        }

        @JavascriptInterface
        public void manualOpen(int idx, boolean incognito) {
            int[] idxs = idx >= 0 ? new int[]{idx} : null;
            runOnUiThread(() -> startAccountQueue(idxs, incognito, false, false));
        }

        @JavascriptInterface
        public void dumpCookiesQueue(String jsonIndices, int concurrency) {
            dumpCookiesQueue(jsonIndices);
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
