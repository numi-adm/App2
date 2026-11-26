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
    private String controlMode = "webview";   // "webview" | "adb"
    private volatile boolean holding = false;   // นิ้วกำลังกดค้างอยู่ (กัน MOVE แทรกหลัง UP)
    private JSONObject cfg = new JSONObject();
    private String host = "127.0.0.1";
    private int port = 16384;
    private int sdk = 30;
    private String holdMode = "idle";
    private int pressX = 0, pressY = 0;
    private double lastBestScore = 0.0;   // คะแนน-template สูงสุดล่าสุด (ไว้ดีบัก)
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
                Tpl t = new Tpl(st.rgba, st.w, st.h, new int[]{0, 0, st.w, st.h}, st.name);
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

    /** เขียนข้อความลง log ของบอท (ให้ฝั่งแอปรายงานเหตุ เช่น แคปภาพไม่สำเร็จ) */
    void note(String text, String color) {
        emit(text, color, true);
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
        cfg = config != null ? config : new JSONObject();
        String mode = optString(cfg, "controlMode", controlMode);
        setControlMode(mode);

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
            return addRawTemplate(rgba, tw, th, release);
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
            Tpl t = new Tpl(st.rgba, st.w, st.h, new int[]{0, 0, st.w, st.h}, st.name);
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
        double best = 0.0;
        int[] bestRect = null;
        for (Tpl t : templates) {
            if (t.w > img.w || t.h > img.h) continue;
            int[] out = new int[4];
            double score = NativeBridge.botMatchTemplate(img.rgba, img.w, img.h,
                    t.rgba, t.w, t.h, step, out);
            if (score > best) {
                best = score;
                if (score >= thr) {
                    int bx = out[0] + ox, by = out[1] + oy;
                    int[] b = t.box;
                    if (b[2] > b[0] && b[3] > b[1]) {
                        bestRect = new int[]{bx + b[0], by + b[1], bx + b[2], by + b[3]};
                    } else {
                        int cx = bx + t.w / 2, cy = by + t.h / 2;
                        bestRect = new int[]{cx, cy, cx, cy};
                    }
                }
            }
        }
        lastBestScore = best;
        return bestRect;
    }

    /** คะแนนความเหมือนสูงสุดของต้นแบบปล่อย (0 = ไม่เจอ) — สแกนใน ROI เดียวกับฝั่งกด */
    private double releaseScore(Frame frame) {
        if (releaseTemplates.isEmpty() || frame == null) return 0.0;
        Frame img = frame;
        int[] r = roiRect(frame);
        if (r != null) img = crop(frame, r[0], r[1], r[2] - r[0], r[3] - r[1]);
        int step = clamp(optInt(cfg, "scanStep", 2), 1, 4);
        double best = 0.0;
        for (Tpl t : releaseTemplates) {
            if (t.w > img.w || t.h > img.h) continue;
            int[] out = new int[4];
            double score = NativeBridge.botMatchTemplate(img.rgba, img.w, img.h,
                    t.rgba, t.w, t.h, step, out);
            if (score > best) best = score;
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
                Frame img = capture();
                if (img == null) {
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
                int[] rect = findTarget(img);
                if (rect == null) {
                    long now = System.currentTimeMillis();
                    if (!"color".equals(optString(cfg, "mode", "template")) && now - lastDiagEmit > 2000) {
                        double thr = optDouble(cfg, "tmThreshold", 0.8);
                        emit("🔍 สแกน... คะแนนสูงสุด " + String.format(java.util.Locale.US, "%.2f", lastBestScore)
                                + " / ต้องการ " + String.format(java.util.Locale.US, "%.2f", thr)
                                + " (ต้นแบบ " + templates.size() + ")", "#3498db", true);
                        lastDiagEmit = now;
                    } else {
                        emit("🔍 กำลังสแกน...", "#3498db", false);
                    }
                    sleep(interval);
                    continue;
                }

                int x = pickX(rect), y = pickY(rect);
                if (!pressTouch(x, y)) {
                    String hint = "webview".equals(controlMode)
                            ? "ตรวจสอบ WebView/การส่งทัชในแอป" : "ตรวจสอบ ADB/SDK";
                    emit("❌ กดนิ้วไม่สำเร็จ — " + hint, "#c0392b", true);
                    sleep(800);
                    continue;
                }
                emit("🎯 กดที่ (" + x + ", " + y + ") โหมด: " + holdMode + " — กำลังชาร์จ...", "#2ecc71", false);

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
                        /* ฉลาดขึ้น: ภาพชาร์จเต็มชัดมาก (≥0.97) = ยืนยันทันที
                         * ไม่ต้องรอเฟรม 2 ให้เสียเวลาชาร์จเกิน */
                        if (score >= 0.97) confirm = confirmN;
                        emit("🔋 ชาร์จ " + String.format(java.util.Locale.US, "%.1f", elapsed)
                                + " วิ (ยืนยัน " + confirm + "/" + confirmN + ")", "#2ecc71", false);
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
                    emit("⚡ ชาร์จเต็ม! รอ reaction " + rd + " ms ก่อนปล่อย...", "#e67e22", false);
                    sleep(rd);
                } else if ("timeout".equals(reason)) {
                    emit("⌛ กดครบ " + (int) maxHold + " วิ ยังไม่เจอภาพชาร์จเต็ม — ปล่อยเพื่อความปลอดภัย", "#e67e22", false);
                } else if ("lost".equals(reason)) {
                    emit("⚠️ ภาพหลุดระหว่างกดค้าง — ปล่อยนิ้วเพื่อความปลอดภัย", "#c0392b", true);
                }

                releaseTouch(x, y);
                presses++;
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
    private boolean pressTouch(int x, int y) {
        try {
            synchronized (holdLock) {
                pressX = x;
                pressY = y;
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
                try {
                    synchronized (holdLock) {
                        /* เช็ค holding ในล็อกอีกครั้ง — กัน MOVE แทรกหลัง UP ไปแล้ว */
                        if (!holding) continue;
                        if ("webview".equals(controlMode)) {
                            if (touchProvider != null) touchProvider.move(x + dx, y + dy);
                        } else if (adb != null && adb.isConnected()) {
                            adb.shell("input motionevent MOVE " + (x + dx) + " " + (y + dy));
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
        double sigma = Math.max(1.0, span * (0.18 + 0.14 * h));
        for (int i = 0; i < 8; i++) {
            int v = (int) Math.round(center + rnd.nextGaussian() * sigma);
            if (v >= lo && v <= hi) return v;
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
