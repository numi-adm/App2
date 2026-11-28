package com.rooo.dashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_SAVE_CSV = 1002;
    private static final int REQ_BOT_TEMPLATE = 1003;

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
    private TextView botBadge;   // แสดง log บอทบนแถบ browser (ไม่รบกวนการจับภาพ WebView)
    private boolean tapPointArmed = false;   // กำลังรอผู้ใช้แตะจอเพื่อจับพิกัดกด

    private ValueCallback<Uri[]> fileUploadCallback;
    private String pendingCsvExport = "";

    private int activeAccountIdx = -1;
    private boolean autoFillPending = false;
    private boolean browserOpen = false;
    private boolean currentCleanSession = true;
    private boolean currentAutoFill = true;
    private boolean autoCheckMode = false;
    private boolean autoAdvanceMode = false;
    private String noticedStatus = "";
    private String lastStatus = "";
    private String lastNote = "";
    private String lastGrabStatus = "";
    private int autoRetryCount = 0;
    private int openToken = 0;
    private boolean skipScheduled = false;
    private Runnable advanceRunnable = null;   // แทนที่ delay ได้ (กัน delay เก่าค้างบังปุ่มดึงใหม่)

    private final List<Integer> accountQueue = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* Auto-Hold Bot (พอร์ตจาก FFFFFF/bot.py) */
    private AutoHoldBot autoHoldBot;
    private BotStorage botStorage;
    private volatile boolean pendingTemplateRelease = false;
    private final java.util.concurrent.atomic.AtomicBoolean autoConnectStop =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /* โหมด WebView: จับภาพและส่งทัชจาก robloxWebView โดยตรง (ไม่ต้อง ADB) */
    private volatile String lastWebCaptureError = "";
    private int pixelCopyFails = 0;
    private long pixelCopySkipUntil = 0L;
    private final AutoHoldBot.CaptureProvider webCaptureProvider = new AutoHoldBot.CaptureProvider() {
        @Override public AutoHoldBot.Frame capture() { return captureWebViewFrame(); }
        @Override public String lastError() { return lastWebCaptureError; }
    };
    private long webTouchDownTime = 0L;
    private final AutoHoldBot.TouchProvider webTouchProvider = new AutoHoldBot.TouchProvider() {
        @Override public void down(int x, int y) { webTouchDownTime = SystemClock.uptimeMillis(); postWebTouch(MotionEvent.ACTION_DOWN, x, y, webTouchDownTime); }
        @Override public void move(int x, int y) { postWebTouch(MotionEvent.ACTION_MOVE, x, y, webTouchDownTime); }
        @Override public void up(int x, int y) { postWebTouch(MotionEvent.ACTION_UP, x, y, webTouchDownTime); }
    };

    private final Runnable liveInspectorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!browserOpen || browserContainer.getVisibility() != View.VISIBLE) {
                return;
            }
            inspectCurrentRobloxPage();
            /* ห่างขึ้นจาก 1500ms → 2200ms: ลดภาระ evaluateJavascript WebView
             * (ยังไวอยู่เพราะ onPageFinished + ปุ่มดึงเรียก inspect ทันทีอยู่แล้ว) */
            mainHandler.postDelayed(this, 2200);
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

        botStorage = new BotStorage(this);
        autoHoldBot = new AutoHoldBot((text, color, force) -> notifyBot(text, color, force), botStorage);
        autoHoldBot.setCaptureProvider(webCaptureProvider);
        autoHoldBot.setTouchProvider(webTouchProvider);
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            autoHoldBot.note("🧩 แอปเวอร์ชัน " + pi.versionName
                    + " (build " + pi.versionCode + ")", "#8ecbff");
        } catch (Exception ignored) {
        }

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
        dashWebView.setWebViewClient(new WebViewClient() {
            private boolean autoConnectTriggered = false;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!autoConnectTriggered) {
                    autoConnectTriggered = true;
                    autoConnectBot();
                }
            }
        });
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

        // ปุ่มทดสอบทัช — ใช้เช็กว่าเส้นทาง dispatchTouchEvent ไปถึง WebView หรือไม่
        Button btnTestTap = makeBarButton("👆 ทดสอบแตะ", "#2c6e8f");
        btnTestTap.setOnClickListener(v -> testWebTap(-1, -1));
        topBar.addView(btnTestTap);

        Button btnCloseNext = makeBarButton("ปิด / ถัดไป ⏭", "#a02828");
        btnCloseNext.setOnClickListener(v -> closeAndOpenNextAccount());
        topBar.addView(btnCloseNext);

        browserContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        /* แถวบอทเต็มความกว้าง — แตะเพื่อเปิด/หยุดบอท (ปุ่มบนแถบเครื่องมือมีเยอะจนล้นจอ) */
        botBadge = new TextView(this);
        botBadge.setTextColor(Color.parseColor("#8ecbff"));
        botBadge.setTextSize(10.5f);
        botBadge.setSingleLine(true);
        botBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        botBadge.setText("🤖 บอท: ปิดอยู่ — แตะบรรทัดนี้เพื่อเริ่ม/หยุด");
        botBadge.setBackgroundColor(Color.parseColor("#202a33"));
        botBadge.setPadding(dp(8), dp(5), dp(8), dp(5));
        botBadge.setOnClickListener(v -> toggleBotFromBar());
        browserContainer.addView(botBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        /* แถวพิกัดกด — แตะปุ่มแล้วแตะบนหน้าเกมเพื่อบันทึกจุดที่อยากให้บอทกด */
        LinearLayout tapRow = new LinearLayout(this);
        tapRow.setOrientation(LinearLayout.HORIZONTAL);
        tapRow.setBackgroundColor(Color.parseColor("#202a33"));
        Button btnArmTap = makeBarButton("📍 จับพิกัด(WebV)", "#1d6a4a");
        btnArmTap.setOnClickListener(v -> {
            tapPointArmed = true;
            Toast.makeText(this, "แตะบนหน้าเกม 1 ครั้งเพื่อบันทึกพิกัด (โหมด WebView)", Toast.LENGTH_SHORT).show();
            if (autoHoldBot != null) {
                autoHoldBot.note("📍 พร้อมจับพิกัด — แตะบนหน้าเกม 1 ครั้ง", "#8ecbff");
            }
        });
        tapRow.addView(btnArmTap, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnClearPoints = makeBarButton("🗑 ล้างพิกัด", "#5a3030");
        btnClearPoints.setOnClickListener(v -> clearTapPointsFromBar());
        tapRow.addView(btnClearPoints, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        browserContainer.addView(tapRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        robloxWebView = new WebView(this);
        /* ดักแตะจอเพื่อจับพิกัด — return false ไม่ไปขัดการทำงานปกติของ WebView */
        robloxWebView.setOnTouchListener((v, event) -> {
            if (tapPointArmed && event.getAction() == MotionEvent.ACTION_DOWN) {
                tapPointArmed = false;
                addTapPointFromBar(Math.round(event.getX()), Math.round(event.getY()));
            }
            return false;
        });
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
                    // ไม่จับเวลา — รอเหตุการณ์จากหน้าเว็บอย่างเดียว
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
        /* ระหว่างบอท WebView กำลังกดค้าง/สแกนหน้าเกม ไม่ต้องตรวจสถานะหรือเปลี่ยนหน้า
         * (การ navigate/ปิดแผงจะทำให้บอทหลุดโฟกัสและจับภาพไม่ได้) */
        if (botRunningInWebView()) return;

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

                // 1. ถ้าได้ Cookie แล้ว → บันทึกแล้วไปไอดีถัดไปทันที
                //    (C classifier เซฟ cookie ลง DB ให้แล้วที่นี่ — เหลือแค่เดินคิว)
                //    ต้องทำงานทุกโหมด รวมปุ่ม "▶ เปิด" ธรรมดาที่ autoCheck/autoAdvance เป็น false
                if (hasCk) {
                    browserStatusBadge.setText("สถานะ: " + st + " — " + note);
                    browserStatusBadge.setTextColor(Color.parseColor("#44ff77"));
                    notifyDashboard(null);
                    if (autoCheckMode || autoAdvanceMode || !accountQueue.isEmpty()) {
                        scheduleAdvance(400);
                    }
                    return;
                }

                // 2. ข้ามแต่ รีโคบเวอร์ลี กับ รหัสไม่ตรง (ไม่รีลองใหม่)
                if (isSkipStatus(st)) {
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

                // 3. ขึ้น VERIFY/CAPTCHA → ค้างหน้าต่างไว้รอผู้ใช้กดยืนยันเอง
                //    ไม่จับเวลา / ไม่รีลอง / ไม่ข้ามไอดีกลางทาง
                //    เมื่อยืนยันเสร็จ hasCookie จะเป็น true แล้วระบบดึง cookie + ไปตัวถัดไปเอง
                if ("VERIFY".equals(st) || "CAPTCHA".equals(st)) {
                    browserStatusBadge.setText("สถานะ: " + st
                            + " — กดยืนยันในหน้าต่าง ระบบจะดึง cookie ให้เอง");
                    browserStatusBadge.setTextColor(Color.parseColor("#ffaa00"));
                    notifyDashboard(null);
                    return;
                }

                // 4. เฉพาะ RETRY (Something went wrong / เกิดข้อผิดพลาดที่รีเซ็ตหน้า) → รีลองใหม่
                if ("RETRY".equals(st)) {
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
                        if (!skipScheduled) {
                            skipScheduled = true;
                            skipCurrentAccount();
                        }
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
        } else if (requestCode == REQ_BOT_TEMPLATE) {
            handleBotTemplateResult(resultCode, data);
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
            /* ลบ storage/cache ทุกครั้งที่สลับไอดีทำให้คิวกระตุกมาก —
             * cookie ถูกล้างแล้วพอสำหรับแยกเซสชัน (web storage ของ roblox เดินผ่าน cookie หลัก)
             * คง clearHistory ไว้ให้ปุ่ม back ไม่ย้อนกลับไปไอดีเก่า */
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
        cancelScheduledAdvance();

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
        noticedStatus = "";
        lastStatus = "";
        lastNote = "";
        autoRetryCount = 0;
        skipScheduled = false;
        cancelScheduledAdvance();   // ยกเลิก delay เก่าของไอดีก่อนหน้า (กันข้ามไอดี/ค้าง)
        openToken++;

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
        final int token = openToken;
        browserStatusBadge.setText("กำลังสแกนหน้าเว็บก่อนดึง cookie...");
        browserStatusBadge.setTextColor(Color.parseColor("#99ccff"));
        inspectCurrentRobloxPage();
        mainHandler.postDelayed(() -> {
            if (!browserOpen || activeAccountIdx != idx || token != openToken) return;
            int got = grabCookieInternal();
            boolean ok = got > 0 && "WORK".equals(lastGrabStatus);
            browserStatusBadge.setText(got > 0
                    ? ("สถานะ: " + lastGrabStatus + (ok ? " — บันทึก Cookie แล้ว" : " — " + lastNote))
                    : "ยังไม่มี cookie ในหน้าต่างนี้ — ดำเนินคิวต่อ");
            browserStatusBadge.setTextColor(Color.parseColor(got > 0 && !ok ? "#ff8844" : "#44ff77"));

            /* สำคัญ: ต่อให้หา cookie ไม่เจอ ก็ยังไปตัวถัดไปได้ (ถ้ามีคิว) —
             * เดิม return ทิ้งตรงนี้เลยทำให้คิวค้างอยู่ไอดีเดียว */
            boolean hasNext = !accountQueue.isEmpty() || autoAdvanceMode || autoCheckMode;
            if (hasNext) {
                notifyDashboard(got > 0
                        ? (ok ? "ดึง cookie สำเร็จ — ไปไอดีถัดไป"
                              : "บันทึก cookie แล้ว (สถานะ " + lastGrabStatus + ") — ไปไอดีถัดไป")
                        : "ยังไม่มี cookie — ข้ามไปไอดีถัดไปในคิว");
                scheduleAdvance(250);   // เร็วขึ้นจาก 1000ms + 500ms scan → ~250ms
            } else {
                notifyDashboard(got > 0
                        ? (ok ? "ดึง cookie สำเร็จ (ไอดีเดียว — กด 'ปิด / ถัดไป' เพื่อจบ)"
                              : "บันทึก cookie แล้ว (สถานะ " + lastGrabStatus + ")")
                        : "ยังไม่มี cookie — เช็คสถานะบนแถบด้านบน");
            }
        }, 180);   // สแกนเร็วขึ้นจาก 500ms → 180ms
    }

    /* หน่วงเวลาแล้วไปไอดีถัดไป (กันสั่งซ้ำ/ข้ามไอดี)
     * ฉลาดขึ้น: ทุกครั้งที่ schedule ใหม่ จะยกเลิก delay เก่าก่อน → ไม่มีกรณี
     * delay เก่าค้างแล้วปุ่มดึงใหม่ถูกเมิน (สาเหตุหลักของ "ดึงแล้วไม่ไปต่อ") */
    private void scheduleAdvance(int delayMs) {
        if (!browserOpen) return;
        final int token = openToken;
        if (advanceRunnable != null) mainHandler.removeCallbacks(advanceRunnable);
        advanceRunnable = () -> {
            advanceRunnable = null;
            if (token == openToken && browserOpen) closeAndOpenNextAccount();
        };
        mainHandler.postDelayed(advanceRunnable, delayMs);
    }

    /** ยกเลิกการเดินคิวที่หน่วงไว้ (ใช้ตอนเปิดไอดีใหม่/หยุดคิว/ลองใหม่) */
    private void cancelScheduledAdvance() {
        if (advanceRunnable != null) {
            mainHandler.removeCallbacks(advanceRunnable);
            advanceRunnable = null;
        }
    }

    /* ไปไอดีถัดไปในคิว โดยไม่ดึง cookie ซ้ำ */
    private void advanceToNext() {
        if (!browserOpen) return;
        if (!accountQueue.isEmpty()) {
            int nextIdx = accountQueue.remove(0);
            openAccountInWebView(nextIdx, currentCleanSession, currentAutoFill);
        } else if (botRunningInWebView()) {
            /* บอท WebView ต้องใช้แผงนี้จับภาพ — ห้ามปิดแผงตอนคิวหมด ไม่งั้นบอทจะดึงภาพไม่ได้ */
            autoCheckMode = false;
            autoAdvanceMode = false;
            cancelScheduledAdvance();
            notifyDashboard("ครบทุกไอดีในคิวแล้ว — คงหน้าเว็บไว้ให้บอททำงานต่อ");
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

    private void maybeAutoRetry(String reason) {
        if (!browserOpen || !currentAutoFill || activeAccountIdx < 0) return;
        if (autoRetryCount >= 3) return;
        retryLoginNow(reason);
    }

    /* รีเซ็ตหน้าเว็บและลองใหม่อัตโนมัติ (สูงสุด 3 ครั้ง) */
    private void retryLoginNow(String reason) {
        if (activeAccountIdx < 0) return;
        autoRetryCount++;
        final int token = openToken;
        lastStatus = "";
        noticedStatus = "";
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
        /* แผงนี้คือแหล่งภาพของบอท WebView — ปิดแผงแล้วบอทจะดึงภาพไม่ได้ตลอด
         * จึงหยุดบอทให้ก่อน แทนที่จะปล่อยให้ฟ้อง error รัว ๆ */
        if (botRunningInWebView()) {
            autoHoldBot.stop();
            notifyBot("⏹ ปิดแผง browser — หยุดบอทให้อัตโนมัติ", "#7f8c8d", true);
        }
        grabCookieInternal();
        accountQueue.clear();
        browserOpen = false;
        autoFillPending = false;
        autoCheckMode = false;
        autoAdvanceMode = false;
        cancelScheduledAdvance();
        openToken++;
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

    // ------------------------------------------------------------------
    // Auto-Hold Bot: โหมด WebView / เชื่อมต่อ ADB อัตโนมัติ / นำเข้าต้นแบบ
    // ------------------------------------------------------------------

    /** จับภาพ robloxWebView ที่กำลังมองเห็น → RGBA (เรียกจาก worker thread)
     *  ลำดับ: PixelCopy (ได้ surface จริงรวม WebGL) → WebView.draw() (สำรอง)
     *  ถ้าทั้งคู่ไม่ได้ จะเก็บเหตุผลไว้ใน lastWebCaptureError ให้ log บอกได้ว่าติดอะไร */
    private AutoHoldBot.Frame captureWebViewFrame() {
        if (robloxWebView == null || browserContainer == null) {
            lastWebCaptureError = "ยังไม่มี WebView";
            return null;
        }
        if (browserContainer.getVisibility() != View.VISIBLE) {
            lastWebCaptureError = "แผง browser ปิดอยู่";
            return null;
        }
        final int w = robloxWebView.getWidth(), h = robloxWebView.getHeight();
        if (w <= 4 || h <= 4) {
            lastWebCaptureError = "WebView ขนาด " + w + "x" + h;
            return null;
        }
        if ((long) w * h > 8_000_000L) {
            lastWebCaptureError = "WebView ใหญ่เกิน " + w + "x" + h;
            return null;
        }

        AutoHoldBot.Frame f = null;
        boolean skipPixelCopy = System.currentTimeMillis() < pixelCopySkipUntil;
        if (!skipPixelCopy) {
            f = capturePixelCopy(w, h);
            if (f != null) {
                pixelCopyFails = 0;
                pixelCopySkipUntil = 0L;
                lastWebCaptureError = "";
                return f;
            }
            String pcErr = lastWebCaptureError;
            /* PixelCopy ล้มติดกันหลายครั้ง (บางเครื่อง/บางจอไม่ให้แคป) → ข้ามไปใช้ draw()
             * สักพักเพื่อไม่ให้ทุกรอบเสียเวลา 0.8 วิ แล้วค่อยลองใหม่ */
            if (++pixelCopyFails >= 5) {
                pixelCopySkipUntil = System.currentTimeMillis() + 30000L;
                pixelCopyFails = 0;
                if (autoHoldBot != null) {
                    autoHoldBot.note("⚠️ PixelCopy ใช้ไม่ได้ (" + pcErr + ") — ใช้ draw() แทนชั่วคราว", "#e67e22");
                }
            }
            f = captureWebViewDraw(w, h);
            if (f != null) {
                lastWebCaptureError = "";
                return f;
            }
            lastWebCaptureError = "PixelCopy " + (pcErr.isEmpty() ? "ล้มเหลว" : pcErr)
                    + " / draw " + (lastWebCaptureError.isEmpty() ? "ล้มเหลว" : lastWebCaptureError);
            return null;
        }

        f = captureWebViewDraw(w, h);
        if (f != null) {
            lastWebCaptureError = "";
            return f;
        }
        return null;
    }

    /** แคปจาก surface ของหน้าต่างจริง (ภาพตรงกับที่เห็น รวม canvas/WebGL) */
    private AutoHoldBot.Frame capturePixelCopy(final int w, final int h) {
        if (Build.VERSION.SDK_INT < 26) {
            lastWebCaptureError = "Android ต่ำกว่า 8";
            return null;
        }
        final Bitmap[] bmp = new Bitmap[1];
        final int[] err = new int[1];
        final boolean[] ok = new boolean[1];
        final boolean[] abandoned = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                if (getWindow() == null || !robloxWebView.isAttachedToWindow()) {
                    err[0] = -1;
                } else {
                    bmp[0] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    int[] loc = new int[2];
                    robloxWebView.getLocationInWindow(loc);
                    final Rect rect = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
                    final Bitmap shot = bmp[0];
                    PixelCopy.request(getWindow(), rect, shot, result -> {
                        try {
                            if (result == PixelCopy.SUCCESS) {
                                ok[0] = true;
                            } else {
                                err[0] = result;
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (abandoned[0]) shot.recycle();
                            latch.countDown();
                        }
                    }, new Handler(Looper.getMainLooper()));
                    return;   // รอ callback เป็นคนนับ latch
                }
            } catch (Exception e) {
                err[0] = -2;
                lastWebCaptureError = "ข้อยกเว้น " + e.getClass().getSimpleName();
            }
            latch.countDown();
        });

        try {
            if (!latch.await(800, TimeUnit.MILLISECONDS)) {
                abandoned[0] = true;   // callback จะ recycle เองถ้ามาถึงทีหลัง
                lastWebCaptureError = "หมดเวลา";
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        Bitmap shot = bmp[0];
        if (!ok[0] || shot == null) {
            if (err[0] == -1) lastWebCaptureError = "WebView ยังไม่ถูกวาดบนจอ";
            else if (err[0] == -2) { /* เก็บข้อความไว้แล้ว */ }
            else lastWebCaptureError = "error " + err[0];
            if (shot != null) shot.recycle();
            return null;
        }
        try {
            return new AutoHoldBot.Frame(w, h, AutoHoldBot.bitmapToRgba(shot));
        } catch (Exception e) {
            lastWebCaptureError = "แปลงภาพไม่ได้";
            return null;
        } finally {
            shot.recycle();
        }
    }

    /** สำรอง: วาด WebView ลง bitmap ตรง ๆ (ไม่ต้องพึ่ง surface/สิทธิ์แคปจอ) */
    private AutoHoldBot.Frame captureWebViewDraw(final int w, final int h) {
        final AutoHoldBot.Frame[] out = new AutoHoldBot.Frame[1];
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            Bitmap bm = null;
            try {
                bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                robloxWebView.draw(new Canvas(bm));
                out[0] = new AutoHoldBot.Frame(w, h, AutoHoldBot.bitmapToRgba(bm));
            } catch (Exception e) {
                lastWebCaptureError = "ข้อยกเว้น " + e.getClass().getSimpleName();
            } finally {
                if (bm != null) bm.recycle();
                latch.countDown();
            }
        });
        try {
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
                lastWebCaptureError = "หมดเวลา";
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }

    /** ส่งทัชจริงเข้า WebView (trusted event — ใช้กับ canvas/WebGL ได้) */
    private void postWebTouch(final int action, final int x, final int y, final long downTime) {
        runOnUiThread(() -> {
            try {
                if (robloxWebView == null) return;
                robloxWebView.requestFocus();
                long now = SystemClock.uptimeMillis();
                long dt = downTime > 0 ? downTime : now;
                MotionEvent ev = MotionEvent.obtain(dt, now, action, x, y, 1.0f, 1.0f, 0,
                        1.0f, 1.0f, 0, 0);
                /* obtain(...) 12 พารามิเตอร์ ตัวสุดท้ายคือ edgeFlags ไม่ใช่ source
                 * ต้องตั้ง source เอง ไม่งั้น WebView เห็นเป็น SOURCE_UNKNOWN */
                ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    robloxWebView.dispatchTouchEvent(ev);
                } finally {
                    ev.recycle();
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** แตะบรรทัดบอทบนแถบ browser เพื่อเปิด/หยุดบอท (ใช้ค่าที่บันทึกไว้จากหน้า dashboard) */
    private void toggleBotFromBar() {
        if (autoHoldBot == null) return;
        if (autoHoldBot.isRunning()) {
            autoHoldBot.stop();
            return;
        }
        JSONObject cfg = botStorage.loadConfig();
        try {
            cfg.put("controlMode", "webview");
        } catch (Exception ignored) {
        }
        autoHoldBot.setControlMode("webview");
        /* start() มีการแคปทดสอบ (รอ UI thread) — ห้ามเรียกบน UI thread ตรง ๆ ไม่งั้นจอค้าง */
        new Thread(() -> autoHoldBot.start(cfg), "bot-start").start();
    }

    private boolean botRunningInWebView() {
        return autoHoldBot != null && autoHoldBot.isRunning()
                && "webview".equals(autoHoldBot.controlMode());
    }

    /** บันทึกพิกัดที่ผู้ใช้แตะบนหน้าเกม แล้วเปิดโหมด "กดตามพิกัด" ทันที */
    private void addTapPointFromBar(int x, int y) {
        try {
            JSONObject cfg = botStorage.loadConfig();
            JSONArray arr = cfg.optJSONArray("tapPoints");
            if (arr == null) arr = new JSONArray();
            JSONArray p = new JSONArray();
            p.put(x);
            p.put(y);
            arr.put(p);
            cfg.put("tapPoints", arr);
            cfg.put("tapMode", "coords");
            botStorage.saveConfig(cfg);
            autoHoldBot.reloadPoints(cfg);
            autoHoldBot.note("📍 บันทึกพิกัดกด (" + x + ", " + y + ") — รวม " + arr.length() + " จุด", "#27ae60");
            syncBotPointsToDashboard();
        } catch (Exception e) {
            if (autoHoldBot != null) {
                autoHoldBot.note("❌ บันทึกพิกัดไม่ได้: " + e.getMessage(), "#c0392b");
            }
        }
    }

    private void clearTapPointsFromBar() {
        try {
            JSONObject cfg = botStorage.loadConfig();
            cfg.put("tapPoints", new JSONArray());
            cfg.put("tapMode", "template");
            botStorage.saveConfig(cfg);
            autoHoldBot.reloadPoints(cfg);
            autoHoldBot.note("🗑 ล้างพิกัดกดแล้ว — กลับไปกดกลางต้นแบบ", "#e67e22");
            syncBotPointsToDashboard();
        } catch (Exception e) {
            if (autoHoldBot != null) {
                autoHoldBot.note("❌ ล้างพิกัดไม่ได้: " + e.getMessage(), "#c0392b");
            }
        }
    }

    private void syncBotPointsToDashboard() {
        if (autoHoldBot == null) return;
        runOnUiThread(() -> dashWebView.evaluateJavascript(
                "if(window.onBotPointsUpdate) window.onBotPointsUpdate("
                        + autoHoldBot.tapPointsJson() + ");", null));
    }

    /** ทดสอบส่งทัชเข้า WebView (x,y < 0 = กึ่งกลาง) — ใช้ดีบักว่าบอทกดได้จริงไหม */
    private void testWebTap(int x, int y) {
        if (robloxWebView == null || browserContainer == null
                || browserContainer.getVisibility() != View.VISIBLE) {
            Toast.makeText(this, "เปิดแผง browser/game ก่อนครับ", Toast.LENGTH_SHORT).show();
            return;
        }
        int w = robloxWebView.getWidth(), h = robloxWebView.getHeight();
        if (x < 0 || y < 0) {
            x = w / 2;
            y = h / 2;
        }
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
            Toast.makeText(this, "ขนาด WebView ไม่พร้อมครับ", Toast.LENGTH_SHORT).show();
            return;
        }
        final int fx = x, fy = y;
        webTouchProvider.down(fx, fy);
        mainHandler.postDelayed(() -> webTouchProvider.up(fx, fy), 90);
        Toast.makeText(this, "ส่งทัชทดสอบที่ (" + fx + ", " + fy + ")", Toast.LENGTH_SHORT).show();
        notifyBot("👆 ทดสอบแตะที่ (" + fx + ", " + fy + ")", "#8ecbff", true);
    }

    /** พยายามเชื่อมต่อ ADB อัตโนมัติตอนเปิดแอป (ไม่บล็อก UI thread) */
    private void autoConnectBot() {
        autoConnectBot(false);
    }

    private void autoConnectBot(boolean force) {
        if (autoHoldBot == null) return;
        if (force) autoHoldBot.setControlMode("adb");
        new Thread(() -> {
            String host = "127.0.0.1";
            int port = 16384;
            boolean enabled = true;
            String controlMode = "webview";
            try {
                JSONObject c = botStorage.loadConfig();
                enabled = !c.has("autoConnect") || c.optBoolean("autoConnect", true);
                host = c.optString("host", host);
                port = c.optInt("port", port);
                controlMode = c.optString("controlMode", controlMode);
            } catch (Exception ignored) {
            }

            if (!force && "webview".equals(controlMode)) {
                notifyBot("⏭ ข้าม ADB auto-connect (โหมด WebView — กดในหน้าเว็บในแอป)", "#7f8c8d", true);
                return;
            }
            if (!force && !enabled) {
                notifyBot("⏭ ข้าม ADB auto-connect (ปิดไว้ในตั้งค่า)", "#7f8c8d", true);
                return;
            }

            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            java.util.List<String[]> targets = new java.util.ArrayList<>();
            addBotTarget(targets, seen, host, port);          // ค่าที่บันทึกไว้ก่อนเสมอ
            /* พอร์ต ADB ของ MuMu: 12 = 16384 + 32×ลำดับจอ (จอ 1=16384, จอ 2=16416, จอ 3=16448)
             * 7555 = MuMu เวอร์ชันเก่า — ลองทั้งบน host ที่บันทึกไว้และบนเครื่องตัวเอง */
            int[] mumuPorts = {16384, 16416, 16448, 7555};
            for (int mp : mumuPorts) addBotTarget(targets, seen, host, mp);
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                for (int mp : mumuPorts) addBotTarget(targets, seen, "127.0.0.1", mp);
            }

            for (int attempt = 1; attempt <= 12 && !autoConnectStop.get(); attempt++) {
                if (autoHoldBot.isConnected()) break;
                for (String[] t : targets) {
                    if (autoConnectStop.get()) return;
                    if (autoHoldBot.connect(t[0], Integer.parseInt(t[1]), 2500)) {
                        final String h = t[0];
                        final String p = t[1];
                        runOnUiThread(() -> {
                            try {
                                JSONObject j = new JSONObject();
                                j.put("ok", true).put("host", h).put("port", Integer.parseInt(p));
                                dashWebView.evaluateJavascript(
                                        "if(window.onBotAutoConnected) window.onBotAutoConnected("
                                                + j.toString() + ");", null);
                            } catch (Exception ignored) {
                            }
                        });
                        notifyBot("🔌 เชื่อมต่อ ADB อัตโนมัติแล้ว: " + h + ":" + p, "#27ae60", true);
                        return;
                    }
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!autoHoldBot.isConnected()) {
                notifyBot("⚠️ ADB auto-connect ไม่สำเร็จ — กด 🔌 เชื่อมต่อเองได้", "#e67e22", true);
            }
        }, "adb-autoconnect").start();
    }

    private static void addBotTarget(java.util.List<String[]> out,
                                     java.util.Set<String> seen, String host, int port) {
        if (host == null) return;
        String h = host.trim();
        if (h.isEmpty()) return;
        String key = h + ":" + port;
        if (seen.add(key)) out.add(new String[]{h, String.valueOf(port)});
    }

    /** ผลจาก file picker: เพิ่มทุกไฟล์ภาพที่เลือกเป็นต้นแบบกด/ปล่อย */
    private void handleBotTemplateResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        final boolean release = pendingTemplateRelease;
        int ok = 0;
        for (Uri u : uris) {
            if (addTemplateFile(u, release)) ok++;
        }
        final int total = uris.size(), done = ok;
        notifyBot("📂 นำเข้าต้นแบบ" + (release ? "ปล่อย" : "กด") + "จากไฟล์ "
                + done + "/" + total + " ไฟล์", done > 0 ? "#27ae60" : "#c0392b", true);
        runOnUiThread(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("ok", done).put("total", total).put("release", release);
                dashWebView.evaluateJavascript(
                        "if(window.onBotFilesAdded) window.onBotFilesAdded(" + j.toString() + ");", null);
            } catch (Exception ignored) {
            }
        });
    }

    private boolean addTemplateFile(Uri uri, boolean release) {
        if (autoHoldBot == null) return false;
        Bitmap bm = null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            bm = BitmapFactory.decodeStream(in);
            if (bm == null) return false;
            byte[] rgba = AutoHoldBot.bitmapToRgba(bm);
            return autoHoldBot.addRawTemplate(rgba, bm.getWidth(), bm.getHeight(), release);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (bm != null) bm.recycle();
        }
    }

    /** ส่งสถานะบอทไปยังหน้า dashboard.html */
    private void notifyBot(String text, String color, boolean force) {
        runOnUiThread(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("text", text == null ? "" : text);
                j.put("color", color == null ? "#3498db" : color);
                j.put("force", force);
                if (botBadge != null) {
                    botBadge.setText("🤖 " + (text == null ? "" : text));
                    try {
                        botBadge.setTextColor(Color.parseColor(color));
                    } catch (Exception ignored) {
                    }
                }
                dashWebView.evaluateJavascript(
                        "if(window.onBotUpdate) window.onBotUpdate(" + j.toString() + ");", null);
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        autoConnectStop.set(true);
        if (autoHoldBot != null) autoHoldBot.stop();
        super.onDestroy();
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

        // ---------------- Auto-Hold Bot (FFFFFF) ----------------
        @JavascriptInterface
        public String botConnect(String host, int port) {
            autoHoldBot.setControlMode("adb");
            boolean ok = autoHoldBot.connect(host, port);
            JSONObject j = new JSONObject();
            try {
                j.put("ok", ok);
                j.put("status", autoHoldBot.statusJson());
            } catch (Exception ignored) {
            }
            return j.toString();
        }

        @JavascriptInterface
        public String botStatus() {
            return autoHoldBot.statusJson();
        }

        @JavascriptInterface
        public String botTestTap(int x, int y) {
            testWebTap(x, y);
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String botCapture() {
            return autoHoldBot.captureJson();
        }

        @JavascriptInterface
        public boolean botAddTemplate(String pngBase64, String boxJson, boolean release) {
            return autoHoldBot.addTemplate(pngBase64, boxJson, release);
        }

        @JavascriptInterface
        public void botClearTemplates(boolean release) {
            autoHoldBot.clearTemplates(release);
        }

        @JavascriptInterface
        public void botStart(String configJson) {
            try {
                autoHoldBot.start(new JSONObject(configJson));
            } catch (Exception e) {
                notifyBot("❌ ตั้งค่าบอทไม่ถูกต้อง: " + e.getMessage(), "#c0392b", true);
            }
        }

        @JavascriptInterface
        public void botStop() {
            autoHoldBot.stop();
        }

        /* ---- โฟลเดอร์เก็บต้นแบบ / ตั้งค่า / log ---- */
        @JavascriptInterface
        public String botFolder() {
            return autoHoldBot.folderPath();
        }

        @JavascriptInterface
        public String botTemplateFolder() {
            return autoHoldBot.templateFolderPath();
        }

        @JavascriptInterface
        public String botSavedList() {
            return autoHoldBot.savedListJson();
        }

        @JavascriptInterface
        public boolean botLoadSaved(String name) {
            return autoHoldBot.loadSaved(name);
        }

        @JavascriptInterface
        public boolean botDeleteSaved(String name) {
            return autoHoldBot.deleteSaved(name);
        }

        @JavascriptInterface
        public int botDeleteAllSaved() {
            return autoHoldBot.deleteAllSaved();
        }

        @JavascriptInterface
        public String botLoadConfig() {
            return autoHoldBot.loadConfigJson();
        }

        @JavascriptInterface
        public boolean botSaveConfig(String json) {
            return autoHoldBot.saveConfigJson(json);
        }

        @JavascriptInterface
        public String botLog() {
            return autoHoldBot.logTail();
        }

        @JavascriptInterface
        public void botClearLog() {
            autoHoldBot.clearLog();
        }

        /** เปิด file picker เลือกไฟล์ต้นแบบ (กด/ปล่อย) ไม่ต้องแคปจอใหม่ */
        @JavascriptInterface
        public void botPickTemplate(boolean release) {
            pendingTemplateRelease = release;
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(intent, "เลือกไฟล์ต้นแบบ"),
                            REQ_BOT_TEMPLATE);
                } catch (Exception e) {
                    notifyBot("❌ เปิดตัวเลือกไฟล์ไม่ได้: " + e.getMessage(), "#c0392b", true);
                }
            });
        }

        /** สั่งลองเชื่อมต่อ ADB อัตโนมัติอีกครั้ง (จากปุ่มใน UI) */
        @JavascriptInterface
        public void botAutoConnectNow() {
            autoConnectBot(true);
        }
    }
}
