package com.rooo.dashboard;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AutoHoldBot — พอร์ตระบบ FFFFFF/bot.py มาเป็นฟีเจอร์ในแอป Android (ปรับแต่งสำหรับ MuMu)
 * แคปจอผ่าน ADB → ตรวจจับด้วย C core (template/สี) → กดค้าง/ปล่อยแบบ humanize
 *
 * ปรับให้ฉลาดขึ้นสำหรับ MuMu 12 (SDK 32, `input motionevent` เป็น native เร็ว ~20ms):
 * - ปล่อยนิ้วทันทีที่ชาร์จเต็ม (ไม่รอเธรด jitter หลับค้างแบบเดิมที่ดีเลย์ถึง ~1 วิ)
 * - สแกนภาพชาร์จเต็มใน ROI เดียวกับฝั่งกด (เร็วขึ้นมากเมื่อตั้ง ROI)
 * - ภาพปล่อยชัดมาก (score ≥ 0.97) = ยืนยันทันที ไม่ต้องรอ 2 เฟรม
 * - ตัดโหมด swipe/pkill/early-release ที่ไม่จำเป็นสำหรับ MuMu ออกหมด
 */
final class AutoHoldBot {

    interface Callback {
        void onStatus(String text, String color, boolean force);
    }

    /** โหมด WebView: แอปเป็นคนจับภาพ/ส่งทัชเอง (ไม่ต้องใช้ ADB) */
    interface CaptureProvider {
        Frame capture();   // เรียกจาก worker thread — implementation ต้องจัดการ UI thread เอง
        /** เหตุผลที่แคปไม่ได้ครั้งล่าสุด (ว่าง = ปกติ) — เอาไปโชว์ใน log ให้รู้ว่าติดตรงไหน */
        default String lastError() { return ""; }
    }

    interface TouchProvider {
        void down(int x, int y);
        void move(int x, int y);
        void up(int x, int y);
    }

    /** อ่านข้อความที่แสดงอยู่ในหน้าเว็บ (เรียกจาก worker thread — ต้องจัดการ UI thread เอง) */
    interface TextProvider {
        String visibleText();
    }

    private static final String TAG = "AutoHoldBot";
    private final Callback cb;
    private final BotStorage storage;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random rnd = new Random();
    private final List<Tpl> templates = new ArrayList<>();
    private final List<Tpl> releaseTemplates = new ArrayList<>();
    private final Object holdLock = new Object();

    private volatile boolean stopFlag = false;
    private Thread worker;
    private Thread drifter;
    private final AtomicBoolean drifterStop = new AtomicBoolean(false);
    private AdbClient adb;
    private CaptureProvider captureProvider;
    private TouchProvider touchProvider;
    private TextProvider textProvider;
    private String controlMode = "webview";   // "webview" | "adb"
    private volatile boolean holding = false;   // นิ้วกำลังกดค้างอยู่ (กัน MOVE แทรกหลัง UP)
    private JSONObject cfg = new JSONObject();
    private String host = "127.0.0.1";
    private int port = 16384;
    private int sdk = 30;
    private String holdMode = "idle";
    private int pressX = 0, pressY = 0;
    private int[] targetRect = null;
    /* พิกัดกดที่ผู้ใช้กำหนดเอง (โหมด "coords") — ใช้แทนจุดกลางกรอบ template */
    private final List<int[]> tapPoints = new ArrayList<>();
    private String tapMode = "template";
    private int tapCursor = 0;
    private int holdMs = 1200;
    private boolean stopAfterCharge = true;   // ชาร์จเต็มแล้วปิดบอททันที (เปิดเซสชันใหม่จะเริ่มใหม่)
    /* Watchdog: ตอนบอทปิดอยู่ให้ดึงภาพตรวจทุก ๆ 2 วิ ถ้าเจอต้นแบบอีกครั้งให้เปิดบอทเอง */
    private boolean watchdogEnabled = true;
    private int watchdogSec = 2;
    private final AtomicBoolean watchdogOn = new AtomicBoolean(false);
    private Thread watchdogThread;
    private JSONObject watchdogCfg;

    private double lastBestScore = 0.0;   // คะแนน-template สูงสุดล่าสุด (ไว้ดีบัก)
    private double lastBestScale = 1.0;   // สเกลต้นแบบที่ให้คะแนนดีที่สุด
    private double stickyScale = 1.0;     // สเกลที่เคย-success → ลองก่อนเสมอ (ประหยัดเวลา)
    private long scanTicks = 0;           // นับรอบสแกน เพื่อสลับระหว่างสแกนสเกลเดียว/สแกนเต็ม
    private long lastTextWarn = 0L;
    private String lastTextSample = "";   // ตัวอย่างข้อความที่อ่านได้ล่าสุด (ไว้ดีบัก)
    private int lastBestTplW = 0, lastBestTplH = 0;   // ขนาดต้นแบบที่ให้คะแนนดีที่สุด
    private long lastDiagEmit = 0L;
    private int frames = 0;
    private int captures = 0;
    private int presses = 0;      // จำนวนครั้งที่กดค้าง→ปล่อยสำเร็จในรอบนี้
    private String lastText = "พร้อมทำงาน";
    private long lastEmit = 0;
    private String lastEmitText = "";

