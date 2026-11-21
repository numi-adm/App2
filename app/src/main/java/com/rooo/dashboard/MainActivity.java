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
            "  function deep(el, depth, out){" +
            "    if(!el || depth > 4) return;" +
            "    try { var t = el.innerText || el.textContent || ''; if(t) out.push(t); } catch(e){}" +
            "    try { if(el.shadowRoot) deep(el.shadowRoot, depth + 1, out); } catch(e){}" +
            "  }" +
            "  var parts = [];" +
            "  try { if(document.title) parts.push(document.title); } catch(e){}" +
            "  try {" +
            "    var sels = ['[role=dialog]','[class*=modal]','[class*=Modal]','[data-testid*=modal]','[class*=alert]','[class*=Alert]','[class*=notification]','[class*=Notification]'];" +
            "    for (var s = 0; s < sels.length; s++) {" +
            "      try { document.querySelectorAll(sels[s]).forEach(function(el){ deep(el, 0, parts); }); } catch(e){}" +
            "    }" +
            "  } catch(e){}" +
            "  try {" +
            "    var nodes = document.querySelectorAll('*');" +
            "    for (var i = 0; i < nodes.length; i++) {" +
            "      var host = nodes[i];" +
            "      var sr = null;" +
            "      try { sr = host.shadowRoot; } catch(e){}" +
            "      if (sr) deep(sr, 0, parts);" +
            "    }" +
            "  } catch(e){}" +
            "  try { if(document.body) parts.push(document.body.innerText || ''); } catch(e){}" +
            "  try {" +
            "    Array.from(document.querySelectorAll('iframe')).forEach(function(f){" +
            "      try { var d = f.contentDocument; if(d && d.body) parts.push(d.body.innerText || ''); } catch(e){}" +
            "    });" +
            "  } catch(e){}" +
            "  var ark = false;" +
            "  try {" +
            "    ark = Array.from(document.querySelectorAll('iframe')).some(function(f){" +
            "      return /arkose|funcaptcha|captcha/i.test(f.src || '') && (f.offsetWidth > 0 || f.offsetHeight > 0);" +
            "    });" +
            "  } catch(e){}" +
            "  return JSON.stringify({url: location.href, text: parts.join('\\n').slice(0, 16000), arkose: ark});" +
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
    private boolean autoAdvanceMode = false;
    private boolean advanceScheduled = false;
    private String noticedStatus = "";
    private String lastStatus = "";
    private String lastNote = "";
    private String lastGrabStatus = "";
    private int autoRetryCount = 0;
    private long loginCycleStart = 0;
    private long lastRetryAt = 0;
    private int openToken = 0;
    private boolean skipScheduled = false;
    private long verifyStartTime = 0;
    private boolean isVerifyWaiting = false;

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
        btnGrab.setOnClickListener(v -> grabAndGoNext());
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

                // หน้า Security Notification (ไอดีถูกล็อกชั่วคราว) — จับใน Java อีกชั้น
                // เผื่อ native classifier อ่านข้อความไม่ครบ แล้วเผลอบันทึกเป็น WORK
                boolean secLock = looksLikeSecurityLock(pageText);
                if (secLock) {
                    st = "LOCKED";
                    note = "ไอดีถูกล็อก (Security Notification) — กด 'Recover your account' ในหน้าต่าง";
                    hasCk = false;
                    if (idx >= 0) NativeBridge.updateStatus(idx, "LOCKED", note);
                }

                lastStatus = st;
                lastNote = note;

                // 1. ถ้าได้ Cookie แล้ว → WORK สำเร็จ
                if (hasCk) {
                    isVerifyWaiting = false;
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#44ff77"));
                    notifyDashboard(null);
                    if ((autoCheckMode || autoAdvanceMode)
                            && (System.currentTimeMillis() - accountOpenedAt > 3000)) {
                        scheduleAdvance(1200);
                    }
                    return;
                }

                // 2. ข้ามแต่ รีโคบเวอร์ลี กับ รหัสไม่ตรง (ไม่รีลองใหม่)
                if (isSkipStatus(st)) {
                    isVerifyWaiting = false;
                    String hex = "BADPASS".equals(st) ? "#ff6666" : "#ff8844";
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor(hex));
                    notifyDashboard(null);
                    if (!noticedStatus.equals(st)) {
                        noticedStatus = st;
                        notifyDashboard("ติด " + st + " — ข้ามไอดีนี้ ไปตัวถัดไป");
                    }
                    if (!skipScheduled) {
                        skipScheduled = true;
                        mainHandler.postDelayed(this::skipCurrentAccount, 1000);
                    }
                    return;
                }

                // 3. ถ้าเข้า VERIFY (ติดกดเพื่อยืนยัน / Verifying you're not a bot / Captcha)
                if ("VERIFY".equals(st) || "CAPTCHA".equals(st)) {
                    if (!isVerifyWaiting) {
                        isVerifyWaiting = true;
                        verifyStartTime = System.currentTimeMillis();
                    }
                    long elapsed = (System.currentTimeMillis() - verifyStartTime) / 1000;
                    long rem = Math.max(0, 45 - elapsed);
                    browserStatusBadge.setText("สถานะ: VERIFY (" + rem + "s/45s) — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#ffaa00"));
                    notifyDashboard(null);

                    // ถ้าเกิน 45s ให้รีทิ้งแล้วลองใหม่ (สูงสุด 3 ครั้ง)
                    if (elapsed >= 45) {
                        if (autoRetryCount < 3) {
                            isVerifyWaiting = false;
                            retryLoginNow("ติด VERIFY เกิน 45 วินาที");
                        } else if (!skipScheduled) {
                            skipScheduled = true;
                            notifyDashboard("ติด VERIFY (ลองใหม่ครบ 3 ครั้งแล้ว) — ข้ามไอดีนี้");
                            mainHandler.postDelayed(this::skipCurrentAccount, 1200);
                        }
                    }
                    return;
                }

                // 4. ที่เหลือ ถ้าไม่เข้า VERIFY (เช่น RETRY / An unknown error / RATE) → รีลองใหม่ 3 ครั้ง
                isVerifyWaiting = false;
                if ("RETRY".equals(st) || "RATE".equals(st)) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
                    notifyDashboard(null);
                    if (autoRetryCount < 3) {
                        maybeAutoRetry(st);
                    } else if (!skipScheduled) {
                        skipScheduled = true;
                        notifyDashboard("ลองใหม่ครบ 3 ครั้งแล้วยังติด " + st + " — ข้ามไอดีนี้");
                        mainHandler.postDelayed(this::skipCurrentAccount, 1200);
                    }
                    return;
                }

                // 4.5 สถานะล็อก/แบน/2FA — หยุดรอ ไม่รีลองใหม่
                if ("LOCKED".equals(st) || "BAN".equals(st) || "2FA".equals(st)) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("BAN".equals(st) ? "#ff5555" : "#ff8844"));
                    // หน้า Security Notification (กู้คืนบัญชี) กับ BAN = ใช้งานไม่ได้แล้ว
                    // จึงไปไอดีถัดไปให้เอง (ถ้ามีคิวเหลือ) ส่วน "ยืนยันว่าเป็นมนุษย์" / 2FA ต้องรอผู้ใช้กดเอง
                    boolean hasNext = autoAdvanceMode || !accountQueue.isEmpty();
                    if (hasNext && (secLock || "BAN".equals(st))) {
                        if (!noticedStatus.equals(st)) {
                            noticedStatus = st;
                            notifyDashboard("ติด " + st + " — ตรวจไอดีที่เหลือให้อัตโนมัติ");
                        } else {
                            notifyDashboard(null);
                        }
                        scheduleAdvance(4000);
                    } else {
                        notifyDashboard(null);
                    }
                    return;
                }

                // 5. อยู่หน้าล็อกอิน หรือสถานะอื่นๆ
                browserStatusBadge.setText("กำลังล็อกอิน / ตรวจสอบสถานะ...");
                browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
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

    private void startAccountQueue(int[] idxs, boolean cleanSession, boolean autoFill,
                                   boolean isCheckMode, boolean autoAdvance) {
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
        autoAdvanceMode = autoAdvance;
        advanceScheduled = false;

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
        advanceScheduled = false;
        isVerifyWaiting = false;
        verifyStartTime = 0;
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
        // อ่านสถานะเดิมก่อนบันทึก (ตัวบันทึก cookie จะเขียนทับเป็น WORK ทันที)
        String dbSt = activeAccountIdx >= 0
                ? NativeBridge.getAccountField(activeAccountIdx, "status") : "";
        int savedIdx = NativeBridge.storeCookieFromHeader(
                activeAccountIdx, user, combined, "ดึงจากหน้าต่างในแอป");
        if (savedIdx >= 0) {
            String st = lastStatus != null ? lastStatus : "";
            String note = "ดึงจากหน้าต่างในแอป";
            if (isBlockingStatus(st)) {
                if (lastNote != null && lastNote.length() > 0) note = lastNote;
            } else if (isBlockingStatus(dbSt)) {
                // หน้าเว็บยังไม่ได้ตรวจ หรือตรวจไม่ทัน — ห้ามทับสถานะล็อก/แบน/ยืนยันด้วย WORK
                st = dbSt;
                note = "คงสถานะเดิม " + dbSt + " — ยังไม่ยืนยันว่าใช้งานได้";
            } else {
                st = "WORK";
                note = "ดึงและบันทึก Cookie สำเร็จแล้ว";
            }
            NativeBridge.updateStatus(savedIdx, st, note);
            lastGrabStatus = st;
            lastNote = note;
        }
        return savedIdx >= 0 ? 1 : 0;
    }

    /*
     * ปุ่ม "ดึง Cookie": สแกนหน้าล่าสุด 1 ครั้งก่อน (กันจับสถานะไม่ทัน)
     * แล้วบันทึก cookie + พาไปไอดีถัดไปในคิวให้อัตโนมัติ
     */
    private void grabAndGoNext() {
        if (!browserOpen) {
            notifyDashboard("ยังไม่มีหน้าต่างที่เปิดอยู่ — เปิดไอดีก่อน");
            return;
        }
        final int idx = activeAccountIdx;
        browserStatusBadge.setText("กำลังสแกนหน้าเว็บก่อนดึง cookie...");
        browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
        inspectCurrentRobloxPage();
        mainHandler.postDelayed(() -> {
            if (!browserOpen || activeAccountIdx != idx) return;
            int got = grabCookieInternal();
            if (got <= 0) {
                notifyDashboard("ยังไม่มี cookie — เช็คสถานะบนแถบด้านบน");
                return;
            }
            boolean ok = "WORK".equals(lastGrabStatus);
            browserStatusBadge.setText("สถานะ: " + lastGrabStatus
                    + (ok ? " — บันทึก Cookie แล้ว" : " — " + lastNote));
            browserStatusBadge.setTextColor(Color.parseColor(ok ? "#44ff77" : "#ff8844"));
            notifyDashboard(ok
                    ? "ดึง cookie สำเร็จ — ไปไอดีถัดไป"
                    : "บันทึก cookie แล้ว (สถานะ " + lastGrabStatus + ") — ไปไอดีถัดไป");
            scheduleAdvance(1000);
        }, 500);
    }

    /* หน่วงเวลาแล้วไปไอดีถัดไป (กันสั่งซ้ำ/ข้ามไอดี) */
    private void scheduleAdvance(int delayMs) {
        if (!browserOpen || advanceScheduled) return;
        final int token = openToken;
        advanceScheduled = true;
        mainHandler.postDelayed(() -> {
            if (token == openToken && browserOpen) closeAndOpenNextAccount();
        }, delayMs);
    }

    /* ไปไอดีถัดไปในคิว โดยไม่ดึง cookie ซ้ำ */
    private void advanceToNext() {
        if (!browserOpen) return;
        if (!accountQueue.isEmpty()) {
            int nextIdx = accountQueue.remove(0);
            openAccountInWebView(nextIdx, currentCleanSession, currentAutoFill);
        } else {
            stopQueueAndCloseBrowser();
            notifyDashboard("ครบทุกไอดีในคิวแล้ว!");
        }
    }

    /* ข้อความหน้า "Security Notification" — จับซ้ำอีกชั้นในฝั่ง Java */
    private static final String[] SECURITY_LOCK_MARKERS = {
            "securitynotification",
            "detectedsuspiciousactivity",
            "temporarilylocked",
            "temporarilylock",
            "credentialsmayhavebeencompromised",
            "recoveryouraccounttoregainaccess",
            "accounthasbeenlocked",
            "accountislocked",
            "accountlocked",
            "บัญชีถูกล็อก",
            "ถูกล็อกชั่วคราว",
            "ล็อกบัญชีชั่วคราว",
            "กิจกรรมที่น่าสงสัย",
            "การแจ้งเตือนด้านความปลอดภัย",
            "การแจ้งเตือนความปลอดภัย"
    };

    private static boolean looksLikeSecurityLock(String text) {
        if (text == null || text.isEmpty()) return false;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isWhitespace(c) || c == '\u00a0' || c == '\u200b'
                    || c == '\u200c' || c == '\u200d' || c == '\ufeff') {
                continue;
            }
            sb.append(c);
        }
        String flat = sb.toString();
        if (flat.isEmpty()) return false;
        boolean hasLocked = flat.contains("locked") || flat.contains("ล็อก");
        for (String marker : SECURITY_LOCK_MARKERS) {
            if (!flat.contains(marker)) continue;
            if ("securitynotification".equals(marker)) {
                if (hasLocked || flat.contains("suspicious") || flat.contains("น่าสงสัย")
                        || flat.contains("recover") || flat.contains("regainaccess")) {
                    return true;
                }
                continue;
            }
            return true;
        }
        return false;
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

    /* ข้ามเฉพาะ รีโคบเวอร์ลี กับ รหัสไม่ตรง เท่านั้น */
    private static boolean isSkipStatus(String st) {
        return "RECOVERY".equals(st) || "BADPASS".equals(st);
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
     * ถ้าครบ 10 วินาทีแล้วจากตรงล็อกอิน ไม่เข้า VERIFY (และไม่ได้ Cookie)
     * ให้รีลองใหม่ 3 ครั้ง (ถ้าครบ 3 ครั้งแล้วจึงข้าม)
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
            if ("VERIFY".equals(st) || "CAPTCHA".equals(st)) {
                // ถ้าเข้า VERIFY แล้ว ให้ตัวจับเวลา 45s ของ VERIFY ดูแล
                return;
            }
            if ("RECOVERY".equals(st) || "BADPASS".equals(st)) {
                return;
            }
            if ("LOCKED".equals(st) || "BAN".equals(st) || "2FA".equals(st)) {
                return;
            }

            // ครบ 10 วิ แล้วไม่เข้า VERIFY: ให้รีลองใหม่ 3 ครั้ง
            if (autoRetryCount < 3) {
                retryLoginNow("ไม่เข้า VERIFY ใน 10 วินาที");
            } else {
                skipScheduled = true;
                browserStatusBadge.setText("ไม่เข้า VERIFY (ลองใหม่ครบ 3 ครั้งแล้ว) — ข้ามไอดีนี้");
                browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
                notifyDashboard("ลองใหม่ครบ 3 ครั้งแล้วยังไม่เข้า VERIFY — ข้ามไอดีนี้");
                mainHandler.postDelayed(() -> skipCurrentAccount(), 800);
            }
        }
    };

    private void maybeAutoRetry(String reason) {
        if (!browserOpen || !currentAutoFill || activeAccountIdx < 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRetryAt < 3000) return;
        if (autoRetryCount >= 3) return;
        lastRetryAt = now;
        retryLoginNow(reason);
    }

    /* รีเซ็ตหน้าเว็บและลองใหม่อัตโนมัติ (สูงสุด 3 ครั้ง) */
    private void retryLoginNow(String reason) {
        if (activeAccountIdx < 0) return;
        autoRetryCount++;
        final int token = openToken;
        lastStatus = "";
        noticedStatus = "";
        loginCycleStart = 0;
        isVerifyWaiting = false;
        verifyStartTime = 0;
        mainHandler.removeCallbacks(stuckCheckRunnable);
        mainHandler.removeCallbacks(liveInspectorRunnable);
        String label = "RETRY".equals(reason) ? "เกิดข้อผิดพลาด" : reason;
        browserStatusBadge.setText("↻ รีทิ้งลองใหม่ (" + label + ") ครั้งที่ " + autoRetryCount + "/3");
        browserStatusBadge.setTextColor(Color.parseColor("#ffcc44"));
        notifyDashboard("↻ รีทิ้งลองใหม่ (" + label + ") ครั้งที่ " + autoRetryCount + "/3");
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
        if (!browserOpen) return;
        grabCookieInternal();
        notifyDashboard(null);
        advanceToNext();
    }

    /* หยุดการทำงานทั้งหมดและกลับหน้าหลัก (หยุดกลางทาง) */
    private void stopQueueAndCloseBrowser() {
        grabCookieInternal();
        accountQueue.clear();
        browserOpen = false;
        autoFillPending = false;
        autoCheckMode = false;
        autoAdvanceMode = false;
        advanceScheduled = false;
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
            runOnUiThread(() -> startAccountQueue(idxs, incognito, true, false, false));
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices, int concurrency) {
            checkAccountsQueue(jsonIndices);
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, true, false));
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
            runOnUiThread(() -> startAccountQueue(idxs, incognito, false, false, false));
        }

        @JavascriptInterface
        public void dumpCookiesQueue(String jsonIndices, int concurrency) {
            dumpCookiesQueue(jsonIndices);
        }

        @JavascriptInterface
        public void dumpCookiesQueue(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, false, true));
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
