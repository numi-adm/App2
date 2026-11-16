@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================================
echo   อัปโหลดโปรเจกต์ขึ้น GitHub เพื่อสร้างไฟล์ APK อัตโนมัติ
echo ========================================================
echo.
echo 1. ไปที่ https://github.com/new
echo 2. ตั้งชื่อ Repository (เช่น roblox-mobile-c) เลือกเป็น Private หรือ Public ก็ได้
echo 3. กดปุ่ม "Create repository" แล้วคัดลอกลิงก์ https://github.com/... มาวางด้านล่าง
echo.
set /p REPO_URL="วางลิงก์ GitHub Repository ของคุณที่นี่: "

if "%REPO_URL%"=="" (
    echo ไม่ได้ใส่ลิงก์ ยกเลิกการทำงาน
    pause
    exit /b
)

git init
git branch -M main
git add .
git commit -m "Initial commit: Roblox Dashboard Mobile C App" 2>nul
git remote remove origin 2>nul
git remote add origin %REPO_URL%
echo.
echo กำลังอัปโหลดขึ้น GitHub...
git push -u origin main --force

echo.
echo ========================================================
echo เสร็จสิ้น! ไปที่แท็บ "Actions" ในหน้า GitHub ของคุณ
echo รอประมาณ 2-3 นาที แล้วกดดาวน์โหลดไฟล์ RobloxDashboardC-APK ได้เลยครับ
echo ========================================================
pause
