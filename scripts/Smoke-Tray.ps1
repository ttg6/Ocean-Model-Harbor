param([Parameter(Mandatory = $true)][string]$ImageDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$branding = Get-Content -LiteralPath (Join-Path $projectRoot 'branding.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$buildConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'build.gradle.kts') -Raw -Encoding UTF8
if ($buildConfig -notmatch 'version = "(\d+\.\d+\.\d+)"') { throw 'Project version missing.' }
$releaseVersion = $Matches[1]
$appName = $branding.name
$exe = (Resolve-Path -LiteralPath (Join-Path $ImageDirectory ($appName + '.exe'))).Path
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$data = Join-Path $env:TEMP ('ocean-tray-smoke-' + [Guid]::NewGuid().ToString('N'))
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class OceanTraySmokeNative {
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string name);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool SetPhysicalCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint x, uint y, uint data, UIntPtr info);
 [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr info);
}
'@
$process = Start-Process -FilePath $exe -ArgumentList @('--data', ('"' + $data + '"'), '--screenshot', ('"' + (Join-Path $output 'main.png') + '"')) -WindowStyle Hidden -PassThru
try {
    $main = [IntPtr]::Zero
    $ownedPids = @($process.Id)
    for ($i = 0; $i -lt 40 -and $main -eq [IntPtr]::Zero; $i++) {
        Start-Sleep -Milliseconds 200
        $children = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $process.Id -and $_.ExecutablePath -eq $exe }
        $ownedPids = @($process.Id) + @($children.ProcessId)
        foreach ($ownedPid in $ownedPids) {
            $candidate = Get-Process -Id $ownedPid -ErrorAction SilentlyContinue
            if ($candidate -and $candidate.MainWindowHandle -ne [IntPtr]::Zero) { $main = $candidate.MainWindowHandle; break }
        }
    }
    if ($main -eq [IntPtr]::Zero) { throw 'Packaged EXE did not show its window.' }
    Start-Sleep -Milliseconds 900
    [OceanTraySmokeNative]::PostMessage($main, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    Start-Sleep -Milliseconds 700
    function Find-TrayIcon {
        foreach ($cls in @('Shell_TrayWnd', 'NotifyIconOverflowWindow', 'TopLevelWindowForOverflowXamlIsland')) {
            $handle = [OceanTraySmokeNative]::FindWindow($cls, [NullString]::Value)
            if ($handle -eq [IntPtr]::Zero) { continue }
            $element = [Windows.Automation.AutomationElement]::FromHandle($handle)
            $items = $element.FindAll([Windows.Automation.TreeScope]::Descendants, [Windows.Automation.Condition]::TrueCondition)
            foreach ($item in $items) {
                if ($item.Current.Name.TrimStart().StartsWith("$appName $releaseVersion") -and !$item.Current.IsOffscreen) { return $item }
            }
        }
    }
    $icon = Find-TrayIcon
    if (!$icon) {
        $taskbar = [Windows.Automation.AutomationElement]::FromHandle([OceanTraySmokeNative]::FindWindow('Shell_TrayWnd', [NullString]::Value))
        $items = $taskbar.FindAll([Windows.Automation.TreeScope]::Descendants, [Windows.Automation.Condition]::TrueCondition)
        $chevron = $items | Where-Object { $_.Current.AutomationId -eq 'OverflowButton' -or $_.Current.Name -match 'hidden icons|\u9690\u85cf.*\u56fe\u6807' } | Select-Object -First 1
        if (!$chevron) { throw 'Tray overflow button not found.' }
        $chevron.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern).Invoke()
        Start-Sleep -Milliseconds 400
        $icon = Find-TrayIcon
    }
    if (!$icon) { throw 'New-version tray icon not found.' }
    $rect = $icon.Current.BoundingRectangle
    $x = [int]($rect.X + $rect.Width / 2); $y = [int]($rect.Y + $rect.Height / 2)
    $popupTitle = "$appName " + [char]0x00B7 + ' ' + [char]0x5FEB + [char]0x6377 + [char]0x63A7 + [char]0x5236
    function Open-TrayPopup {
        [OceanTraySmokeNative]::SetPhysicalCursorPos($x, $y) | Out-Null
        [OceanTraySmokeNative]::mouse_event(8, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 70
        [OceanTraySmokeNative]::mouse_event(16, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 350
        # Other running versions have the same popup title. Select our process
        # rather than FindWindow's first match, which can be a hidden old window.
        $condition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, $popupTitle)
        $windows = [Windows.Automation.AutomationElement]::RootElement.FindAll([Windows.Automation.TreeScope]::Children, $condition)
        foreach ($element in $windows) {
            $handle = [IntPtr]$element.Current.NativeWindowHandle
            if ($ownedPids -contains $element.Current.ProcessId -and [OceanTraySmokeNative]::IsWindowVisible($handle)) { return $element }
        }
        throw "Actual tray right-click did not open a visible control window. Icon: $($rect.ToString()); own PIDs: $ownedPids"
    }
    $popup = Open-TrayPopup
    $popupRect = $popup.Current.BoundingRectangle
    if ($popupRect.Y -gt $y -or [Math]::Abs($popupRect.Right - $x) -gt 60 -or $y - $popupRect.Bottom -gt 80) {
        throw "Tray window is not adjacent to its icon: $popupRect / $x,$y"
    }
    $captureRect = [Drawing.Rectangle]::FromLTRB([int]$popupRect.X - 8, [int]$popupRect.Y - 8,
        [int][Math]::Max($popupRect.Right + 8, $rect.Right + 8), [int][Math]::Max($popupRect.Bottom + 8, $rect.Bottom + 8))
    $bitmap = New-Object Drawing.Bitmap $captureRect.Width,$captureRect.Height
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($captureRect.Location, [Drawing.Point]::Empty, $captureRect.Size)
        $bitmap.Save((Join-Path $output 'tray-next-to-icon.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
    $bitmap = New-Object Drawing.Bitmap ([int]$popupRect.Width),([int]$popupRect.Height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen([Drawing.Point]::new([int]$popupRect.X, [int]$popupRect.Y), [Drawing.Point]::Empty, $bitmap.Size)
        $bitmap.Save((Join-Path $output 'tray-control.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
    [OceanTraySmokeNative]::keybd_event(27, 0, 0, [UIntPtr]::Zero)
    [OceanTraySmokeNative]::keybd_event(27, 0, 2, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 200
    if ([OceanTraySmokeNative]::IsWindowVisible([IntPtr]$popup.Current.NativeWindowHandle)) { throw 'Escape did not dismiss the tray window.' }
    $popup = Open-TrayPopup
    $popupRect = $popup.Current.BoundingRectangle
    [OceanTraySmokeNative]::SetPhysicalCursorPos([int]$popupRect.X - 25, [int]$popupRect.Y - 25) | Out-Null
    [OceanTraySmokeNative]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 200
    [OceanTraySmokeNative]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
    for ($i = 0; $i -lt 10 -and [OceanTraySmokeNative]::IsWindowVisible([IntPtr]$popup.Current.NativeWindowHandle); $i++) {
        Start-Sleep -Milliseconds 100
    }
    if ([OceanTraySmokeNative]::IsWindowVisible([IntPtr]$popup.Current.NativeWindowHandle)) { throw 'Outside click did not dismiss the tray window.' }
    $iconImage = [Drawing.Icon]::ExtractAssociatedIcon($exe)
    try {
        $iconBitmap = $iconImage.ToBitmap()
        try { $iconBitmap.Save((Join-Path $output 'exe-icon.png'), [Drawing.Imaging.ImageFormat]::Png) }
        finally { $iconBitmap.Dispose() }
    } finally { $iconImage.Dispose() }
    Write-Host 'Verified packaged EXE, actual tray right-click, adjacent placement, Escape, outside click, and EXE icon extraction.'
} finally {
    $targets = Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -eq $process.Id -or $_.ParentProcessId -eq $process.Id }
    foreach ($target in $targets) {
        if ($target.ExecutablePath -eq $exe -and $target.CommandLine.Contains($data)) { Stop-Process -Id $target.ProcessId -ErrorAction SilentlyContinue }
    }
}
