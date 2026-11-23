package com.rooo.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * ที่เก็บข้อมูลของ Auto-Hold Bot
 *  <filesDir>/AutoHoldBot/
 *      templates/   *.png + *.json  (ต้นแบบกด / ต้นแบบปล่อย)
 *      config.json  (ตั้งค่าที่บันทึกไว้)
 *      bot.log      (ประวัติสถานะ)
 *
 * ใช้ internal storage ของแอป จึงไม่ต้องขอสิทธิ์ READ/WRITE_EXTERNAL_STORAGE
 */
final class BotStorage {

    static final class SavedTemplate {
        final String name;
        final boolean release;
        final byte[] rgba;
        final int w, h;

        SavedTemplate(String name, boolean release, byte[] rgba, int w, int h) {
            this.name = name;
            this.release = release;
            this.rgba = rgba;
            this.w = w;
            this.h = h;
        }
    }

    private static final String TAG = "BotStorage";
    private static final String DIR = "AutoHoldBot";
    private static final String SUB = "templates";
    private static final long LOG_LIMIT = 256L * 1024;

    private final File root;
    private final File tplDir;
    private final File cfgFile;
    private final File logFile;
    private final SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
    private final Random rnd = new Random();

    BotStorage(Context ctx) {
        File base = ctx != null ? ctx.getExternalFilesDir(null) : null;
        if (base == null && ctx != null) base = ctx.getFilesDir();
        if (base == null) base = new File(System.getProperty("java.io.tmpdir", "."));
        root = new File(base, DIR);
        tplDir = new File(root, SUB);
        cfgFile = new File(root, "config.json");
        logFile = new File(root, "bot.log");
        //noinspection ResultOfMethodCallIgnored
        tplDir.mkdirs();
    }

    String path() {
        return root.getAbsolutePath();
    }

    String templatePath() {
        return tplDir.getAbsolutePath();
    }

    private static String safe(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private File png(String name) {
        return new File(tplDir, safe(name) + ".png");
    }

    private File meta(String name) {
        return new File(tplDir, safe(name) + ".json");
    }

    /** บันทึกต้นแบบ → คืนชื่อไฟล์ (ไม่ include นามสกุล) หรือ null ถ้าไม่สำเร็จ */
    synchronized String save(byte[] rgba, int w, int h, boolean release) {
        String name = "tpl_" + stamp.format(new java.util.Date()) + "_" + rnd.nextInt(1000)
                + (release ? "_rel" : "_press");
        try {
            int[] argb = new int[w * h];
            for (int i = 0; i < argb.length; i++) {
                int r = rgba[i * 4] & 0xFF;
                int g = rgba[i * 4 + 1] & 0xFF;
                int b = rgba[i * 4 + 2] & 0xFF;
                argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            Bitmap bm = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
            boolean ok;
            FileOutputStream fos = new FileOutputStream(png(name));
            try {
                ok = bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            bm.recycle();
            if (!ok) return null;

            JSONObject j = new JSONObject();
            j.put("name", name);
            j.put("release", release);
            j.put("w", w);
            j.put("h", h);
            j.put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()));
            writeText(meta(name), j.toString());
            return name;
        } catch (Exception e) {
            Log.w(TAG, "save: " + e.getMessage());
            delete(name);
            return null;
        }
    }

    synchronized List<SavedTemplate> loadAll() {
        List<SavedTemplate> out = new ArrayList<>();
        File[] files = tplDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) {
            SavedTemplate t = load(stripJson(f.getName()));
            if (t != null) out.add(t);
        }
        return out;
    }

    synchronized SavedTemplate load(String name) {
        File p = png(name), m = meta(name);
        if (!p.exists() || !m.exists()) return null;
        try {
            JSONObject j = new JSONObject(readText(m));
            Bitmap bm = BitmapFactory.decodeFile(p.getAbsolutePath());
            if (bm == null) return null;
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < px.length; i++) {
                rgba[i * 4]     = (byte) ((px[i] >> 16) & 0xFF);
                rgba[i * 4 + 1] = (byte) ((px[i] >> 8) & 0xFF);
                rgba[i * 4 + 2] = (byte) (px[i] & 0xFF);
                rgba[i * 4 + 3] = (byte) ((px[i] >> 24) & 0xFF);
            }
            boolean rel = j.optBoolean("release", false);
            return new SavedTemplate(safe(name), rel, rgba, w, h);
        } catch (Exception e) {
            Log.w(TAG, "load " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** รายการต้นแบบในโฟลเดอร์ (อ่านเฉพาะ json ไม่ต้อง decode png) */
    synchronized String listJson() {
        JSONArray arr = new JSONArray();
        File[] files = tplDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return arr.toString();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) {
            try {
                JSONObject j = new JSONObject(readText(f));
                JSONObject o = new JSONObject();
                o.put("name", safe(j.optString("name", stripJson(f.getName()))));
                o.put("release", j.optBoolean("release", false));
                o.put("w", j.optInt("w", 0));
                o.put("h", j.optInt("h", 0));
                o.put("created", j.optString("created", ""));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    synchronized boolean delete(String name) {
        boolean ok = false;
        String s = safe(name);
        if (s.isEmpty()) return false;
        File p = png(s), m = meta(s);
        if (p.exists()) ok |= p.delete();
        if (m.exists()) ok |= m.delete();
        return ok;
    }

    synchronized int clearAll() {
        int n = 0;
        File[] files = tplDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.delete()) n++;
            }
        }
        return n;
    }

    synchronized void saveConfig(JSONObject cfg) {
        try {
            writeText(cfgFile, cfg.toString());
        } catch (Exception e) {
            Log.w(TAG, "saveConfig: " + e.getMessage());
        }
    }

    synchronized JSONObject loadConfig() {
        try {
            if (cfgFile.exists()) return new JSONObject(readText(cfgFile));
        } catch (Exception e) {
            Log.w(TAG, "loadConfig: " + e.getMessage());
        }
        return new JSONObject();
    }

    synchronized void appendLog(String line) {
        try {
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
            String text = time + "  " + line + "\n";
            FileOutputStream fos = new FileOutputStream(logFile, true);
            try {
                fos.write(text.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            if (logFile.length() > LOG_LIMIT) {
                String all = readText(logFile);
                int cut = all.length() / 2;
                int nl = all.indexOf('\n', cut);
                if (nl > 0) {
                    FileOutputStream f2 = new FileOutputStream(logFile, false);
                    try {
                        f2.write(all.substring(nl + 1).getBytes("UTF-8"));
                    } finally {
                        f2.close();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    synchronized String readLog(int maxLines) {
        try {
            if (!logFile.exists()) return "";
            String all = readText(logFile);
            String[] lines = all.split("\\R");
            int from = Math.max(0, lines.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    synchronized void clearLog() {
        //noinspection ResultOfMethodCallIgnored
        logFile.delete();
    }

    private static String stripJson(String n) {
        return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }

    private static void writeText(File f, String s) throws IOException {
        FileOutputStream fos = new FileOutputStream(f, false);
        try {
            fos.write(s.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    private static String readText(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } finally {
            br.close();
        }
        return sb.toString();
    }
}
