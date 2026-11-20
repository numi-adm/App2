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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private LinearLayout browserContainer;
    private TextView browserTitle;
    private TextView browserStatusBadge;
    private Button btnToggleGrid;
    private HorizontalScrollView tabStripScroll;
    private LinearLayout tabStripLayout;
    private FrameLayout slotsContainer;

    private ValueCallback<Uri[]> fileUploadCallback;
    private String pendingCsvExport = "";

    private boolean browserOpen = false;
    private boolean currentCleanSession = true;
    private boolean currentAutoFill = true;
    private boolean autoCheckMode = false;
    private int currentConcurrency = 1;
    private int focusedSlotIndex = 0;
    private boolean isGridMode = true;

    private final List<Integer> accountQueue = new ArrayList<>();
    private final List<WorkerSlot> slots = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // 2. Slide-up In-App Roblox Browser Panel (Supports 1, 2, 4, 6, 8, 10 IDs concurrently)
        browserContainer = new LinearLayout(this);
        browserContainer.setOrientation(LinearLayout.VERTICAL);
        browserContainer.setBackgroundColor(Color.parseColor("#181818"));
        browserContainer.setVisibility(View.GONE);

        // Top toolbar: Title, Grid toggle, Grab Cookie, Retry, Close/Next (NO "หน้าหลัก" button!)
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
        browserTitle.setTextSize(12.5f);
        browserTitle.setText("Roblox Browser");
        titleCol.addView(browserTitle);

        browserStatusBadge = new TextView(this);
        browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
        browserStatusBadge.setTextSize(10.5f);
        browserStatusBadge.setText("กำลังเตรียมระบบ...");
        titleCol.addView(browserStatusBadge);

        topBar.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnToggleGrid = makeBarButton("⊞ ตาราง", "#3a5a8a");
        btnToggleGrid.setOnClickListener(v -> {
            isGridMode = !isGridMode;
            renderSlotLayout();
        });
        topBar.addView(btnToggleGrid);

        Button btnGrab = makeBarButton("ดึง Cookie", "#1d7a34");
        btnGrab.setOnClickListener(v -> {
            if (slots.isEmpty()) return;
            int idx = Math.max(0, Math.min(focusedSlotIndex, slots.size() - 1));
            WorkerSlot s = slots.get(idx);
            int got = s.grabSlotCookie(false);
            if (got > 0) {
                notifyDashboard("ช่อง #" + (idx + 1) + " บันทึก Cookie แล้ว! (" + s.lastGrabStatus + ")");
            } else {
                s.inspectSlotPage();
                notifyDashboard("ช่อง #" + (idx + 1) + " ยังไม่มี cookie — ดูสถานะบนแถบ");
            }
        });
        topBar.addView(btnGrab);

        Button btnRetry = makeBarButton("ลองใหม่", "#8a5a00");
        btnRetry.setOnClickListener(v -> {
            if (slots.isEmpty()) return;
            int idx = Math.max(0, Math.min(focusedSlotIndex, slots.size() - 1));
            WorkerSlot s = slots.get(idx);
            if (s.activeAccountIdx >= 0) {
                notifyDashboard("ช่อง #" + (idx + 1) + " ลองใหม่ไอดี #" + (s.activeAccountIdx + 1) + "...");
                s.startAccount(s.activeAccountIdx, true, currentAutoFill);
            }
        });
        topBar.addView(btnRetry);

        // "ปิด / ถัดไป ⏭" (กด = ข้ามช่องนี้ไปไอดีถัดไป, กดค้าง = หยุดทั้งหมดแล้วกลับหน้าหลัก)
        Button btnCloseNext = makeBarButton("ปิด / ถัดไป ⏭", "#a02828");
        btnCloseNext.setOnClickListener(v -> {
            if (slots.isEmpty()) {
                stopQueueAndCloseBrowser();
                return;
            }
            int idx = Math.max(0, Math.min(focusedSlotIndex, slots.size() - 1));
            WorkerSlot s = slots.get(idx);
            s.grabSlotCookie(false);
            s.skipSlot("ผู้ใช้กด ปิด / ถัดไป");
        });
        btnCloseNext.setOnLongClickListener(v -> {
            stopQueueAndCloseBrowser();
            notifyDashboard("หยุดการทำงานทั้งหมดและกลับหน้าหลักแล้ว");
            return true;
        });
        topBar.addView(btnCloseNext);

        browserContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Horizontal scrollable Tab strip for switching slots
        tabStripScroll = new HorizontalScrollView(this);
        tabStripScroll.setBackgroundColor(Color.parseColor("#1f1f1f"));
        tabStripScroll.setHorizontalScrollBarEnabled(false);
        tabStripLayout = new LinearLayout(this);
        tabStripLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabStripLayout.setPadding(dp(4), dp(3), dp(4), dp(3));
        tabStripScroll.addView(tabStripLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        browserContainer.addView(tabStripScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Container holding the slot WebViews (Grid or Single)
        slotsContainer = new FrameLayout(this);
        browserContainer.addView(slotsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(browserContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /* ===== สถานะที่ต้องข้ามทันที (VERIFY ให้รอกดเองก่อนปิด ไม่ข้ามทันที) ===== */
    private static boolean isSkipStatus(String st) {
        return "LOCKED".equals(st) || "RECOVERY".equals(st)
                || "2FA".equals(st) || "CAPTCHA".equals(st) || "BAN".equals(st);
    }

    /* =========================================================================
     * WorkerSlot: ช่องเปิด Roblox อิสระ (รองรับ 1, 2, 4, 6, 8, 10 ไอดีพร้อมกัน)
     * ========================================================================= */
    private class WorkerSlot {
        final int slotId;
        final WebView webView;
        final LinearLayout slotContainer;
        final TextView slotTitleView;
        final TextView slotStatusView;

        int activeAccountIdx = -1;
        long accountOpenedAt = 0;
        String lastStatus = "";
        String lastNote = "";
        String lastGrabStatus = "";
        boolean autoFillPending = false;
        boolean skipScheduled = false;
        boolean isVerifyWaiting = false;
        long verifyStartTime = 0;
        int openToken = 0;

        /* Watchdog: ถ้ายังไม่ผ่าน ครบ 30 วินาทีแล้วยังไม่ผ่าน ให้ข้ามทันที */
        final Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!browserOpen || activeAccountIdx < 0 || skipScheduled) return;
                if ("WORK".equals(lastStatus)) return;
                skipScheduled = true;
                final int idx = activeAccountIdx;
                String st = ("VERIFY".equals(lastStatus)) ? "VERIFY"
                        : ((lastStatus != null && !lastStatus.isEmpty() && !"UNKNOWN".equals(lastStatus)) ? lastStatus : "SKIP");
                String note = "VERIFY".equals(st)
                        ? "ติด Verify (Press and hold) — ไม่ได้กดยืนยันใน 30 วิ"
                        : "ครบ 30 วินาทีแล้วยังไม่ผ่าน (ข้าม)";
                updateHeader("ครบ 30 วินาทีแล้วยังไม่ผ่าน (ข้าม)", "#ffcc44");
                synchronized (NativeBridge.class) {
                    NativeBridge.updateStatus(idx, st, note);
                }
                notifyDashboard("ช่อง #" + (slotId + 1) + " ครบ 30 วิยังไม่ผ่าน — ข้ามไอดี #" + (idx + 1));
                mainHandler.postDelayed(() -> skipSlot("ครบ 30 วินาทีแล้วยังไม่ผ่าน"), 120);
            }
        };

        /* Periodic page status inspector */
        final Runnable liveInspectorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!browserOpen || activeAccountIdx < 0 || skipScheduled) return;
                inspectSlotPage();
                mainHandler.postDelayed(this, 600);
            }
        };

        WorkerSlot(int id) {
            this.slotId = id;
            this.slotContainer = new LinearLayout(MainActivity.this);
            this.slotContainer.setOrientation(LinearLayout.VERTICAL);
            this.slotContainer.setBackgroundColor(Color.parseColor("#202020"));

            // Slot header bar
            LinearLayout header = new LinearLayout(MainActivity.this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBackgroundColor(Color.parseColor("#2b2b2b"));
            int hp = dp(6);
            int vp = dp(3);
            header.setPadding(hp, vp, hp, vp);

            slotTitleView = new TextView(MainActivity.this);
            slotTitleView.setTextColor(Color.WHITE);
            slotTitleView.setTextSize(11f);
            slotTitleView.setText("ช่อง " + (slotId + 1) + ": ว่าง");

            slotStatusView = new TextView(MainActivity.this);
            slotStatusView.setTextColor(Color.parseColor("#99ccff"));
            slotStatusView.setTextSize(10f);
            slotStatusView.setText("พร้อมใช้งาน");

            LinearLayout titleCol = new LinearLayout(MainActivity.this);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            titleCol.addView(slotTitleView);
            titleCol.addView(slotStatusView);

            header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnFocus = makeBarButton("ขยาย", "#3a3a3a");
            btnFocus.setTextSize(10f);
            btnFocus.setPadding(dp(6), dp(2), dp(6), dp(2));
            btnFocus.setOnClickListener(v -> setFocusedSlot(slotId));
            header.addView(btnFocus);

            slotContainer.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Slot WebView
            webView = new WebView(MainActivity.this);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webView, true);

            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    final int idx = activeAccountIdx;
                    if (autoFillPending && url != null && url.contains("/login") && idx >= 0) {
                        autoFillPending = false;
                        String js;
                        synchronized (NativeBridge.class) {
                            js = NativeBridge.buildLoginJs(idx);
                        }
                        if (js != null && !js.isEmpty()) {
                            view.evaluateJavascript(js, null);
                        }
                    }
                    mainHandler.postDelayed(() -> inspectSlotPage(), 400);
                }
            });

            slotContainer.addView(webView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        boolean isActive() {
            return activeAccountIdx >= 0;
        }

        void updateHeader(String statusText, String hexColor) {
            runOnUiThread(() -> {
                int total = NativeBridge.getAccountCount();
                String user = activeAccountIdx >= 0
                        ? NativeBridge.getAccountField(activeAccountIdx, "username") : "";
                if (activeAccountIdx >= 0) {
                    slotTitleView.setText("ช่อง " + (slotId + 1) + " [#" + (activeAccountIdx + 1) + "/" + total + " " + user + "]");
                } else {
                    slotTitleView.setText("ช่อง " + (slotId + 1) + ": ว่าง");
                }
                slotStatusView.setText(statusText);
                slotStatusView.setTextColor(Color.parseColor(hexColor));
            });
        }

        void startAccount(int idx, boolean cleanSession, boolean autoFill) {
            this.activeAccountIdx = idx;
            this.accountOpenedAt = System.currentTimeMillis();
            this.lastStatus = "";
            this.lastNote = "";
            this.skipScheduled = false;
            this.isVerifyWaiting = false;
            this.verifyStartTime = 0;
            this.openToken++;

            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.removeCallbacks(liveInspectorRunnable);

            // เริ่มจับเวลา 30 วินาที — ครบแล้วยังไม่ผ่านจะข้ามทันที
            mainHandler.postDelayed(timeoutRunnable, 30000);

            updateHeader("กำลังเปิดและกรอกรหัส...", "#99ccff");

            String cookie;
            synchronized (NativeBridge.class) {
                cookie = NativeBridge.getAccountField(idx, "cookie");
            }

            Runnable launch = () -> {
                if (!autoCheckMode && cookie != null && cookie.length() > 100) {
                    CookieManager cm = CookieManager.getInstance();
                    cm.setCookie("https://www.roblox.com",
                            ".ROBLOSECURITY=" + cookie + "; Domain=.roblox.com; Path=/; Secure; HttpOnly");
                    cm.flush();
                    autoFillPending = false;
                    webView.loadUrl("https://www.roblox.com/home");
                } else {
                    autoFillPending = autoFill && (idx >= 0);
                    webView.loadUrl("https://www.roblox.com/login");
                }
                mainHandler.postDelayed(liveInspectorRunnable, 1000);
            };

            if (cleanSession && currentConcurrency <= 1) {
                clearBrowserSession(launch);
            } else {
                launch.run();
            }
        }

        void inspectSlotPage() {
            if (!browserOpen || activeAccountIdx < 0 || skipScheduled) return;
            final int token = openToken;
            final int idx = activeAccountIdx;

            webView.evaluateJavascript(JS_INSPECT_PAGE, value -> {
                if (!browserOpen || openToken != token || activeAccountIdx != idx || skipScheduled) return;
                String url = webView.getUrl() != null ? webView.getUrl() : "";
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
                } catch (Exception ignored) {}

                CookieManager cm = CookieManager.getInstance();
                String c1 = cm.getCookie("https://www.roblox.com");
                String c2 = cm.getCookie("https://roblox.com");
                String combinedCookies = (c1 != null ? c1 : "") + "; " + (c2 != null ? c2 : "");

                String resJson;
                synchronized (NativeBridge.class) {
                    resJson = NativeBridge.classifyPage(idx, url, pageText, hasArkose, combinedCookies);
                }

                try {
                    JSONObject res = new JSONObject(resJson);
                    String st = res.optString("status", "UNKNOWN");
                    String note = res.optString("note", "");
                    boolean hasCk = res.optBoolean("hasCookie", false);

                    lastStatus = st;
                    lastNote = note;

                    if (isSkipStatus(st)) {
                        /* LOCKED / RECOVERY / 2FA / CAPTCHA / BAN → ข้ามไปเลยทันที! */
                        skipScheduled = true;
                        mainHandler.removeCallbacks(timeoutRunnable);
                        mainHandler.removeCallbacks(liveInspectorRunnable);
                        updateHeader(st + " — ข้ามทันที", "#ff8844");
                        synchronized (NativeBridge.class) {
                            NativeBridge.updateStatus(idx, st, note);
                        }
                        notifyDashboard("ช่อง #" + (slotId + 1) + " ติด " + st + " — ข้ามไอดี #" + (idx + 1));
                        mainHandler.postDelayed(() -> skipSlot("ติด " + st), 150);
                        return;
                    }

                    if ("VERIFY".equals(st)) {
                        /* ติด VERIFY (Press and hold) → รอกดเองก่อนปิด (เริ่มนับเวลาให้ 30 วิ) */
                        if (!isVerifyWaiting) {
                            isVerifyWaiting = true;
                            verifyStartTime = System.currentTimeMillis();
                            mainHandler.removeCallbacks(timeoutRunnable);
                            mainHandler.postDelayed(timeoutRunnable, 30000);
                            synchronized (NativeBridge.class) {
                                NativeBridge.updateStatus(idx, "VERIFY",
                                        (note != null && !note.isEmpty()) ? note : "ติด Verify (Press and hold) — กดค้างที่ปุ่มบนหน้าจอเพื่อยืนยัน");
                            }
                            notifyDashboard("ช่อง #" + (slotId + 1) + " ติด Verify — กด Press and hold ในหน้าต่าง");
                        }
                        long elapsed = (System.currentTimeMillis() - verifyStartTime) / 1000;
                        long rem = Math.max(0, 30 - elapsed);
                        updateHeader("VERIFY — กดค้างในจอ (" + rem + "s/30s)", "#ffaa00");
                        return;
                    }

                    if (hasCk || "WORK".equals(st)) {
                        /* ล็อกอินสำเร็จ — เก็บ Cookie แล้วไปตัวถัดไป */
                        skipScheduled = true;
                        mainHandler.removeCallbacks(timeoutRunnable);
                        mainHandler.removeCallbacks(liveInspectorRunnable);
                        updateHeader("WORK — สำเร็จ", "#44ff77");
                        grabSlotCookie(true);
                        notifyDashboard("ช่อง #" + (slotId + 1) + " ไอดี #" + (idx + 1) + " สำเร็จ (WORK)");
                        mainHandler.postDelayed(this::onSuccessFinish, 400);
                        return;
                    }

                    if ("BADPASS".equals(st)) {
                        /* รหัสไม่ตรง → ข้ามทันที */
                        skipScheduled = true;
                        mainHandler.removeCallbacks(timeoutRunnable);
                        mainHandler.removeCallbacks(liveInspectorRunnable);
                        updateHeader("BADPASS — รหัสไม่ตรง", "#ff6666");
                        synchronized (NativeBridge.class) {
                            NativeBridge.updateStatus(idx, "BADPASS", "รหัสไม่ตรงกับชื่อผู้ใช้");
                        }
                        notifyDashboard("ช่อง #" + (slotId + 1) + " ไอดี #" + (idx + 1) + " รหัสไม่ถูกต้อง");
                        mainHandler.postDelayed(() -> skipSlot("รหัสไม่ถูกต้อง"), 150);
                        return;
                    }

                    if (!"UNKNOWN".equals(st)) {
                        updateHeader(st + " (" + note + ")", "#ffcc44");
                    } else {
                        long elapsed = (System.currentTimeMillis() - accountOpenedAt) / 1000;
                        updateHeader("กำลังล็อกอิน... (" + elapsed + "s/30s)", "#99ccff");
                    }
                } catch (Exception ignored) {}
            });
        }

        void skipSlot(String reason) {
            if (!browserOpen) return;
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.removeCallbacks(liveInspectorRunnable);
            final int prevIdx = activeAccountIdx;
            if (prevIdx >= 0) {
                String st = "VERIFY".equals(lastStatus) ? "VERIFY"
                        : ((lastStatus == null || lastStatus.isEmpty() || "UNKNOWN".equals(lastStatus)) ? "SKIP" : lastStatus);
                String note = "VERIFY".equals(st)
                        ? ((lastNote != null && !lastNote.isEmpty()) ? lastNote : "ติด Verify (Press and hold) — กดค้างที่ปุ่มบนหน้าจอเพื่อยืนยัน")
                        : ((lastNote == null || lastNote.isEmpty()) ? reason : lastNote);
                synchronized (NativeBridge.class) {
                    NativeBridge.updateStatus(prevIdx, st, note);
                }
            }
            dispatchNextForSlot(this);
        }

        void onSuccessFinish() {
            if (!browserOpen) return;
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.removeCallbacks(liveInspectorRunnable);
            dispatchNextForSlot(this);
        }

        int grabSlotCookie(boolean markWork) {
            CookieManager cm = CookieManager.getInstance();
            String raw1 = cm.getCookie("https://www.roblox.com");
            String raw2 = cm.getCookie("https://roblox.com");
            String combined = (raw1 != null ? raw1 : "") + "; " + (raw2 != null ? raw2 : "");
            String user = activeAccountIdx >= 0
                    ? NativeBridge.getAccountField(activeAccountIdx, "username") : "";
            int savedIdx;
            synchronized (NativeBridge.class) {
                savedIdx = NativeBridge.storeCookieFromHeader(
                        activeAccountIdx, user, combined, "ดึงจากหน้าต่างในแอป");
                if (savedIdx >= 0) {
                    String st = markWork ? "WORK" : (lastStatus != null && !lastStatus.isEmpty() ? lastStatus : "WORK");
                    String note = markWork ? "ดึงและบันทึก Cookie สำเร็จแล้ว" : lastNote;
                    NativeBridge.updateStatus(savedIdx, st, note);
                    lastGrabStatus = st;
                }
            }
            return savedIdx >= 0 ? 1 : 0;
        }

        void markIdle() {
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.removeCallbacks(liveInspectorRunnable);
            activeAccountIdx = -1;
            skipScheduled = false;
            updateHeader("ว่าง (เสร็จสิ้น)", "#888888");
            webView.loadUrl("about:blank");
        }

        void stopSlot() {
            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.removeCallbacks(liveInspectorRunnable);
            activeAccountIdx = -1;
            skipScheduled = false;
            webView.loadUrl("about:blank");
        }
    }

    /* =========================================================================
     * การจัดเรียงและจัดการ Multi-Slot (1, 2, 4, 6, 8, 10 ไอดี)
     * ========================================================================= */
    private void setFocusedSlot(int slotId) {
        focusedSlotIndex = slotId;
        isGridMode = false;
        renderSlotLayout();
    }

    private void setupSlots(int count) {
        for (WorkerSlot s : slots) {
            s.stopSlot();
        }
        slots.clear();
        slotsContainer.removeAllViews();
        tabStripLayout.removeAllViews();

        int n = Math.max(1, Math.min(count, 10));
        currentConcurrency = n;

        for (int i = 0; i < n; i++) {
            WorkerSlot slot = new WorkerSlot(i);
            slots.add(slot);
        }

        focusedSlotIndex = 0;
        isGridMode = (n > 1);

        renderSlotLayout();
    }

    private void renderSlotLayout() {
        slotsContainer.removeAllViews();
        tabStripLayout.removeAllViews();

        int n = slots.size();
        if (n == 0) return;

        // Populate Slot Tab Strip
        if (n > 1) {
            tabStripScroll.setVisibility(View.VISIBLE);
            btnToggleGrid.setVisibility(View.VISIBLE);
            btnToggleGrid.setText(isGridMode ? "🗖 ขยาย" : "⊞ ตาราง");

            Button btnAll = makeBarButton("⊞ ตาราง (" + n + " ช่อง)", isGridMode ? "#1d7a34" : "#444444");
            btnAll.setOnClickListener(v -> {
                isGridMode = true;
                renderSlotLayout();
            });
            tabStripLayout.addView(btnAll);

            for (int i = 0; i < n; i++) {
                final int sId = i;
                boolean isSel = (!isGridMode && focusedSlotIndex == sId);
                Button tabBtn = makeBarButton("ช่อง " + (i + 1), isSel ? "#2d7ff9" : "#333333");
                tabBtn.setOnClickListener(v -> {
                    focusedSlotIndex = sId;
                    isGridMode = false;
                    renderSlotLayout();
                });
                tabStripLayout.addView(tabBtn);
            }
        } else {
            tabStripScroll.setVisibility(View.GONE);
            btnToggleGrid.setVisibility(View.GONE);
        }

        if (n == 1 || !isGridMode) {
            // Full-screen focused slot view
            int showIdx = (n == 1) ? 0 : Math.max(0, Math.min(focusedSlotIndex, n - 1));
            WorkerSlot slot = slots.get(showIdx);
            if (slot.slotContainer.getParent() != null) {
                ((ViewGroup) slot.slotContainer.getParent()).removeView(slot.slotContainer);
            }
            slotsContainer.addView(slot.slotContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // Grid view: shows all slots working simultaneously
            ScrollView gridScroll = new ScrollView(this);
            LinearLayout gridContent = new LinearLayout(this);
            gridContent.setOrientation(LinearLayout.VERTICAL);

            if (n == 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int i = 0; i < 2; i++) {
                    WorkerSlot slot = slots.get(i);
                    if (slot.slotContainer.getParent() != null) {
                        ((ViewGroup) slot.slotContainer.getParent()).removeView(slot.slotContainer);
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(340), 1f);
                    if (i > 0) lp.leftMargin = dp(4);
                    row.addView(slot.slotContainer, lp);
                }
                gridContent.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                for (int i = 0; i < n; i += 2) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    if (i > 0) {
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rlp.topMargin = dp(4);
                        row.setLayoutParams(rlp);
                    }

                    WorkerSlot s1 = slots.get(i);
                    if (s1.slotContainer.getParent() != null) {
                        ((ViewGroup) s1.slotContainer.getParent()).removeView(s1.slotContainer);
                    }
                    LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(260), 1f);
                    row.addView(s1.slotContainer, lp1);

                    if (i + 1 < n) {
                        WorkerSlot s2 = slots.get(i + 1);
                        if (s2.slotContainer.getParent() != null) {
                            ((ViewGroup) s2.slotContainer.getParent()).removeView(s2.slotContainer);
                        }
                        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(260), 1f);
                        lp2.leftMargin = dp(4);
                        row.addView(s2.slotContainer, lp2);
                    }
                    gridContent.addView(row);
                }
            }

            gridScroll.addView(gridContent, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            slotsContainer.addView(gridScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void dispatchNextForSlot(WorkerSlot slot) {
        int nextIdx = -1;
        synchronized (accountQueue) {
            if (!accountQueue.isEmpty()) {
                nextIdx = accountQueue.remove(0);
            }
        }
        if (nextIdx >= 0) {
            slot.startAccount(nextIdx, currentCleanSession, currentAutoFill);
            updateTopBarStatus();
        } else {
            slot.markIdle();
            updateTopBarStatus();
            checkIfAllSlotsFinished();
        }
    }

    private void updateTopBarStatus() {
        runOnUiThread(() -> {
            int activeCnt = 0;
            for (WorkerSlot s : slots) {
                if (s.isActive()) activeCnt++;
            }
            int qCnt;
            synchronized (accountQueue) {
                qCnt = accountQueue.size();
            }
            if (slots.size() > 1) {
                browserTitle.setText("Roblox [" + activeCnt + "/" + slots.size() + " ช่องกำลังทำงาน]");
                browserStatusBadge.setText("ทำงานพร้อมกัน " + slots.size() + " ไอดี · เหลือคิวรอ " + qCnt + " ไอดี");
            } else if (slots.size() == 1 && slots.get(0).isActive()) {
                int total = NativeBridge.getAccountCount();
                int idx = slots.get(0).activeAccountIdx;
                String user = idx >= 0 ? NativeBridge.getAccountField(idx, "username") : "";
                String qInfo = qCnt > 0 ? (" [เหลือคิว " + qCnt + "]") : "";
                browserTitle.setText("#" + (idx + 1) + "/" + total + " " + user + qInfo);
                browserStatusBadge.setText(slots.get(0).lastStatus.isEmpty()
                        ? "กำลังโหลดและตรวจสอบ..." : slots.get(0).lastStatus);
            }
        });
    }

    private void checkIfAllSlotsFinished() {
        boolean allIdle = true;
        for (WorkerSlot s : slots) {
            if (s.isActive()) {
                allIdle = false;
                break;
            }
        }
        if (allIdle) {
            runOnUiThread(() -> {
                browserTitle.setText("เสร็จสิ้นทุกไอดีแล้ว!");
                browserStatusBadge.setText("ครบทุกไอดีในคิวแล้ว (ปิดหน้าต่างได้)");
                browserStatusBadge.setTextColor(Color.parseColor("#44ff77"));
                notifyDashboard("ทำงานครบทุกไอดีในคิวแล้ว!");
            });
        }
    }

    private void startAccountQueue(int[] idxs, boolean cleanSession, boolean autoFill, boolean isCheckMode, int concurrency) {
        synchronized (accountQueue) {
            accountQueue.clear();
            int total = NativeBridge.getAccountCount();
            if (idxs != null && idxs.length > 0) {
                for (int i : idxs) {
                    if (i >= 0 && i < total) accountQueue.add(i);
                }
                if (idxs.length == 1 && idxs[0] >= 0 && !isCheckMode) {
                    for (int next = idxs[0] + 1; next < total; next++) {
                        accountQueue.add(next);
                    }
                }
            }
        }

        currentCleanSession = cleanSession;
        currentAutoFill = autoFill;
        autoCheckMode = isCheckMode;

        int reqSlots = Math.max(1, Math.min(concurrency, 10));
        int totalQueued;
        synchronized (accountQueue) {
            totalQueued = accountQueue.size();
        }
        int actualSlots = (totalQueued > 0) ? Math.min(reqSlots, totalQueued) : 1;

        setupSlots(actualSlots);

        browserOpen = true;
        browserContainer.setVisibility(View.VISIBLE);
        dashWebView.setVisibility(View.GONE);
        updateTopBarStatus();

        for (WorkerSlot slot : slots) {
            dispatchNextForSlot(slot);
        }
    }

    /* หยุดการทำงานและกลับหน้าหลัก (หยุดกลางทาง) */
    private void stopQueueAndCloseBrowser() {
        synchronized (accountQueue) {
            accountQueue.clear();
        }
        browserOpen = false;
        autoCheckMode = false;

        for (WorkerSlot s : slots) {
            s.stopSlot();
        }

        browserContainer.setVisibility(View.GONE);
        dashWebView.setVisibility(View.VISIBLE);
        notifyDashboard("กลับสู่หน้าหลักแล้ว");
    }

    /* ปุ่มย้อนกลับของ Android: หยุดกลางทางและกลับหน้าหลักทันที */
    @Override
    public void onBackPressed() {
        if (browserContainer != null && browserContainer.getVisibility() == View.VISIBLE) {
            stopQueueAndCloseBrowser();
            return;
        }
        super.onBackPressed();
    }

    private void clearBrowserSession(Runnable afterClear) {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(value -> {
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            if (afterClear != null) afterClear.run();
        });
    }

    /* ---------- เช็ค cookie ว่าตรงกับชื่อในชีตไหม (HTTP Background Threads) ---------- */
    private void startCookieCheck(int[] idxs, int concurrency) {
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
        int threads = Math.max(1, Math.min(concurrency, 15));
        notifyDashboard("กำลังเช็ค cookie " + list.size() + " ไอดี (พร้อมกัน " + threads + " threads)...");

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
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
        String user;
        String cookie;
        synchronized (NativeBridge.class) {
            user = NativeBridge.getAccountField(idx, "username");
            cookie = NativeBridge.getAccountField(idx, "cookie");
        }
        if (cookie == null || cookie.length() < 100) {
            synchronized (NativeBridge.class) {
                NativeBridge.updateStatus(idx, "NOCOOKIE",
                        "ยังไม่มี cookie — เปิดไอดีแล้วล็อกอิน/ดึง cookie ก่อน");
            }
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
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
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
                synchronized (NativeBridge.class) {
                    NativeBridge.setUsername(idx, name);
                }
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
        synchronized (NativeBridge.class) {
            NativeBridge.updateStatus(idx, status, note);
        }
    }

    private void notifyDashboard(String msg) {
        runOnUiThread(() -> {
            if (dashWebView == null) return;
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
        lp.leftMargin = dp(4);
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

    private class JsBridge {
        @JavascriptInterface
        public String getAccountsJson() {
            synchronized (NativeBridge.class) {
                return NativeBridge.getAccountsJson();
            }
        }

        @JavascriptInterface
        public String getLiveStatus() {
            if (browserOpen) {
                List<Integer> openList = new ArrayList<>();
                for (WorkerSlot s : slots) {
                    if (s.isActive()) openList.add(s.activeAccountIdx);
                }
                int qCnt;
                synchronized (accountQueue) {
                    qCnt = accountQueue.size();
                }
                return "{\"open\":" + openList.toString() + ",\"manual\":[],\"queue\":" + qCnt + "}";
            }
            return "{\"open\":[],\"manual\":[],\"queue\":0}";
        }

        @JavascriptInterface
        public int addAccount(String username, String password) {
            synchronized (NativeBridge.class) {
                return NativeBridge.addAccount(username, password, "");
            }
        }

        @JavascriptInterface
        public int importText(String text) {
            synchronized (NativeBridge.class) {
                return NativeBridge.importText(text);
            }
        }

        @JavascriptInterface
        public int importCsv(String csvText) {
            synchronized (NativeBridge.class) {
                return NativeBridge.importCsv(csvText);
            }
        }

        @JavascriptInterface
        public String exportCsv(String jsonIndices) {
            int[] idxs = parseIntArray(jsonIndices);
            String csv;
            synchronized (NativeBridge.class) {
                csv = NativeBridge.exportCsv(idxs);
            }
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
            synchronized (NativeBridge.class) {
                return idxs != null ? NativeBridge.deleteIndices(idxs) : 0;
            }
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
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, incognito, true, false, concurrency));
        }

        @JavascriptInterface
        public void openAccountsQueue(String jsonIndices, boolean incognito) {
            openAccountsQueue(jsonIndices, incognito, 2);
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices, int concurrency) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, true, concurrency));
        }

        @JavascriptInterface
        public void checkAccountsQueue(String jsonIndices) {
            checkAccountsQueue(jsonIndices, 2);
        }

        @JavascriptInterface
        public void checkCookies(String jsonIndices, int concurrency) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startCookieCheck(idxs, concurrency));
        }

        @JavascriptInterface
        public void checkCookies(String jsonIndices) {
            checkCookies(jsonIndices, 5);
        }

        @JavascriptInterface
        public void manualOpen(int idx, boolean incognito) {
            int[] idxs = idx >= 0 ? new int[]{idx} : null;
            runOnUiThread(() -> startAccountQueue(idxs, incognito, false, false, 1));
        }

        @JavascriptInterface
        public void dumpCookiesQueue(String jsonIndices, int concurrency) {
            int[] idxs = parseIntArray(jsonIndices);
            runOnUiThread(() -> startAccountQueue(idxs, true, true, false, concurrency));
        }

        @JavascriptInterface
        public void dumpCookiesQueue(String jsonIndices) {
            dumpCookiesQueue(jsonIndices, 2);
        }

        @JavascriptInterface
        public int grabLiveCookie() {
            if (slots.isEmpty()) return 0;
            int idx = Math.max(0, Math.min(focusedSlotIndex, slots.size() - 1));
            return slots.get(idx).grabSlotCookie(false);
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
