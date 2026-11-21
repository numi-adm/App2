# Roblox Account Dashboard — Mobile C App (`mobile_c_app`)

โปรเจกต์แอปบนโทรศัพท์ (Android NDK ภาษา C + WebView ในตัว) แยกโฟลเดอร์อิสระตามที่ต้องการ

## สิ่งที่ปรับปรุงและตัดออกตามคำสั่ง
1. **ตัดส่วน "สมัครไอดีใหม่"** ออกทั้งหมด (ทั้งฟอร์มและโค้ดสมัคร)
2. **ตัดตัวเลือก "เก็บอัตโนมัติ"** ออก
3. **ตัด 3 ปุ่มในรูป** ออก:
   - `โหลดใหม่`
   - `เช็คสถานะที่เลือก`
   - `เช็คทั้งหมด (ทีละ 5)`
4. **ตัดปุ่ม `เช็ค` ในแต่ละแถว** ออก (เหลือเฉพาะ `เปิด`, `เปิดเอง`, `ดึง cookie`, และปุ่ม `ก๊อป`)
5. **ตัดตัวเลือกสำหรับหน้าต่าง PC** ออก (`พอร์ต 9222`, `แนบพอร์ต`, `กว้าง`, `สูง`, `ซ่อนหน้าต่าง`, `เบราว์เซอร์ Chrome/Edge`) และปรับเลย์เอาต์ให้รองรับหน้าจอมือถือ

## เพิ่มใหม่ (รอบล่าสุด)
- **จับหน้า `Security Notification` (ไอดีถูกล็อกชั่วคราว) ได้แน่นอนขึ้น**: หน้า "We've detected suspicious activity… temporarily locked it. Please recover your account to regain access." จะขึ้น `LOCKED` ทันที และ**ห้ามถูกทับเป็น `WORK`** ตอนกดดึง cookie
  - ตัวตรวจอ่านข้อความจาก `<title>`, กล่อง dialog/modal/alert/notification, **shadow DOM** และ iframe (เดิมอ่านแค่ innerText ของ body)
  - เพิ่มคีย์เวิร์ดทั้งอังกฤษ/ไทย (`security notification`, `temporarily locked`, `credentials may have been compromised`, `detected suspicious activity`, `ถูกล็อกชั่วคราว`, ฯลฯ)
  - จับซ้ำอีกชั้นในฝั่ง Java (`looksLikeSecurityLock`) เผื่อ native classifier อ่านข้อความไม่ครบ
  - ปุ่มดึง cookie จะ**ไม่เขียนทับ**สถานะ `LOCKED`/`BAN`/`VERIFY`/`RECOVERY`/`CAPTCHA`/`2FA` ที่มีอยู่เดิมด้วย `WORK` อีก
