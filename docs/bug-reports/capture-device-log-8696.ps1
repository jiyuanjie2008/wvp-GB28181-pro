# Capture GB28181 device logcat for device 8696 (CONTROL: reproducibly successful).
# Goal: collect a clean log of 3 successful INVITE/BYE cycles to compare against 5331 (broken).
# Strategy: enlarge logd ringbuffer, let user run scenario, then DUMP buffer once.
# Prereq: USB debugging on, Vysor client closed, device 8696 connected via USB.

$ErrorActionPreference = 'Continue'

$AdbExe   = 'C:\Users\jiyua\AppData\Local\vysor\app-5.0.7\resources\app.asar.unpacked\native\win32\adb.exe'
$Stamp    = Get-Date -Format 'yyyyMMdd-HHmm'
$OutFile  = "d:\JXT\jxt-evidence-system\wvp-GB28181-pro\docs\bug-reports\device_log_8696_${Stamp}.txt"
$DevPath  = '/sdcard/dlog_8696.txt'

if (-not (Test-Path $AdbExe)) {
    Write-Host "[ERR] adb not found: $AdbExe" -ForegroundColor Red
    Read-Host "press Enter to close"
    exit 1
}

function Wait-Device {
    param([int]$Tries = 8)
    for ($i = 1; $i -le $Tries; $i++) {
        $out = & $AdbExe devices 2>&1 | Out-String
        if ($out -match "device(`r|`n|$)") {
            $lines = $out -split "`n" | Where-Object { $_ -match "\sdevice\s*$" }
            if ($lines.Count -gt 0) { return $true }
        }
        Write-Host "  [retry $i/$Tries] device not online, wait 1s..." -ForegroundColor Yellow
        Start-Sleep -Seconds 1
    }
    return $false
}

Write-Host "==> Step 0: check device (expecting device 8696)" -ForegroundColor Cyan
& $AdbExe devices
if (-not (Wait-Device 5)) {
    Write-Host "[ERR] no device. Check USB / Vysor closed / adb authorized." -ForegroundColor Red
    Read-Host "press Enter to close"
    exit 1
}
Write-Host "  OK device online"
Write-Host "  Output file: $OutFile" -ForegroundColor Yellow

Write-Host ""
Write-Host "==> Step 1: enlarge logd ringbuffer to 16M and clear" -ForegroundColor Cyan
& $AdbExe shell "logcat -G 16M"
Start-Sleep -Milliseconds 500
& $AdbExe shell "logcat -c"
Start-Sleep -Milliseconds 500
$bufSize = & $AdbExe shell "logcat -g 2>&1 | head -3"
Write-Host "  buffer status:"
Write-Host $bufSize

Write-Host ""
Write-Host "==> Step 2: NOW operate the wvp web UI on device 8696:" -ForegroundColor Yellow
Write-Host "       Same protocol as 5331 capture for direct comparison."
Write-Host ""
Write-Host "       Round 1 (short play):"
Write-Host "         (1) Click PLAY                  ** 1st"
Write-Host "         (2) Wait ~10 seconds"
Write-Host "         (3) Click STOP                  ** 1st BYE"
Write-Host "         (4) Wait ~15 seconds"
Write-Host ""
Write-Host "       Round 2 (medium play):"
Write-Host "         (5) Click PLAY                  ** 2nd (expected SUCCESS for 8696)"
Write-Host "         (6) Wait ~30 seconds"
Write-Host "         (7) Click STOP                  ** 2nd BYE"
Write-Host "         (8) Wait ~15 seconds"
Write-Host ""
Write-Host "       Round 3 (verify still works):"
Write-Host "         (9) Click PLAY                  ** 3rd (expected SUCCESS for 8696)"
Write-Host "        (10) Wait ~30 seconds"
Write-Host "        (11) Click STOP                  ** 3rd BYE"
Write-Host ""
Write-Host "  Goal: 3 SUCCESSFUL INVITE/BYE cycles for control comparison."
Write-Host ""
Write-Host "  IMPORTANT: no logcat process is running during your operation."
Write-Host "  All logs accumulate in the kernel ringbuffer. Buffer is 16MB."
Write-Host ""
Read-Host "  Press Enter when finished"

