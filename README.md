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