- **ปุ่ม `ดึง Cookie` กดครั้งเดียวจบ**: สแกนหน้าล่าสุด → บันทึก cookie → **ไปไอดีถัดไปในคิวให้อัตโนมัติ** (ไม่ต้องกด `ปิด / ถัดไป` เอง)
- **`⬇ ดึง cookie (ที่เลือก)` ไล่ทุกไอดีอัตโนมัติ**: เปิดทีละไอดี (ใช้ cookie เดิมก่อน), ตรวจหน้า/บันทึก cookie, เจอ `WORK` / หน้า `Security Notification` / `BAN` แล้วไปตัวถัดไปเอง — ใช้เช็กว่าอีก 7 ไอดีที่เหลือติดล็อกเหมือนกันหรือไม่ (ถ้าติด 2FA/ยืนยันว่าเป็นมนุษย์ จะหยุดรอให้กดเอง)
- **ในคิวปกติ เจอหน้า `Security Notification` หรือ `BAN` จะข้ามไปไอดีถัดไปให้เองใน 4 วินาที** (ไอดีใช้งานไม่ได้แล้ว) — ส่วน "ยืนยันว่าเป็นมนุษย์" ยังหยุดรอให้กดยืนยันในหน้าต่างเหมือนเดิม
- **`LOCKED`/`BAN` ไม่ถูกสั่งรีลองใหม่ 3 ครั้งอีกต่อไป** (เดิมตัวจับเวลา 10 วิอาจสั่งรีทิ้งไอดีที่ล็อกแล้ว)
- **แก้ปัญหาบางครั้งไม่ใส่รหัสให้ (Autofill Password)**: ปรับปรุง JavaScript Autofill ให้มีการ Escape แบบ JSON-safe ป้องกันเครื่องหมายพิเศษในรหัสผ่าน พร้อมระบบ Verify DOM Input ตรวจสอบค่า `p.value` ซ้ำก่อนคลิกปุ่มเข้าสู่ระบบ ป้องกัน React Hydration / Re-render ล้างค่ารหัสผ่าน
- **ถ้าครบ 10 วินาทีแล้วไม่เปลี่ยนเป็นติดกดเพื่อยืนยัน (VERIFY) จะข้ามไอดีตามเดิม**: คืนค่าตัวจับเวลาการรอผลหลังกดล็อกอินกลับเป็น 10 วินาที หากไม่ขึ้น Verify (Press and hold) และไม่ได้ Cookie ภายใน 10 วิ จะข้ามไอดีนั้นทันที แต่ถ้าติด VERIFY จะหยุดรอให้ผู้ใช้กดยืนยันเอง
- **ปรับแค่ "รหัสไม่ตรง" (BADPASS) กับ "เกิดข้อผิดพลาดไม่ทราบสาเหตุ" (RETRY) ที่รีเซ็ตหน้า**: สถานะอื่น (ค้าง 10 วิ, VERIFY, LOCKED, 2FA, CAPTCHA, BAN, RATE) จะไม่รีเซ็ตหน้าเว็บ
- **นำแถบ "หน้าหลัก" ในหน้าต่าง Roblox กลับมา**: มีปุ่ม "หน้าหลัก" บนแถบเครื่องมือด้านบน สำหรับหยุดคิวการทำงานทั้งหมดและกลับหน้าหลักแดชบอร์ดได้ตลอดเวลา
- **เอาหลายหน้าจอออก**: ถอดระบบเปิดพร้อมกันหลายจอ/Grid/Multi-slot ออกทั้งหมด กลับมาใช้หน้าต่างเดี่ยว (Single Roblox WebView) ทำงานทีละ 1 ไอดีตามลำดับคิว
- **ปรับให้ระบบเสถียรขึ้น**: หมดปัญหา Cookie ตีกันจาก CookieManager ใน Android, ประหยัด RAM, ซิงค์ Cookie อย่างปลอดภัย
- **ช่องนำเข้ารับได้หลายบรรทัด** (textarea) — วาง `ชื่อ:รหัส` หรือ `.ROBLOSECURITY` หลายบรรทัดพร้อมกันได้ บรรทัดละ 1 ไอดี เช่นวาง cookie 2 บรรทัด = ได้ 2 ไอดี (ถ้าไม่มีชื่อ ระบบจะเติมชื่อจาก cookie ให้ตอนกดเช็ค cookie)
- **ปุ่ม `↻ ลองใหม่`** — ทำซ้ำคำสั่งล่าสุด (เช็ค cookie / ตรวจรหัส / ดึง cookie) และในหน้าต่าง Roblox มีปุ่ม **ลองใหม่** สำหรับเริ่มไอดีปัจจุบันใหม่ (ล้างเซสชันแล้วกรอก/เช็คอีกรอบ)
- **สถานะใหม่ `RECOVERY`** — ตรวจจับหน้าที่ติด Recovery / กู้บัญชี / ยืนยันตัวตน (อีเมล, รหัสกู้คืน, ใช้เครื่องอื่น)
- **สถานะใหม่ `LOCKED` + แจ้งเตือน** — หน้า "Account locked / suspicious activity / Confirm you're a human" จะขึ้นป้าย `LOCKED` และเด้งแจ้งเตือน "ไอดีถูกล็อก — กด Continue แล้วยืนยันว่าเป็นคนในหน้าต่าง" (ไม่ปิดหน้าต่าง/ไม่ข้ามไอดี)
- **แก้บั๊ก "ดึง cookie แล้วขึ้น WORK ทั้งที่โดนล็อก"** — ปุ่มดึง cookie จะไม่ทับสถานะ `LOCKED`/`BAN`/`VERIFY`/`RECOVERY`/`CAPTCHA`/`2FA` ด้วย `WORK` อีก และตัวตรวจหน้าจะอ่านข้อความในกล่องข้อความ (dialog/modal), shadow DOM และ iframe ด้วย จึงจับ "Account locked" ได้แม้ข้อความอยู่ท้ายหน้า
- **แก้บั๊ก "รหัสไม่ตรงไม่ขึ้น"** — เดิมถ้า Roblox ให้ cookie ของ guest หลังล็อกอินพลาด ระบบจะเข้าใจว่า WORK และข้ามไอดีไป; ตอนนี้ตรวจหน้า /login + ข้อความ error (incorrect / username and/or password / ไม่ถูกต้อง) ให้เป็น `BADPASS` ก่อน และไม่บันทึก cookie ของ guest ลงฐานข้อมูล
- **นำเข้าไฟล์ Sheet (.csv)** ที่มีคอลัมน์ `username,password,cookie,status,created,checked,note` — รหัสผ่านถูกเก็บลง `accounts.json` ด้วย
- **ปุ่ม `🍪 เช็ค cookie (ที่เลือก)` / `🍪 ทั้งหมด`** — เอา cookie ไปถาม `users.roblox.com/v1/users/authenticated` ว่าเป็นบัญชีไหน แล้วเทียบกับชื่อในชีต (ทำในแอป ไม่ต้องเปิด Roblox WebView และไม่ต้องเปิดเบราว์เซอร์)
  - `WORK` = cookie ตรงกับชื่อ — ใช้ได้
  - `MISMATCH` = cookie เป็นของบัญชีอื่น ไม่ตรงกับชื่อในชีต
  - `BADPASS` = cookie หมดอายุ/ใช้ไม่ได้
  - `NOCOOKIE` = ยังไม่มี cookie
