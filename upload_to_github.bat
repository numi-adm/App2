@echo off
cd /d "%~dp0"
echo ========================================================
echo   Uploading fixed project to GitHub (App2 and App)...
echo ========================================================
echo.

git add .
git commit -m "Fix GitHub Actions SDK and NDK setup"

echo.
echo Pushing to https://github.com/numi-adm/App2.git ...
git push https://github.com/numi-adm/App2.git main --force

echo.
echo Pushing to https://github.com/numi-adm/App.git ...
git push https://github.com/numi-adm/App.git main --force

echo.
echo ========================================================
echo Done! Open https://github.com/numi-adm/App2/actions
echo Wait 2-3 minutes for the green checkmark, then download
echo RobloxDashboardC-APK from the Artifacts section.
echo ========================================================
pause
