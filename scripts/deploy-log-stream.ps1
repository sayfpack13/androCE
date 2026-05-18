param(
    [Parameter(Mandatory = $true)]
    [string]$SessionLog,
    [int]$PullIntervalSec = 5
)

$ErrorActionPreference = 'Continue'

$pullTargets = [ordered]@{
    '/data/local/tmp/androce/install.log' = 'DEVICE install.log'
    '/data/local/tmp/frida-server.log' = 'DEVICE frida-server.log'
    '/sdcard/Android/data/com.androce/files/androce_log.txt' = 'APP androce_log.txt'
}

$liveDir = Join-Path (Split-Path -Parent $SessionLog) '_live'
New-Item -ItemType Directory -Force -Path $liveDir | Out-Null

function Write-SessionLine {
    param([string]$Line = '')
    if ($Line) { [Console]::Out.WriteLine($Line) } else { [Console]::Out.WriteLine() }
    Add-Content -LiteralPath $SessionLog -Value $Line -Encoding utf8
}

function Sync-DeviceLogs {
    param(
        [string]$LogPath,
        [string]$MirrorDir,
        [hashtable]$Targets,
        [hashtable]$Offsets,
        [switch]$Final
    )

    foreach ($entry in $Targets.GetEnumerator()) {
        $remote = $entry.Key
        $title = $entry.Value
        $safeName = ($remote -replace '[^a-zA-Z0-9]+', '_').Trim('_')
        $local = Join-Path $MirrorDir $safeName

        $null = & adb pull $remote $local 2>&1
        if (-not (Test-Path -LiteralPath $local)) { continue }

        $bytes = [System.IO.File]::ReadAllBytes($local)
        if ($bytes.Length -eq 0) { continue }

        $prev = 0
        if ($Offsets.ContainsKey($remote)) { $prev = $Offsets[$remote] }
        if ($bytes.Length -le $prev) { continue }

        $newBytes = $bytes[$prev..($bytes.Length - 1)]
        $Offsets[$remote] = $bytes.Length
        $text = [System.Text.Encoding]::UTF8.GetString($newBytes).TrimEnd()

        $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
        $tag = if ($Final) { 'final' } else { 'live' }
        Add-Content -LiteralPath $LogPath -Value ''
        Add-Content -LiteralPath $LogPath -Value "======== $title ($tag $stamp) ========"
        Add-Content -LiteralPath $LogPath -Value $text -Encoding utf8
        [Console]::Out.WriteLine("[pull] $title +$($newBytes.Length) bytes")
    }
}

function Sync-ShellSnapshot {
    param([string]$Title, [string]$Cmd)

    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -LiteralPath $SessionLog -Value ''
    Add-Content -LiteralPath $SessionLog -Value "======== $Title ($stamp) ========"
    $out = & adb shell su -c $Cmd 2>&1
    foreach ($line in @($out)) {
        Add-Content -LiteralPath $SessionLog -Value $line.ToString() -Encoding utf8
    }
}

$offsets = @{}
Sync-DeviceLogs -LogPath $SessionLog -MirrorDir $liveDir -Targets $pullTargets -Offsets $offsets

$pullJob = Start-Job -ScriptBlock {
    param($LogPath, $MirrorDir, $Targets, $IntervalSec)

    $offsets = @{}
    while ($true) {
        foreach ($entry in $Targets.GetEnumerator()) {
            $remote = $entry.Key
            $title = $entry.Value
            $safeName = ($remote -replace '[^a-zA-Z0-9]+', '_').Trim('_')
            $local = Join-Path $MirrorDir $safeName

            $null = & adb pull $remote $local 2>&1
            if (-not (Test-Path -LiteralPath $local)) { continue }

            $bytes = [System.IO.File]::ReadAllBytes($local)
            if ($bytes.Length -eq 0) { continue }

            $prev = 0
            if ($offsets.ContainsKey($remote)) { $prev = $offsets[$remote] }
            if ($bytes.Length -le $prev) { continue }

            $newBytes = $bytes[$prev..($bytes.Length - 1)]
            $offsets[$remote] = $bytes.Length
            $text = [System.Text.Encoding]::UTF8.GetString($newBytes).TrimEnd()

            $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
            Add-Content -LiteralPath $LogPath -Value ''
            Add-Content -LiteralPath $LogPath -Value "======== $title (live $stamp) ========"
            Add-Content -LiteralPath $LogPath -Value $text -Encoding utf8
        }
        Start-Sleep -Seconds $IntervalSec
    }
} -ArgumentList $SessionLog, $liveDir, $pullTargets, $PullIntervalSec

Add-Content -LiteralPath $SessionLog -Value ''
Add-Content -LiteralPath $SessionLog -Value '======== LOGCAT (Ctrl+C to stop) ========'
Write-SessionLine "Streaming logcat (device logs pull every ${PullIntervalSec}s)..."

$logcatArgs = @(
    'logcat', '-v', 'time',
    'SpeedInjector:I', 'SpeedInjector:E',
    'SpeedHook:I', 'SpeedHook:E', 'SpeedHook:W',
    'androCE.DependencyInstaller:I', 'androCE.DependencyInstaller:E',
    'androCE.FridaSpeedHack:I', 'androCE.FridaSpeedHack:E',
    'androCE.Setup:I', 'androCE.Setup:E',
    'GlobalExceptionHandler:E',
    'AndroidRuntime:E',
    'DEBUG:F',
    'androCE.MemoryReader:E', 'androCE.Scanner:E',
    'androCE.MemoryWriter:E', 'androCE.ScanViewModel:E', 'androCE.ProcessLister:E',
    '*:S'
)

try {
    & adb @logcatArgs 2>&1 | ForEach-Object {
        $line = $_.ToString()
        [Console]::Out.WriteLine($line)
        Add-Content -LiteralPath $SessionLog -Value $line -Encoding utf8
    }
}
finally {
    if ($pullJob) {
        Stop-Job -Job $pullJob -ErrorAction SilentlyContinue
        Remove-Job -Job $pullJob -Force -ErrorAction SilentlyContinue
    }

    Sync-DeviceLogs -LogPath $SessionLog -MirrorDir $liveDir -Targets $pullTargets -Offsets $offsets -Final
    Sync-ShellSnapshot -Title 'SELinux getenforce' -Cmd 'getenforce'
    Sync-ShellSnapshot -Title 'frida processes' -Cmd 'pgrep -af frida'
    Sync-ShellSnapshot -Title 'frida-server file' -Cmd 'file /data/local/tmp/androce/usr/bin/frida-server 2>/dev/null || file /data/local/tmp/frida-server 2>/dev/null'

    Write-SessionLine ''
    Write-SessionLine 'Log stream ended.'
}