- **ปุ่ม `🔑 ตรวจรหัส:ชื่อ`** — ตรวจว่ารหัสผ่านตรงกับชื่อผู้ใช้จริงหรือไม่ โดย**บังคับล็อกอินด้วยรหัส** (ล้าง session/cookie ก่อน ไม่ใช้ cookie เดิม):
  - ขึ้น `BADPASS` + note “รหัสไม่ตรงกับชื่อผู้ใช้” = รหัสผิด
  - ขึ้น `WORK` = รหัสตรง ล็อกอินได้ (และเก็บ cookie ให้)
  - ขึ้น `VERIFY/CAPTCHA/2FA/RATE` + note “รหัสถูกต้อง” = รหัสถูกแต่ติดด่านของ Roblox
- ปุ่ม `เปิด` ยังใช้ cookie เดิมตามปกติ (ไม่ตรวจรหัส) — ใช้ตอนเข้าเกม/ใช้งาน

---

## โครงสร้างไฟล์ใน `mobile_c_app`
- `app/src/main/cpp/native_core.h` & `native_core.c` — **หัวใจหลักภาษา C (C11 + JNI)**:
  - จัดการอ่าน/เขียนฐานข้อมูล `accounts.json` ด้วยภาษา C
  - แยกข้อความนำเข้า (`ชื่อ:รหัส` หรือ `.ROBLOSECURITY` cookie)
  - สกัดค่า `.ROBLOSECURITY` จาก Cookie Header ของเบราว์เซอร์ในแอป
  - สร้างสคริปต์ Auto-Fill กรอก `username` และ `password` อัตโนมัติเมื่อเปิดหน้าล็อกอิน Roblox
- `app/src/main/cpp/CMakeLists.txt` — ไฟล์ CMake สำหรับคอมไพล์ C เป็นไลบรารี `libroblox_dash.so` บน Android NDK
- `app/src/main/java/com/rooo/dashboard/MainActivity.java` & `NativeBridge.java` — หน้าต่างหลักของแอป Android พร้อม WebView แดชบอร์ด และหน้าต่าง Roblox Browser ในตัว (มีปุ่ม **ดึง Cookie**, **ย่อหน้าต่าง**, **ปิด**)
- `app/src/main/assets/dashboard.html` — หน้า UI สำหรับมือถือที่เชื่อมกับ C Core ผ่าน `window.NativeApp`
- `server_main.c` — เซิร์ฟเวอร์ HTTP ภาษา C ล้วน สำหรับคอมไพล์รันบน **Termux (มือถือ Android)** หรือบน PC ก็ได้

---

## วิธีนำไปใช้งาน / สร้างไฟล์ `.apk` ลงมือถือ

### วิธีที่ 1: เปิดด้วย Android Studio เพื่อ Build APK
1. เปิดโปรแกรม **Android Studio** แล้วเลือก **Open** ไปที่โฟลเดอร์ `c:\Users\msi\Desktop\Rooo\mobile_c_app`
2. รอให้ Gradle ซิงก์และคอมไพล์ CMake (`native_core.c`)
3. เลือกเมนู **Build > Build Bundle(s) / APK(s) > Build APK(s)** เพื่อได้ไฟล์ `.apk` ติดตั้งลงมือถือได้ทันที

### วิธีที่ 2: Build APK อัตโนมัติผ่าน GitHub Actions (ไม่ต้องลง Android Studio ในเครื่อง)
1. อัปโหลดโฟลเดอร์ `mobile_c_app` ขึ้น GitHub Repository
2. ไฟล์ `.github/workflows/build-apk.yml` จะสร้างไฟล์ `RobloxDashboardC-debug-apk` ให้อัตโนมัติ ดาวน์โหลดไปติดตั้งในมือถือได้เลย

### วิธีที่ 3: รันด้วยภาษา C ล้วนบนมือถือผ่านแอป Termux
หากใช้แอป **Termux** บน Android:
```bash
pkg install clang
clang -O2 server_main.c app/src/main/cpp/native_core.c -o rooo_mobile
./rooo_mobile
```
แล้วเปิดเบราว์เซอร์บนมือถือไปที่ `http://127.0.0.1:8765`
