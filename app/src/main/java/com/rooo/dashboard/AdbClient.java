package com.rooo.dashboard;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * ADB client ขั้นต่ำที่พอร์ตแนวคิดจาก FFFFFF/bot.py (ADBManager)
 * รองรับการต่อตรงที่พอร์ต ADB ของ MuMu/อุปกรณ์ (เช่น 127.0.0.1:16384) ผ่าน ADB wire protocol
 * ไม่ต้องพึ่ง adb.exe บนเครื่องและไม่ต้อง root
 *
 * รูปแบบแพ็กเก็ต ADB: 24-byte header (cmd,arg0,arg1,len,crc,magic) + payload
 */
final class AdbClient {

    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int ADB_VERSION = 0x01000001;
    private static final int MAX_DATA = 1024 * 1024;
    private static final int LOCAL_ID = 1;

    private final String host;
    private final int port;
    private final int timeoutMs;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    AdbClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 8000;
    }

    String target() {
        return host + ":" + port;
    }

    /** เชื่อมต่อและจับมือ ADB (CNXN) */
    synchronized void connect() throws IOException {
        close();
        closed.set(false);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = new DataOutputStream(s.getOutputStream());

        send(A_CNXN, ADB_VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        Packet p = read();
        if (p.cmd == A_AUTH) {
            throw new IOException("อุปกรณ์ต้องการอนุญาต ADB (ให้กด Allow บนจอ MuMu/มือถือก่อน)");
        }
        if (p.cmd != A_CNXN) {
            throw new IOException("ADB handshake ไม่สำเร็จ (cmd=" + Integer.toHexString(p.cmd) + ")");
        }
    }

    /** รัน `shell:<cmd>` และคืนผลที่ได้จนจบ */
    synchronized String shell(String cmd) throws IOException {
        ensureOpen();
        openService("shell:" + cmd);
        return new String(readStream(20 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    /** รัน `exec:<cmd>` และคืน bytes ดิบ (ใช้กับ `screencap`) */
    synchronized byte[] exec(String cmd, int maxBytes) throws IOException {
        ensureOpen();
        openService("exec:" + cmd);
        return readStream(maxBytes > 0 ? maxBytes : 40 * 1024 * 1024);
    }

    /** เปิดบริการแบบค้างไว้ (ใช้กับ `input swipe ... 600000`) คืน client ตัวใหม่ที่อ่านต่อใน area */
    synchronized AdbClient openLongRunning(String cmd) throws IOException {
        AdbClient c = new AdbClient(host, port, 600000);
        c.connect();
        c.openService("exec:" + cmd);
        c.startDrainer();
        return c;
    }

    private void startDrainer() {
        Thread t = new Thread(() -> {
            try {
                readStream(1024 * 1024);
            } catch (Exception ignored) {
            }
        }, "adb-drainer");
        t.setDaemon(true);
        t.start();
    }

    private void ensureOpen() throws IOException {
        if (socket == null || socket.isClosed() || closed.get()) {
            throw new IOException("ยังไม่ได้เชื่อมต่อ ADB หรือหลุดแล้ว");
        }
    }

    private synchronized void openService(String service) throws IOException {
        send(A_OPEN, LOCAL_ID, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p.cmd == A_WRTE) {
                send(A_OKAY, LOCAL_ID, p.arg0, new byte[0]);
                continue;
            }
            if (p.cmd == A_CLSE) {
                throw new IOException("ADB ปฏิเสธคำสั่ง: " + service);
            }
            if (p.cmd == A_OKAY) {
                return;
            }
        }
        throw new IOException("ADB ไม่ตอบคำสั่ง: " + service);
    }

    private synchronized byte[] readStream(int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            Packet p = read();
            if (p.cmd == A_WRTE) {
                if (bos.size() + p.data.length <= maxBytes) {
                    bos.write(p.data, 0, p.data.length);
                }
                send(A_OKAY, LOCAL_ID, p.arg0, new byte[0]);   // arg0 ของฝ่ายตรงข้าม = remote id ของเรา
            } else if (p.cmd == A_CLSE) {
                break;
            } else if (p.cmd == A_OKAY) {
                continue;
            }
        }
        return bos.toByteArray();
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        if (data.length > MAX_DATA) throw new IOException("ADB payload ใหญ่เกินไป");
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length)
         .putInt((int) crc.getValue()).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private synchronized Packet read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head, 0, 24);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = h.getInt();
        int arg0 = h.getInt();
        int arg1 = h.getInt();
        int len = h.getInt();
        int crc = h.getInt();
        int magic = h.getInt();
        if (magic != (cmd ^ 0xffffffff)) {
            throw new IOException("ADB packet magic ไม่ถูกต้อง");
        }
        if (len < 0 || len > MAX_DATA) {
            throw new IOException("ADB packet size ไม่ถูกต้อง: " + len);
        }
        byte[] data = new byte[len];
        if (len > 0) in.readFully(data);
        /* adb รุ่นใหม่ (protocol v2) ส่ง data_check = 0 และไม่ตรวจ CRC เลย
         * จึงไม่ควร reject แพ็กเก็ตเพราะ CRC ไม่ตรง */
        return new Packet(cmd, arg0, arg1, data);
    }

    /** ต้องไม่ synchronized — ไม่งั้นจะบล็อกตอนปิดขณะ drainer ค้างอ่านอยู่ */
    void close() {
        if (closed.getAndSet(true)) return;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    boolean isConnected() {
        return socket != null && !socket.isClosed() && !closed.get();
    }

    private static final class Packet {
        final int cmd, arg0, arg1;
        final byte[] data;

        Packet(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
