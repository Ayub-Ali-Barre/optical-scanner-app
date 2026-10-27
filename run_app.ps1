# Automatisk start av Android Emulator, installation och körning av Standby Optical Scanner
$ErrorActionPreference = "Stop"

$sdkPath = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
$env:ANDROID_HOME = $sdkPath
$env:Path = "$env:JAVA_HOME\bin;$sdkPath\emulator;$sdkPath\platform-tools;$env:Path"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   Standby Optical Scanner - Emulator Runner" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Kontrollera om en enhet eller emulator redan körs
$deviceList = & adb devices | Select-String "device$"

if (-not $deviceList) {
    Write-Host "`n1. Ingen aktiv Android-enhet hittades. Startar Pixel_Scanner emulatorn..." -ForegroundColor Yellow
    Write-Host "   (Kopplar din HP Wide Vision HD Camera till mobilens bakkamera)" -ForegroundColor DarkGray
    
    # Starta emulatorn i bakgrunden
    Start-Process -FilePath "$sdkPath\emulator\emulator.exe" -ArgumentList "-avd", "Pixel_Scanner", "-camera-back", "webcam0"
    
    Write-Host "2. Väntar på att den virtuella telefonen startar upp..." -ForegroundColor Yellow
    & adb wait-for-device
    
    # Vänta tills Android OS har startat klart
    $bootCompleted = ""
    while ($bootCompleted -ne "1") {
        Start-Sleep -Seconds 2
        $bootCompleted = (& adb shell getprop sys.boot_completed).Trim()
        Write-Host "." -NoNewline -ForegroundColor Gray
    }
    Write-Host "`n   Telefonen har startat!" -ForegroundColor Green
} else {
    Write-Host "`n1. Hittade en aktiv enhet/emulator! Använder befintlig instans." -ForegroundColor Green
}

# 2. Installera appen
Write-Host "`n3. Installerar appen (Standby Optical Scanner)..." -ForegroundColor Cyan
Set-Location -Path $PSScriptRoot
.\gradlew.bat installDebug

# 3. Starta appen
Write-Host "`n4. Öppnar appen på telefonens skärm..." -ForegroundColor Green
& adb shell am start -n com.standby.opticalscanner/.MainActivity

Write-Host "`n==========================================" -ForegroundColor Cyan
Write-Host " Klart! Appen körs nu i emulatorn på din PC." -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