    AutoHoldBot(Callback cb, BotStorage storage) {
        this.cb = cb;
        this.storage = storage != null ? storage : new BotStorage(null);
        /* โหลดต้นแบบที่เคยบันทึกไว้ในโฟลเดอร์กลับมาใช้ทันที */
        try {
            for (BotStorage.SavedTemplate st : this.storage.loadAll()) {
                int[] b = (st.box != null && st.box.length >= 4) ? st.box : new int[]{0, 0, st.w, st.h};
                Tpl t = new Tpl(st.rgba, st.w, st.h, b, st.name);
                if (st.release) releaseTemplates.add(t);
                else templates.add(t);
            }
        } catch (Exception e) {
            Log.w(TAG, "load saved templates: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // โหมดควบคุม: WebView (ในแอป) หรือ ADB (จอภายนอก)
    // ------------------------------------------------------------------
    void setControlMode(String mode) {
        if ("adb".equals(mode) || "webview".equals(mode)) controlMode = mode;
    }

    String controlMode() {
        return controlMode;
    }

    void setCaptureProvider(CaptureProvider p) {
        captureProvider = p;
    }

    void setTouchProvider(TouchProvider p) {
        touchProvider = p;
    }

    void setTextProvider(TextProvider p) {
        textProvider = p;
    }

    /** เขียนข้อความลง log ของบอท (ให้ฝั่งแอปรายงานเหตุ เช่น แคปภาพไม่สำเร็จ) */
    void note(String text, String color) {
        emit(text, color, true);
    }

    // ------------------------------------------------------------------
    // พิกัดกดที่กำหนดเอง (กดตำแหน่งที่ผู้ใช้แตะ แทนกลางต้นแบบ)
    // ------------------------------------------------------------------
    /** โหลด/โหลดใหม่จาก config — เรียกได้แม้บอทกำลังรัน */
    void reloadPoints(JSONObject cfg) {
        if (cfg == null) return;
        tapMode = "coords".equals(optString(cfg, "tapMode", tapMode)) ? "coords" : "template";
        holdMs = clamp(optInt(cfg, "holdMs", holdMs), 50, 60000);
        synchronized (tapPoints) {
            tapPoints.clear();
            JSONArray arr = cfg.optJSONArray("tapPoints");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    int[] p = parsePoint(arr.opt(i));
                    if (p != null) tapPoints.add(p);
                }
            }
            tapCursor = 0;
        }
    }

    private static int[] parsePoint(Object o) {
        try {
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                if (a.length() >= 2) return new int[]{a.getInt(0), a.getInt(1)};
            } else if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                return new int[]{j.getInt("x"), j.getInt("y")};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** เพิ่ม 1 จุดพิกัด (เรียกจากปุ่มบนแผง browser หลังผู้ใช้แตะจอ) */
    void addTapPoint(int x, int y) {
        synchronized (tapPoints) {
            tapPoints.add(new int[]{x, y});
            tapMode = "coords";
        }
        emit("📍 เพิ่มพิกัดกด (" + x + ", " + y + ") — ใช้โหมดกดตามพิกัดแล้ว", "#27ae60", true);
    }

    /** รายการพิกัดเป็น JSON [[x,y], ...] */
    String tapPointsJson() {
        JSONArray arr = new JSONArray();
        synchronized (tapPoints) {
            for (int[] p : tapPoints) {
                JSONArray q = new JSONArray();
                q.put(p[0]);
                q.put(p[1]);
                arr.put(q);
            }
        }
        return arr.toString();
    }

    boolean hasTapPoints() {
        synchronized (tapPoints) {
            return !tapPoints.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // การเชื่อมต่อ
    // ------------------------------------------------------------------
    boolean connect(String host, int port) {
        return connect(host, port, 8000);
    }

    boolean connect(String host, int port, int timeoutMs) {
        try {
            AdbClient client = new AdbClient(host, port, timeoutMs);
            client.connect();
            if (adb != null && adb != client) adb.close();
            adb = client;
            this.host = host;
            this.port = port;
            String sk = "30";
            try {
                String out = client.shell("getprop ro.build.version.sdk");
                sk = out.replaceAll("[^0-9]", "");
                if (sk.length() > 4) sk = sk.substring(0, 4);
            } catch (Exception ignored) {
            }
            try {
                sdk = Integer.parseInt(sk);
            } catch (Exception e) {
                sdk = 30;
            }
            emit("🟢 เชื่อมต่อ ADB สำเร็จ: " + host + ":" + port + " (SDK " + sdk + ")", "#27ae60", true);
            return true;
        } catch (Exception e) {
            emit("❌ เชื่อมต่อ ADB ไม่ได้: " + shortErr(e), "#c0392b", true);
            return false;
        }
    }

    boolean isConnected() {
        if ("webview".equals(controlMode)) {
            return captureProvider != null && touchProvider != null;
        }
        return adb != null && adb.isConnected();
    }

    // ------------------------------------------------------------------
    // ตั้งค่า / เริ่ม / หยุด
    // ------------------------------------------------------------------
    void start(JSONObject config) {
        if (running.get()) return;
        stopWatchdog();   // เปิดบอทแล้วไม่ต้องให้ watchdog แย่งดึงภาพ
        cfg = config != null ? config : new JSONObject();
        String mode = optString(cfg, "controlMode", controlMode);
        setControlMode(mode);
        reloadPoints(cfg);
        stopAfterCharge = cfg.optBoolean("stopAfterCharge", true);
        stickyScale = 1.0;   // ต้นแบบชุดใหม่ → ให้สแกนหาสเกลตั้งแต่รอบแรก
        scanTicks = 0;

        if ("webview".equals(controlMode)) {
            if (captureProvider == null || touchProvider == null) {
                emit("❌ โหมด WebView ยังไม่พร้อม — เปิดแผง browser/game ในแอปก่อน", "#c0392b", true);
                return;
            }
            emit("🌐 ใช้โหมด WebView (กดในหน้าเว็บ — ไม่ต้อง ADB)", "#3498db", true);
            Frame test = capture();
            if (test == null) {
                String why = captureProvider.lastError();
                emit("⚠️ ยังจับภาพ WebView ไม่ได้"
                        + (why == null || why.isEmpty() ? "" : " (" + why + ")")
                        + " — เปิดแผง browser/game ให้มองเห็นก่อนครับ", "#e67e22", true);
            } else {
                emit("📷 WebView พร้อมสแกน " + test.w + "x" + test.h, "#27ae60", true);
            }
        } else {
            String h = optString(cfg, "host", host);
            int p = optInt(cfg, "port", port);
            if (!isConnected() || !h.equals(host) || p != port) {
                if (!connect(h, p)) return;
            }
        }
        stopFlag = false;
        running.set(true);
        frames = 0;
        captures = 0;
        presses = 0;
        emit("▶ เริ่มสแกนหาเป้าหมาย...", "#3498db", true);
        worker = new Thread(this::loop, "auto-hold-worker");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        if (!running.get()) return;
        stopFlag = true;
        drifterStop.set(true);
        running.set(false);
        emit("⏹ หยุดการทำงาน", "#7f8c8d", true);
        releaseTouch(pressX, pressY);
    }

    // ------------------------------------------------------------------
    // Watchdog: ตอนปิดอยู่ให้คอยดึงภาพตรวจหาต้นแบบ แล้วเปิดบอทเองเมื่อเจอ
    // ------------------------------------------------------------------
    void startWatchdog(JSONObject config) {
        watchdogCfg = config != null ? config : (cfg != null ? cfg : new JSONObject());
        watchdogEnabled = watchdogCfg.optBoolean("watchdogEnabled", true);
        watchdogSec = clamp(watchdogCfg.optInt("watchdogSec", 2), 1, 60);
        if (!watchdogEnabled || running.get() || watchdogOn.get()) return;
        watchdogOn.set(true);
        watchdogThread = new Thread(this::watchdogLoop, "bot-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    void stopWatchdog() {
        watchdogOn.set(false);
    }

    private void watchdogLoop() {
        long period = Math.max(1000L, watchdogSec * 1000L);
        while (watchdogOn.get() && !running.get()) {
            try {
                Thread.sleep(period);
            } catch (InterruptedException e) {
                break;
            }
            if (!watchdogOn.get() || running.get()) continue;
            JSONObject c = watchdogCfg != null ? watchdogCfg : cfg;
            if (c == null) continue;
            cfg = c;                                   // findTarget/textFound ใช้ค่าจาก cfg
            setControlMode(optString(c, "controlMode", controlMode));
            boolean txt = textMode();
            if (txt) {
                if (!textFound()) continue;
            } else {
                if ("webview".equals(controlMode) && captureProvider == null) continue;
                Frame f = capture();
                if (f == null || findTarget(f) == null) continue;
            }
            emit(txt ? "🔔 ข้อความปรากฏอีกครั้ง — เปิดบอทอัตโนมัติ"
                     : "🔔 เจอภาพต้นแบบขณะปิดอยู่ — เปิดบอทอัตโนมัติ", "#27ae60", true);
            start(c);
            break;                                     // start() หยุด watchdog อยู่แล้ว
        }
    }

    boolean isRunning() {
        return running.get();
    }

    String statusJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("running", running.get());
            j.put("connected", isConnected());
            j.put("controlMode", controlMode);
            j.put("target", "webview".equals(controlMode) ? "webview" : host + ":" + port);
            j.put("sdk", sdk);
            j.put("mode", holdMode);
            j.put("frames", frames);
            j.put("captures", captures);
            j.put("presses", presses);
            j.put("templates", templates.size());
            j.put("releaseTemplates", releaseTemplates.size());
            j.put("bestScore", lastBestScore);
            j.put("captureError", "webview".equals(controlMode) && captureProvider != null
                    ? captureProvider.lastError() : "");
            j.put("tapMode", tapMode);
            j.put("tapPoints", new JSONArray(tapPointsJson()));
            j.put("holdMs", holdMs);
            j.put("bestScale", lastBestScale);
            j.put("bestTpl", lastBestTplW + "x" + lastBestTplH);
            j.put("releaseThreshold", optDouble(cfg, "releaseThreshold", 0.92));
            j.put("stopAfterCharge", stopAfterCharge);
            j.put("autoStartOnSession", cfg.optBoolean("autoStartOnSession", true));
            j.put("watchdog", watchdogOn.get());
            j.put("watchdogSec", watchdogSec);
            j.put("textNeedle", optString(cfg, "textNeedle", ""));
            j.put("textSample", textSampleShort());
            j.put("last", lastText);
        } catch (Exception ignored) {
        }
        return j.toString();
    }

    // ------------------------------------------------------------------
    // จัดการภาพต้นแบบ
    // ------------------------------------------------------------------
    /** แปลง Bitmap เป็น RGBA byte[] (ใช้ทั้งตอนตัดจากจอและตอนนำเข้าไฟล์) */
    static byte[] bitmapToRgba(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            rgba[i * 4]     = (byte) ((c >> 16) & 0xFF);
            rgba[i * 4 + 1] = (byte) ((c >> 8) & 0xFF);
            rgba[i * 4 + 2] = (byte) (c & 0xFF);
            rgba[i * 4 + 3] = (byte) ((c >> 24) & 0xFF);
        }
        return rgba;
    }

    boolean addTemplate(String pngBase64, String boxJson, boolean release) {
        try {
            byte[] png = Base64.decode(pngBase64, Base64.DEFAULT);
            Bitmap bm = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bm == null) return false;
            int w = bm.getWidth(), h = bm.getHeight();
            if (w < 4 || h < 4 || (long) w * h > 8_000_000) return false;
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);

            int bx1 = 0, by1 = 0, bx2 = w, by2 = h;
            try {
                JSONArray box = new JSONArray(boxJson);
                if (box.length() >= 4) {
                    bx1 = clamp(box.getInt(0), 0, w - 1);
                    by1 = clamp(box.getInt(1), 0, h - 1);
                    bx2 = clamp(box.getInt(2), 1, w);
                    by2 = clamp(box.getInt(3), 1, h);
                    if (bx2 <= bx1) bx2 = Math.min(w, bx1 + 1);
                    if (by2 <= by1) by2 = Math.min(h, by1 + 1);
                }
            } catch (Exception ignored) {
            }

            /* ตัดให้เหลือเฉพาะบริเวณที่เลือก — สำคัญมากสำหรับความเร็ว NCC */
            int tw = bx2 - bx1, th = by2 - by1;
            if (tw < 4 || th < 4) {
                bm.recycle();
                return false;
            }
            byte[] rgba = new byte[tw * th * 4];
            for (int y = 0; y < th; y++) {
                int srow = (by1 + y) * w + bx1;
                int drow = y * tw * 4;
                for (int x = 0; x < tw; x++) {
                    int c = px[srow + x];
                    rgba[drow + x * 4]     = (byte) ((c >> 16) & 0xFF);     // R
                    rgba[drow + x * 4 + 1] = (byte) ((c >> 8) & 0xFF);      // G
                    rgba[drow + x * 4 + 2] = (byte) (c & 0xFF);             // B
                    rgba[drow + x * 4 + 3] = (byte) ((c >> 24) & 0xFF);     // A
                }
            }
            bm.recycle();
            String savedName = storage.save(rgba, tw, th, release, new int[]{0, 0, tw, th});
            if (savedName == null) return false;
            Tpl t = new Tpl(rgba, tw, th, new int[]{0, 0, tw, th}, savedName);
            stickyScale = 1.0;
            scanTicks = 0;
            if (release) releaseTemplates.add(t);
            else templates.add(t);
            emit("✂️ ตัดต้นแบบ" + (release ? "ปล่อย" : "กด") + " " + tw + "x" + th + " → " + savedName, "#27ae60", true);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "addTemplate: " + e.getMessage());
            return false;
        }
    }

    /** เพิ่มต้นแบบจากไฟล์ภาพที่เลือก (ไม่ต้องแคปจอใหม่) */
    boolean addRawTemplate(byte[] rgba, int w, int h, boolean release) {
        if (rgba == null || w < 4 || h < 4 || (long) w * h > 8_000_000
                || rgba.length < w * h * 4) return false;
        String savedName = storage.save(rgba, w, h, release);
        if (savedName == null) return false;
        Tpl t = new Tpl(rgba, w, h, new int[]{0, 0, w, h}, savedName);
        stickyScale = 1.0;
        scanTicks = 0;
        if (release) releaseTemplates.add(t);
        else templates.add(t);
        emit("📂 เพิ่มต้นแบบ" + (release ? "ปล่อย" : "กด") + "จากไฟล์ "
                + w + "x" + h + " → " + savedName, "#27ae60", true);
        return true;
    }

    void clearTemplates(boolean release) {
        if (release) releaseTemplates.clear();
        else templates.clear();
    }

    // ------------------------------------------------------------------
    // โฟลเดอร์เก็บต้นแบบ / ตั้งค่า / log
    // ------------------------------------------------------------------
    String folderPath() {
        return storage.path();
    }

    String templateFolderPath() {
        return storage.templatePath();
    }

    /** [{name,release,w,h,created}, ...] จาก *.json ในโฟลเดอร์ */
    String savedListJson() {
        return storage.listJson();
    }

    boolean loadSaved(String name) {
        try {
            BotStorage.SavedTemplate st = storage.load(name);
            if (st == null) return false;
            int[] b = (st.box != null && st.box.length >= 4) ? st.box : new int[]{0, 0, st.w, st.h};
            Tpl t = new Tpl(st.rgba, st.w, st.h, b, st.name);
            if (st.release) releaseTemplates.add(t);
            else templates.add(t);
            emit("📂 โหลดต้นแบบ '" + st.name + "' แล้ว", "#27ae60", true);
            return true;
        } catch (Exception e) {
            emit("❌ โหลดต้นแบบไม่ได้: " + shortErr(e), "#c0392b", true);
            return false;
        }
    }

    boolean deleteSaved(String name) {
        boolean removed = false;
        String safe = name == null ? "" : name;
        for (int i = templates.size() - 1; i >= 0; i--) {
            if (safe.equals(templates.get(i).name)) { templates.remove(i); removed = true; }
        }
        for (int i = releaseTemplates.size() - 1; i >= 0; i--) {
            if (safe.equals(releaseTemplates.get(i).name)) { releaseTemplates.remove(i); removed = true; }
        }
        boolean ok = storage.delete(safe) || removed;
        emit(ok ? "🗑 ลบต้นแบบ '" + safe + "' แล้ว" : "⚠️ ไม่พบต้นแบบ '" + safe + "'",
                ok ? "#e67e22" : "#c0392b", true);
        return ok;
    }

    int deleteAllSaved() {
        int n = storage.clearAll();
        templates.clear();
        releaseTemplates.clear();
        emit("🗑 ลบต้นแบบทั้งหมดในโฟลเดอร์ (" + n + " ไฟล์)", "#e67e22", true);
        return n;
    }

    String loadConfigJson() {
        return storage.loadConfig().toString();
    }

    boolean saveConfigJson(String json) {
        try {
            JSONObject cfgObj = new JSONObject(json);
            storage.saveConfig(cfgObj);
            reloadPoints(cfgObj);
            emit("💾 บันทึกตั้งค่าไปยังโฟลเดอร์บอทแล้ว", "#27ae60", true);
            return true;
        } catch (Exception e) {
            emit("❌ บันทึกตั้งค่าไม่ได้: " + shortErr(e), "#c0392b", true);
            return false;
        }
    }

    String logTail() {
        return storage.readLog(200);
    }

    void clearLog() {
        storage.clearLog();
    }

    /** คืน JSON {w,h,image:"data:image/png;base64,..."} สำหรับแสดงพรีวิว/ตัดต้นแบบ */
    String captureJson() {
        Frame f = capture();
        if (f == null) {
            String hint = "webview".equals(controlMode)
                    ? "เปิดแผง browser/game ให้มองเห็นก่อน"
                    : "ตรวจสอบ ADB/จอ MuMu";
            String why = ("webview".equals(controlMode) && captureProvider != null)
                    ? captureProvider.lastError() : "";
            String detail = (why == null || why.isEmpty()) ? hint : (why + " — " + hint);
            return "{\"ok\":false,\"error\":\"ดึงภาพไม่ได้ — " + detail + "\"}";
        }
        try {
            int[] argb = new int[f.w * f.h];
            for (int i = 0; i < argb.length; i++) {
                int r = f.rgba[i * 4] & 0xFF;
                int g = f.rgba[i * 4 + 1] & 0xFF;
                int b = f.rgba[i * 4 + 2] & 0xFF;
                argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            Bitmap bm = Bitmap.createBitmap(argb, f.w, f.h, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, bos);
            bm.recycle();
            JSONObject j = new JSONObject();
            j.put("ok", true);
            j.put("w", f.w);
            j.put("h", f.h);
            j.put("image", "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
            return j.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + shortErr(e) + "\"}";
        }
    }

    // ------------------------------------------------------------------
    // ตรวจจับ
    // ------------------------------------------------------------------
    /** กรอบ ROI จาก config (null = ทั้งจอ) — ใช้ร่วมกันทั้งฝั่งกดและฝั่งปล่อย */
    private int[] roiRect(Frame frame) {
        try {
            JSONArray roi = cfg.optJSONArray("roi");
            if (roi != null && roi.length() >= 4) {
                int x1 = clamp(roi.getInt(0), 0, frame.w - 1);
                int y1 = clamp(roi.getInt(1), 0, frame.h - 1);
                int x2 = clamp(roi.getInt(2), x1 + 1, frame.w);
                int y2 = clamp(roi.getInt(3), y1 + 1, frame.h);
                if (x2 - x1 > 4 && y2 - y1 > 4) return new int[]{x1, y1, x2, y2};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean textMode() {
        return "text".equals(optString(cfg, "mode", "template"));
    }

    /** อ่านข้อความในหน้าเว็บ แล้วเทียบกับคำที่ตั้งไว้ */
    private boolean textFound() {
        if (textProvider == null) {
            long now = System.currentTimeMillis();
            if (now - lastTextWarn > 5000) {
                emit("⚠️ โหมดข้อความยังไม่พร้อม — เปิดแผง browser/game ในแอปก่อน", "#c0392b", true);
                lastTextWarn = now;
            }
            return false;
        }
        String needle = optString(cfg, "textNeedle", "");
        if (needle.trim().isEmpty()) return false;
        String text = textProvider.visibleText();
        if (text == null) return false;
        lastTextSample = text;
        boolean cs = cfg.optBoolean("textCaseSensitive", false);
        String match = optString(cfg, "textMatch", "contains");
        try {
            if ("regex".equals(match)) {
                int flags = cs ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                return Pattern.compile(needle, flags).matcher(text).find();
            }
            String hay = cs ? text : text.toLowerCase(Locale.US);
            String key = cs ? needle : needle.toLowerCase(Locale.US);
            if ("exact".equals(match)) return hay.trim().equals(key.trim());
            return hay.contains(key);
        } catch (Exception e) {
            return false;
        }
    }

    private String textSampleShort() {
        String s = lastTextSample == null ? "" : lastTextSample.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** เจอข้อความแล้ว → ให้ลูปหลักกดที่พิกัดที่ผู้ใช้จับไว้ (rect เป็นแค่สัญญาณว่าเจอ) */
    private int[] findTextTarget() {
        if (!textFound()) return null;
        if (!hasTapPoints()) {
            long now = System.currentTimeMillis();
            if (now - lastTextWarn > 5000) {
                emit("⚠️ โหมดข้อความต้องมีพิกัดกด — กด 📍 จับพิกัด(WebV) บนแผง browser ก่อน",
                        "#e67e22", true);
                lastTextWarn = now;
            }
            return null;
        }
        return new int[]{0, 0, 0, 0};
    }

    private int[] findTarget(Frame frame) {
        if (frame == null) return null;
        int ox = 0, oy = 0;
        Frame img = frame;
        int[] r = roiRect(frame);
        if (r != null) {
            img = crop(frame, r[0], r[1], r[2] - r[0], r[3] - r[1]);
            ox = r[0];
            oy = r[1];
        }

        String mode = optString(cfg, "mode", "template");
        if ("color".equals(mode)) {
            int rgb = parseHex(optString(cfg, "colorHex", "#ffffff"));
            int tol = clamp(optInt(cfg, "colorTol", 25), 0, 80);
            int minArea = clamp(optInt(cfg, "colorMinArea", 30), 1, 100000);
            int[] pt = new int[2];
            int area = NativeBridge.botFindColor(img.rgba, img.w, img.h, rgb, tol, minArea, pt);
            if (area <= 0) return null;
            int x = pt[0] + ox, y = pt[1] + oy;
            return new int[]{x, y, x, y};
        }

        if (templates.isEmpty()) {
            lastBestScore = 0.0;
            return null;
        }
        double thr = optDouble(cfg, "tmThreshold", 0.8);
        int step = clamp(optInt(cfg, "scanStep", 2), 1, 4);
        /* ลองหลายสเกล — ต้นแบบที่แคปจากจอ/MuMu คนละขนาดกับภาพสดจะจับไม่ได้เลย
         * (NCC ที่สเกลผิดมักได้ ~0.1–0.2 ทั้งที่ภาพดูตรงกัน) */
        scanTicks++;
        /* สแกนทุกสเกลเฉพาะรอบแรก + ทุก 10 รอบ (และตอน multi-scale ปิดให้สแกนแค่ 1.0)
         * รอบอื่นใช้สเกลที่เคยสำเร็จ → ไม่ให้บอทช้าจากการสแกนหลายสเกลทุกเฟรม */
        boolean multi = cfg.optBoolean("multiScale", true);
        double[] scales;
        if (!multi) {
            scales = new double[]{1.0};
        } else if (scanTicks <= 3 || (scanTicks % 10) == 0) {
            scales = new double[]{1.0, 0.9, 1.1, 0.8, 1.25, 0.7, 1.4, 0.6, 1.6, 0.5, 2.0};
        } else if (Math.abs(stickyScale - 1.0) > 0.01) {
            scales = new double[]{stickyScale, 1.0};
        } else {
            scales = new double[]{1.0};
        }
        double best = 0.0;
        int[] bestRect = null;
        double bestScale = 1.0;
        int bestW = 0, bestH = 0;
        for (double s : scales) {
            for (Tpl t : templates) {
                Tpl cur = scaleTpl(t, s);
                if (cur.w > img.w || cur.h > img.h || cur.w < 8 || cur.h < 8) continue;
                int[] out = new int[4];
                double score = NativeBridge.botMatchTemplate(img.rgba, img.w, img.h,
                        cur.rgba, cur.w, cur.h, step, out);
                if (score > best) {
                    best = score;
                    bestScale = s;
                    bestW = cur.w;
                    bestH = cur.h;
                    int bx = out[0] + ox, by = out[1] + oy;
                    int[] b = cur.box;
                    if (b != null && b.length >= 4 && (b[2] > b[0] || b[3] > b[1])) {
                        bestRect = new int[]{bx + b[0], by + b[1], bx + b[2], by + b[3]};
                    } else {
                        int cx = bx + cur.w / 2, cy = by + cur.h / 2;
                        bestRect = new int[]{cx, cy, cx, cy};
                    }
                }
            }
            if (best >= thr) {
                stickyScale = bestScale;   // จำสเกลที่ใช้ได้ → รอบหน้าลองตัวนี้ก่อน
                break;                     // เจอแล้ว ไม่ต้องเสียเวลาลองสเกลที่เหลือ
            }
        }
        lastBestScore = best;
        lastBestScale = bestScale;
        lastBestTplW = bestW;
        lastBestTplH = bestH;
        return best >= thr ? bestRect : null;
    }

    /** ย่อ/ขยายต้นแบบให้เข้ากับความละเอียดภาพที่กำลังสแกน */
    private static Tpl scaleTpl(Tpl t, double s) {
        if (Math.abs(s - 1.0) < 0.01) return t;
        int nw = Math.max(4, (int) Math.round(t.w * s));
        int nh = Math.max(4, (int) Math.round(t.h * s));
        if (nw == t.w && nh == t.h) return t;
        int[] argb = new int[t.w * t.h];
        for (int i = 0; i < argb.length; i++) {
            int rr = t.rgba[i * 4] & 0xFF;
            int gg = t.rgba[i * 4 + 1] & 0xFF;
            int bb = t.rgba[i * 4 + 2] & 0xFF;
            argb[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }
        Bitmap bm = Bitmap.createBitmap(argb, t.w, t.h, Bitmap.Config.ARGB_8888);
        Bitmap sc = Bitmap.createScaledBitmap(bm, nw, nh, true);
        byte[] rgba = bitmapToRgba(sc);
        bm.recycle();
        if (sc != bm) sc.recycle();
        int[] b = (t.box != null && t.box.length >= 4)
                ? new int[]{(int) Math.round(t.box[0] * s), (int) Math.round(t.box[1] * s),
                            (int) Math.round(t.box[2] * s), (int) Math.round(t.box[3] * s)}
                : new int[]{0, 0, nw, nh};
        return new Tpl(rgba, nw, nh, b, t.name);
    }

    /** คะแนนความเหมือนสูงสุดของต้นแบบปล่อย (0 = ไม่เจอ) — สแกนใน ROI เดียวกับฝั่งกด */
    private double releaseScore(Frame frame) {
        if (releaseTemplates.isEmpty() || frame == null) return 0.0;
        Frame img = frame;
        int[] r = roiRect(frame);
        if (r != null) img = crop(frame, r[0], r[1], r[2] - r[0], r[3] - r[1]);
        int step = clamp(optInt(cfg, "scanStep", 2), 1, 4);
        double thr = optDouble(cfg, "releaseThreshold", 0.92);
        /* ต้นแบบปล่อยมาจากจอเดียวกับต้นแบบกด → ใช้สเกลที่เคยสำเร็จก่อน แล้วค่อย 1.0/0.8/1.25 */
        double[] scales = !cfg.optBoolean("multiScale", true)
                ? new double[]{1.0}
                : (Math.abs(stickyScale - 1.0) > 0.01
                    ? new double[]{stickyScale, 1.0, 0.9, 1.1, 0.8, 1.25}
                    : new double[]{1.0, 0.9, 1.1, 0.8, 1.25});
        double best = 0.0;
        for (double s : scales) {
            for (Tpl t : releaseTemplates) {
                Tpl cur = scaleTpl(t, s);
                if (cur.w > img.w || cur.h > img.h || cur.w < 8 || cur.h < 8) continue;
                int[] out = new int[4];
                double score = NativeBridge.botMatchTemplate(img.rgba, img.w, img.h,
                        cur.rgba, cur.w, cur.h, step, out);
                if (score > best) best = score;
            }
            if (best >= thr) break;
        }
        return best;
    }

    // ------------------------------------------------------------------
    // ลูปหลัก (เหมือน BotEngine._run ใน bot.py)
    // ------------------------------------------------------------------
    private void loop() {
        int holdScanMin = clamp(optInt(cfg, "holdScanMin", 80), 30, 800);
        int holdScanMax = Math.max(holdScanMin, clamp(optInt(cfg, "holdScanMax", 200), 50, 1000));
        int reactMin = clamp(optInt(cfg, "reactMin", 120), 0, 3000);
        int reactMax = Math.max(reactMin, clamp(optInt(cfg, "reactMax", 350), 0, 3000));
        double slowChance = optDouble(cfg, "slowChance", 0.15);
        int confirmN = clamp(optInt(cfg, "confirmN", 2), 1, 8);
        double maxHold = optDouble(cfg, "maxHold", 60.0);
        int interval = clamp(optInt(cfg, "interval", 300), 50, 3000);
        int fails = 0;

        try {
            while (running.get() && !stopFlag) {
                boolean txt = textMode();
                Frame img = txt ? null : capture();
                if (!txt && img == null) {
                    fails++;
                    boolean web = "webview".equals(controlMode);
                    String why = (web && captureProvider != null) ? captureProvider.lastError() : "";
                    String hint = web
                            ? "เปิดแผง browser/game ให้มองเห็นอยู่ไหม?" : "จอ MuMu/ADB ยังเปิดอยู่ไหม?";
                    String detail = (why == null || why.isEmpty()) ? hint : (why + " — " + hint);
                    if (fails == 1) emit("⚠️ ดึงภาพไม่ได้ — " + detail, "#e67e22", true);
                    else if (fails == 3) reconnect();
                    /* ไม่สแปม log ทุกวินาที — เตือนซ้ำทุก ~30 วิ พร้อมเหตุผลล่าสุด */
                    else if (fails % 30 == 0) {
                        emit("⚠️ ยังดึงภาพไม่ได้ (" + fails + " ครั้ง) — " + detail, "#c0392b", true);
                    }
                    sleep(fails < 3 ? 400 : 1000);
                    continue;
                }
                fails = 0;
                int[] rect = txt ? findTextTarget() : findTarget(img);
                if (rect == null) {
                    long now = System.currentTimeMillis();
                    if (txt) {
                        if (now - lastDiagEmit > 2000) {
                            emit("🔤 ยังไม่เจอ '" + optString(cfg, "textNeedle", "")
                                    + "' — ที่อ่านได้: " + (textSampleShort().isEmpty() ? "(ว่าง)" : textSampleShort()),
                                    "#3498db", true);
                            lastDiagEmit = now;
                        }
                    } else if (!"color".equals(optString(cfg, "mode", "template")) && now - lastDiagEmit > 2000) {
                        double thr = optDouble(cfg, "tmThreshold", 0.8);
                        emit("🔍 สแกน... คะแนน " + String.format(java.util.Locale.US, "%.2f", lastBestScore)
                                + " / ต้องการ " + String.format(java.util.Locale.US, "%.2f", thr)
                                + " (ต้นแบบ " + lastBestTplW + "x" + lastBestTplH
                                + " @สเกล " + String.format(java.util.Locale.US, "%.2f", lastBestScale)
                                + " / ภาพ " + img.w + "x" + img.h
                                + " · ต้นแบบ " + templates.size() + ")"
                                 + (lastBestScore < 0.4
                                    ? " — ต่ำมาก: ต้นแบบคนละจอ/คนละสเกล? แนะนำแคปต้นแบบใหม่ในหน้าเกมนี้"
                                    : ""), "#3498db", true);
                        lastDiagEmit = now;
                    } else {
                        emit("🔍 กำลังสแกน...", "#3498db", false);
                    }
                    sleep(interval);
                    continue;
                }

                int x, y;
                int[] pressRect = rect;
                /* โหมดข้อความ: กดตามพิกัดที่จับไว้เสมอ (ข้อความเป็นแค่ตัวบอกว่าถึงเวลากด) */
                boolean useCoords = (txt || "coords".equals(tapMode)) && hasTapPoints();
                if (useCoords) {
                    int[] p;
                    synchronized (tapPoints) {
                        p = tapPoints.get(tapCursor % tapPoints.size());
                        tapCursor++;
                    }
                    x = p[0];
                    y = p[1];
                    pressRect = new int[]{x, y, x, y};  // กัน jitter ดึงนิ้วออกจากพิกัดที่กำหนด
                } else {
                    x = pickX(rect);
                    y = pickY(rect);
                }
                if (!pressTouch(x, y, pressRect)) {
                    String hint = "webview".equals(controlMode)
                            ? "ตรวจสอบ WebView/การส่งทัชในแอป" : "ตรวจสอบ ADB/SDK";
                    emit("❌ กดนิ้วไม่สำเร็จ — " + hint, "#c0392b", true);
                    sleep(800);
                    continue;
                }
                emit("🎯 กดที่ (" + x + ", " + y + ") "
                        + (useCoords ? "[พิกัดที่กำหนด]" : "[กลางต้นแบบ]")
                        + " โหมด: " + holdMode + " — กำลังชาร์จ...", "#2ecc71", false);

                String reason = "stop";
                long t0 = System.currentTimeMillis();
                int miss = 0, confirm = 0;
                frames++;

                while (running.get() && !stopFlag) {
                    sleep(holdScanMin + rnd.nextInt(Math.max(1, holdScanMax - holdScanMin + 1)));
                    double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
                    if (maxHold > 0 && elapsed > maxHold) {
                        reason = "timeout";
                        break;
                    }
                    if (txt) {
                        /* โหมดข้อความ: ค้างนิ้วไปจนกว่าข้อความจะหายจากหน้าเว็บ */
                        if (!textFound()) {
                            emit("🔤 ข้อความหายไป — ปล่อยนิ้วและปิดบอท", "#e67e22", true);
                            reason = "charged";
                            break;
                        }
                        emit("🔋 ค้างนิ้ว " + String.format(java.util.Locale.US, "%.1f", elapsed)
                                + " วิ — รอข้อความ '" + optString(cfg, "textNeedle", "") + "' หายไป",
                                "#2ecc71", false);
                        continue;
                    }
                    if (useCoords && releaseTemplates.isEmpty()) {
                        /* โหมดพิกัด: จุดกดไม่ได้อยู่บนต้นแบบ → ใช้เวลากดค้างแทนเช็คภาพหาย */
                        if ((System.currentTimeMillis() - t0) >= holdMs) {
                            reason = "timeout";
                            break;
                        }
                        emit("🔋 ค้างนิ้ว " + String.format(java.util.Locale.US, "%.1f", elapsed)
                                + " / " + (holdMs / 1000.0) + " วิ", "#2ecc71", false);
                        continue;
                    }
                    Frame check = capture();
                    if (check == null) {
                        miss++;
                        if (miss >= 4) {
                            reason = "lost";
                            break;
                        }
                        continue;
                    }
                    miss = 0;
                    if (!releaseTemplates.isEmpty()) {
                        double score = releaseScore(check);
                        double thr = optDouble(cfg, "releaseThreshold", 0.92);
                        if (score >= thr) confirm++;
                        else confirm = 0;
                        /* ภาพชาร์จเต็มชัดมาก = ยืนยันทันที (แต่ต้องไม่ต่ำกว่าที่ผู้ใช้กำหนด
                         * เช่น ตั้ง 0.99 → 0.97 ต้องไม่ปล่อยก่อนถึงค่า) */
                        if (score >= Math.max(0.97, thr)) confirm = confirmN;
                        emit("🔋 ชาร์จ " + String.format(java.util.Locale.US, "%.1f", elapsed)
                                + " วิ (เหมือน " + String.format(java.util.Locale.US, "%.2f", score)
                                + "/" + String.format(java.util.Locale.US, "%.2f", thr)
                                + " · ยืนยัน " + confirm + "/" + confirmN + ")", "#2ecc71", false);
                    } else {
                        boolean gone = findTarget(check) == null;
                        if (gone) confirm++;
                        else confirm = 0;
                    }
                    if (confirm >= confirmN) {
                        reason = "charged";
                        break;
                    }
                }

                if ("charged".equals(reason)) {
                    int rd = reactMin + rnd.nextInt(Math.max(1, reactMax - reactMin + 1));
                    if (rnd.nextDouble() < slowChance) rd = (int) (rd * (1.5 + rnd.nextDouble() * 0.7));
                    emit((txt ? "🔤 ข้อความหาย — รอ reaction " : "⚡ ชาร์จเต็ม! รอ reaction ")
                            + rd + " ms ก่อนปล่อย...", "#e67e22", false);
                    sleep(rd);
                } else if ("timeout".equals(reason)) {
                    if (useCoords && releaseTemplates.isEmpty()) {
                        emit("⏱ ค้างนิ้วครบ " + (holdMs / 1000.0) + " วิ — ปล่อยนิ้ว", "#e67e22", false);
                    } else {
                        emit("⌛ กดครบ " + (int) maxHold + " วิ ยังไม่เจอภาพชาร์จเต็ม — ปล่อยเพื่อความปลอดภัย", "#e67e22", false);
                    }
                } else if ("lost".equals(reason)) {
                    emit("⚠️ ภาพหลุดระหว่างกดค้าง — ปล่อยนิ้วเพื่อความปลอดภัย", "#c0392b", true);
                }

                releaseTouch(x, y);
                presses++;
                /* ชาร์จเต็ม (หรือกดตามพิกัดครบตาม ms ที่กำหนด) → ปิดบอททันที
                 * เวลาเปิดเซสชันใหม่ MainActivity จะเริ่มบอทให้เองอีกครั้ง */
                /* โหมดข้อความ: ทุกครั้งที่ปล่อยนิ้ว (ข้อความหาย) ปิดบอททันทีเสมอ */
                boolean finished = txt || "charged".equals(reason)
                        || (useCoords && releaseTemplates.isEmpty() && "timeout".equals(reason));
                if (finished && (txt || stopAfterCharge)) {
                    emit((txt ? "✅ ข้อความหาย — ปิดบอทอัตโนมัติ"
                              : "✅ ชาร์จเต็ม — ปิดบอทอัตโนมัติ")
                            + " (เปิดเซสชันใหม่จะเริ่มให้เอง)", "#27ae60", true);
                    stopFlag = true;
                    running.set(false);
                    startWatchdog(cfg);   // รอจนข้อความ/ต้นแบบกลับมา → เปิดบอทเองอีกครั้ง
                    break;
                }
                if (!running.get() || stopFlag) break;
                if ("lost".equals(reason)) {
                    reconnect();
                    sleep(500);
                    continue;
                }
                if (!releaseTemplates.isEmpty()) rearm(interval);
                emit("✋ ปล่อยนิ้วเรียบร้อย! สแกนหาต่อ...", "#9b59b6", false);
                sleep(500);
            }
        } catch (Exception e) {
            emit("⚠️ ข้อผิดพลาด: " + shortErr(e), "#c0392b", true);
        } finally {
            releaseTouch(pressX, pressY);
            running.set(false);
            holdMode = "idle";
            emit("⏹ หยุดการทำงาน (เรียบร้อย)", "#7f8c8d", true);
        }
    }

    private void rearm(int interval) {
        long t1 = System.currentTimeMillis();
        while (running.get() && !stopFlag && System.currentTimeMillis() - t1 < 10000) {
            Frame im = capture();
            if (im == null || findTarget(im) == null) break;
            sleep(interval);
        }
    }

    private void reconnect() {
        releaseTouch(pressX, pressY);
        if ("webview".equals(controlMode)) {
            emit("↻ ตรวจสอบ WebView ใหม่... (เปิดแผง browser/game ไว้นะครับ)", "#e67e22", true);
            return;
        }
        emit("🔌 กำลังเชื่อมต่อ ADB ใหม่...", "#e67e22", true);
        try {
            if (adb != null) adb.close();
            connect(host, port);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // ทัช / ADB
    // ------------------------------------------------------------------
    /* MuMu 12 (SDK 32): `input motionevent` เป็น native เร็ว ~20ms — ใช้ทางเดียว
     * (ตัดโหมด swipe สำหรับ Android เก่า < 10 ที่ MuMu ไม่ต้องการออก) */
    private boolean pressTouch(int x, int y, int[] rect) {
        try {
            synchronized (holdLock) {
                pressX = x;
                pressY = y;
                targetRect = rect;
                if ("webview".equals(controlMode)) {
                    if (touchProvider == null) throw new IOException("WebView ไม่พร้อม");
                    touchProvider.down(x, y);
                    holdMode = "webview";
                } else {
                    if (adb == null || !adb.isConnected()) throw new IOException("ADB หลุด");
                    adb.shell("input motionevent DOWN " + x + " " + y);
                    holdMode = "motion";
                }
                holding = true;
                startDrifter(x, y);
            }
            return true;
        } catch (Exception e) {
            emit("⚠️ กดไม่ได้: " + shortErr(e), "#c0392b", true);
            return false;
        }
    }

    private void startDrifter(final int x, final int y) {
        drifterStop.set(false);
        final double h = optDouble(cfg, "humanize", 0.7);
        if (h <= 0) return;
        final int r = (int) Math.round(4 * h);
        drifter = new Thread(() -> {
            while (!drifterStop.get() && holding && running.get()) {
                long wait = 70 + rnd.nextInt(230);
                if (rnd.nextDouble() < 0.18) wait *= 2 + rnd.nextInt(2);
                sleep(wait);
                if (drifterStop.get() || !holding || !running.get()) break;
                int dx = r > 0 ? rnd.nextInt(2 * r + 1) - r : 0;
                int dy = r > 0 ? rnd.nextInt(2 * r + 1) - r : 0;
                int nx = x + dx;
                int ny = y + dy;
                if (targetRect != null && targetRect.length >= 4) {
                    nx = clamp(nx, targetRect[0], targetRect[2]);
                    ny = clamp(ny, targetRect[1], targetRect[3]);
                }
                try {
                    synchronized (holdLock) {
                        /* เช็ค holding ในล็อกอีกครั้ง — กัน MOVE แทรกหลัง UP ไปแล้ว */
                        if (!holding) continue;
                        if ("webview".equals(controlMode)) {
                            if (touchProvider != null) touchProvider.move(nx, ny);
                        } else if (adb != null && adb.isConnected()) {
                            adb.shell("input motionevent MOVE " + nx + " " + ny);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "auto-hold-drift");
        drifter.setDaemon(true);
        drifter.start();
    }

    /** ปล่อยนิ้วทันที — ปลุกเธรด jitter ด้วย interrupt แล้วส่ง UP เลย
     * (เดิม join(1200) รอเธรดหลับให้จบก่อน ทำให้ชาร์จเต็มแล้วยังกดต่ออีก ~1 วิ) */
    private void releaseTouch(int x, int y) {
        drifterStop.set(true);
        holding = false;
        Thread d = drifter;
        drifter = null;
        if (d != null) d.interrupt();
        synchronized (holdLock) {
            try {
                if ("webview".equals(holdMode)) {
                    if (touchProvider != null) touchProvider.up(x, y);
                } else if ("motion".equals(holdMode) && adb != null && adb.isConnected()) {
                    adb.shell("input motionevent UP " + x + " " + y);
                }
            } catch (Exception ignored) {
            }
            holdMode = "idle";
        }
    }

    private Frame capture() {
        if ("webview".equals(controlMode)) {
            if (captureProvider == null) return null;
            Frame f = captureProvider.capture();
            if (f != null) captures++;
            return f;
        }
        if (adb == null || !adb.isConnected()) return null;
        try {
            byte[] raw = adb.exec("screencap", 40 * 1024 * 1024);
            if (raw == null || raw.length < 12) return null;
            int w = le32(raw, 0);
            int h = le32(raw, 4);
            int fmt = le32(raw, 8);
            if (w <= 0 || h <= 0 || (long) w * h > 40_000_000L) return null;

            /* MuMu ออก RGBA_8888/RGBX/BGRA (4 ไบต์/px) เสมอ — ไม่รองรับ 565/888 ที่ MuMu ไม่ใช้
             * Android ใหม่เพิ่ม colorSpace 4 ไบต์ต่อท้าย header (รวม 16 ไบต์) */
            if (fmt != 1 && fmt != 2 && fmt != 5) return null;
            int need = w * h * 4;
            int off = (raw.length >= 16 + need) ? 16 : 12;
            if (raw.length < off + need) return null;
            byte[] rgba = new byte[need];
            final int n = w * h;
            boolean bgra = (fmt == 5);
            for (int i = 0; i < n; i++) {
                int s = off + i * 4, d = i * 4;
                rgba[d]     = raw[s + (bgra ? 2 : 0)];
                rgba[d + 1] = raw[s + 1];
                rgba[d + 2] = raw[s + (bgra ? 0 : 2)];
                rgba[d + 3] = (byte) 0xFF;
            }
            captures++;
            return new Frame(w, h, rgba);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------
    private void emit(String text, String color, boolean force) {
        lastText = text;
        long now = System.currentTimeMillis();
        if (!force && text.equals(lastEmitText) && now - lastEmit < 1200) return;
        lastEmit = now;
        lastEmitText = text;
        storage.appendLog(text);
        if (cb != null) {
            try {
                cb.onStatus(text, color, force);
            } catch (Exception ignored) {
            }
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int pickX(int[] r) {
        return pickPoint(r, true);
    }

    private int pickY(int[] r) {
        return pickPoint(r, false);
    }

    private int pickPoint(int[] r, boolean xAxis) {
        double h = optDouble(cfg, "humanize", 0.7);
        double cx = (r[0] + r[2]) / 2.0, cy = (r[1] + r[3]) / 2.0;
        int lo = xAxis ? r[0] : r[1], hi = xAxis ? r[2] : r[3];
        double center = xAxis ? cx : cy;
        double span = Math.max(1.0, hi - lo);
        if (h <= 0.05 || span <= 3.0) return (int) Math.round(center);

        // ดึงจุดกดให้อยู่ในโซนปลอดภัย ไม่ให้โดนขอบปุ่มหรือหลุดนอกกรอบ
        int margin = (int) Math.floor(span * 0.20);
        int safeLo = lo + margin;
        int safeHi = hi - margin;
        if (safeHi <= safeLo) {
            safeLo = lo + (int) Math.floor(span * 0.10);
            safeHi = hi - (int) Math.floor(span * 0.10);
            if (safeHi < safeLo) { safeLo = (int) Math.round(center); safeHi = safeLo; }
        }

        double sigma = Math.max(1.0, span * 0.08 * h);
        for (int i = 0; i < 10; i++) {
            int v = (int) Math.round(center + rnd.nextGaussian() * sigma);
            if (v >= safeLo && v <= safeHi) return v;
        }
        return (int) Math.round(center);
    }

    private static Frame crop(Frame f, int x, int y, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int row = 0; row < h; row++) {
            int s = ((y + row) * f.w + x) * 4;
            System.arraycopy(f.rgba, s, out, row * w * 4, w * 4);
        }
        return new Frame(w, h, out);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) |
               ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int parseHex(String s) {
        if (s == null) return 0xFFFFFF;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) return 0xFFFFFF;
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (Exception e) {
            return 0xFFFFFF;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String optString(JSONObject j, String k, String def) {
        try {
            String v = j.optString(k, def);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static int optInt(JSONObject j, String k, int def) {
        try {
            return j.optInt(k, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static double optDouble(JSONObject j, String k, double def) {
        try {
            return j.optDouble(k, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    static final class Frame {
        final int w, h;
        final byte[] rgba;

        Frame(int w, int h, byte[] rgba) {
            this.w = w;
            this.h = h;
            this.rgba = rgba;
        }
    }

    private static final class Tpl {
        final byte[] rgba;
        final int w, h;
        final int[] box;
        final String name;   // ชื่อไฟล์ในโฟลเดอร์ (null = ต้นแบบเฉพาะ session)

        Tpl(byte[] rgba, int w, int h, int[] box, String name) {
            this.rgba = rgba;
            this.w = w;
            this.h = h;
            this.box = box;
            this.name = name;
        }
    }
}
