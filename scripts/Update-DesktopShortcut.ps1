param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$buildConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'build.gradle.kts') -Raw -Encoding UTF8
if ($buildConfig -notmatch 'version = "(\d+\.\d+\.\d+)"') { throw 'Project version missing.' }
$releaseVersion = $Matches[1]
$branding = Get-Content -LiteralPath (Join-Path $projectRoot 'branding.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$image = (Resolve-Path -LiteralPath (Join-Path $projectRoot ("dist/release-$releaseVersion/" + $branding.name))).Path
$exe = Join-Path $image ($branding.name + '.exe')
$icon = Join-Path $image 'Ocean.ico'
if (!(Test-Path -LiteralPath $exe) -or !(Test-Path -LiteralPath $icon)) { throw 'Build the release and its icon first.' }
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Desktop')) ($branding.name + '.lnk')
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $exe
$shortcut.WorkingDirectory = $image
$shortcut.Arguments = ''
$shortcut.IconLocation = $icon + ',0'
$shortcut.Description = $branding.name + ' ' + $releaseVersion
$shortcut.Save()
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class OceanShortcutRefresh {
 [DllImport("shell32.dll", CharSet=CharSet.Unicode)] public static extern void SHChangeNotify(uint evt, uint flags, string path, IntPtr unused);
}
'@
[OceanShortcutRefresh]::SHChangeNotify(0x2000, 0x0005, $shortcutPath, [IntPtr]::Zero)
$verified = $shell.CreateShortcut($shortcutPath)
if ($verified.TargetPath -ne $exe -or $verified.IconLocation -ne ($icon + ',0')) { throw 'Shortcut verification failed.' }
Write-Host "Updated: $shortcutPath -> $exe (icon: $icon)"