Write-Host ""
Write-Host "==> Step 3: dump ringbuffer to device file (filtered)" -ForegroundColor Cyan
$tags = @(
    'GB28181:V', 'Gb28181Local:V', 'gb28181:V',
    'System.out:V',
    'DCWLog:V', 'DCWLog-dcwAvcEncoder:V',
    'sip:V', 'sua:V', 'sua_call:V', 'SIP:V',
    'rtp:V', 'rtp_tx:V', 'RtpTx:V', 'RtpTcp:V', 'rtp_send:V',
    'AndroidRuntime:E', 'libc:E', 'DEBUG:E',
    '*:S'
) -join ' '
$dumpCmd = "logcat -d -v threadtime -f $DevPath $tags"
Write-Host "  cmd: $dumpCmd"
& $AdbExe shell $dumpCmd
Start-Sleep -Seconds 2

$sz = & $AdbExe shell "stat -c %s $DevPath 2>/dev/null"
Write-Host "  device file size: $sz bytes"

Write-Host ""
Write-Host "==> Step 4: pull file" -ForegroundColor Cyan
if (Test-Path $OutFile) { Remove-Item $OutFile -Force }
& $AdbExe pull $DevPath $OutFile
& $AdbExe shell "rm -f $DevPath" 2>$null

if (Test-Path $OutFile) {
    $size  = (Get-Item $OutFile).Length
    $lines = (Get-Content $OutFile).Count
    $kb    = [Math]::Round($size/1KB, 1)
    Write-Host ""
    Write-Host "==> DONE. File saved:" -ForegroundColor Green
    Write-Host "    $OutFile"
    Write-Host "    size  = $kb KB"
    Write-Host "    lines = $lines"

    # quick stats - same metrics as 5331 capture for direct comparison
    Write-Host ""
    Write-Host "  --- quick stats (compare to 5331) ---" -ForegroundColor Cyan
    $invites  = (Select-String -Path $OutFile -Pattern "INVITE sip:").Count
    $byes     = (Select-String -Path $OutFile -Pattern "^.{19}.*\bBYE sip:").Count
    $states   = (Select-String -Path $OutFile -Pattern "sua_call_state.*::").Count
    $live2    = (Select-String -Path $OutFile -Pattern "Gb28181Local.*liveState:2").Count
    $rtpInit  = (Select-String -Path $OutFile -Pattern "rtp_tx.*init|RtpTx.*init|rtp_send.*init|sock.*open|connect.*succ").Count
    $warn302  = (Select-String -Path $OutFile -Pattern "ua_media\.cpp.{0,5}302").Count
    Write-Host "  INVITE recv lines:           $invites"
    Write-Host "  BYE recv lines:              $byes"
    Write-Host "  sua_call_state trans:        $states"
    Write-Host "  liveState:2 events:          $live2"
    Write-Host "  rtp init/sock hits:          $rtpInit"
    Write-Host "  ua_media.cpp:302 WARN hits:  $warn302   *** KEY DIFFERENCE vs 5331 ***" -ForegroundColor Yellow

    # event timeline
    Write-Host ""
    Write-Host "  --- event timeline (call_state + liveState transitions) ---" -ForegroundColor Cyan
    $script:liveprev = ''
    Get-Content $OutFile | ForEach-Object {
        if ($_ -match '(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3}).*p_sua->call_state=(\d)') {
            "    $($matches[1])  call_state=$($matches[2])"
        }
        elseif ($_ -match '(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3}).*Gb28181Local: onStateUpdate.*liveState:(\d)') {
            $cur = $matches[2]
            if ($cur -ne $script:liveprev) {
                "    $($matches[1])  liveState=$cur"
                $script:liveprev = $cur
            }
        }
    }
} else {
    Write-Host "[ERR] pull failed. Check device-side $DevPath." -ForegroundColor Red
}

Read-Host "press Enter to close"
