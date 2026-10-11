param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$buildConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'build.gradle.kts') -Raw -Encoding UTF8
if ($buildConfig -notmatch 'version = "(\d+\.\d+\.\d+)"') { throw 'Project version missing.' }
$releaseVersion = $Matches[1]
$branding = Get-Content -LiteralPath (Join-Path $projectRoot 'branding.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$appName = $branding.name
$thanksName = $branding.acknowledgementsFile
if ([string]::IsNullOrWhiteSpace($thanksName) -or [IO.Path]::GetFileName($thanksName) -ne $thanksName) {
    throw 'Acknowledgements filename missing or invalid.'
}
$tutorialName = $branding.tutorialFile
if ([string]::IsNullOrWhiteSpace($tutorialName) -or [IO.Path]::GetFileName($tutorialName) -ne $tutorialName) {
    throw 'Tutorial filename missing or invalid.'
}
$archiveRoot = $appName + '/'
$sourceImage = Join-Path $projectRoot ("dist/release-$releaseVersion/" + $appName)
$exe = Join-Path $sourceImage ($appName + '.exe')
if (!(Test-Path -LiteralPath $exe)) { throw 'Build the Windows release first.' }
$workRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
$staging = Join-Path $workRoot ('customer-package-' + [Guid]::NewGuid().ToString('N'))
$image = Join-Path $staging $appName
New-Item -ItemType Directory -Path $image -Force | Out-Null
$archiveName = "$($branding.archivePrefix)-$releaseVersion-Customer.zip"
$destination = Join-Path $projectRoot ('dist/' + $archiveName)
try {
    # Copy only application binaries, bundled runtime and license notices.
    foreach ($item in @('app', 'runtime', ($appName + '.exe'), 'Ocean.ico', 'LICENSE', 'THIRD-PARTY-NOTICES.md')) {
        Copy-Item -LiteralPath (Join-Path $sourceImage $item) -Destination $image -Recurse
    }
    $guide = Join-Path $projectRoot 'docs/Customer-Quick-Start.txt'
    if (!(Test-Path -LiteralPath $guide)) { throw 'Customer guide missing.' }
    $instructions = Get-Content -LiteralPath $guide -Raw -Encoding UTF8
    [IO.File]::WriteAllText((Join-Path $image 'Quick-Start.txt'), $instructions, [Text.UTF8Encoding]::new($true))
    $thanksPath = Join-Path $projectRoot 'docs/Open-Source-Thanks.txt'
    if (!(Test-Path -LiteralPath $thanksPath)) { throw 'Open-source acknowledgements missing.' }
    $thanks = Get-Content -LiteralPath $thanksPath -Raw -Encoding UTF8
    [IO.File]::WriteAllText((Join-Path $image $thanksName), $thanks, [Text.UTF8Encoding]::new($true))
    $tutorialPath = Join-Path $projectRoot 'docs/Customer-Tutorial.txt'
    if (!(Test-Path -LiteralPath $tutorialPath)) { throw 'Customer tutorial missing.' }
    $tutorial = Get-Content -LiteralPath $tutorialPath -Raw -Encoding UTF8
    [IO.File]::WriteAllText((Join-Path $image $tutorialName), $tutorial, [Text.UTF8Encoding]::new($true))
    $fileHashes = @{}
    $manifest = Get-ChildItem -LiteralPath $image -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($image.Length + 1).Replace('\', '/')
        $fileHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $fileHashes[$relative] = $fileHash
        '{0}  {1}' -f $fileHash, $relative
    }
    $manifest | Out-File -LiteralPath (Join-Path $image 'FILES-SHA256.txt') -Encoding utf8
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $temporaryZip = Join-Path $staging 'customer.zip'
    $writer = [IO.Compression.ZipFile]::Open($temporaryZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        Get-ChildItem -LiteralPath $image -Recurse -File -Force | ForEach-Object {
            $relative = $_.FullName.Substring($image.Length + 1).Replace('\', '/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($writer, $_.FullName,
                ($archiveRoot + $relative), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $writer.Dispose() }
    $zip = [IO.Compression.ZipFile]::OpenRead($temporaryZip)
    try {
        $required = @(($archiveRoot + $appName + '.exe'), ($archiveRoot + "app/desktop-$releaseVersion.jar"),
            ($archiveRoot + 'runtime/bin/server/jvm.dll'), ($archiveRoot + 'Quick-Start.txt'),
            ($archiveRoot + $thanksName), ($archiveRoot + $tutorialName), ($archiveRoot + 'Ocean.ico'), ($archiveRoot + 'LICENSE'),
            ($archiveRoot + 'THIRD-PARTY-NOTICES.md'), ($archiveRoot + 'FILES-SHA256.txt'))
        foreach ($name in $required) {
            if (!$zip.GetEntry($name)) { throw "Missing package entry: $name" }
        }
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            if (!$entry.FullName.StartsWith($archiveRoot)) { throw 'Unexpected archive root.' }
            if ($entry.FullName -match '(^|/)(store|accounts?|pool|logs|credentials[^/]*|\.env[^/]*|instance\.lock|\.test-data|\.zcode)(/|$)' -or
                $entry.FullName -match '\.(sqlite|log|bak|bin)$') { throw "Unexpected user data in archive: $($entry.FullName)" }
            $relative = $entry.FullName.Substring($archiveRoot.Length)
            if ($relative -eq 'FILES-SHA256.txt') { continue }
            if (!$fileHashes.ContainsKey($relative)) { throw "Unexpected entry: $relative" }
            $hashAlgorithm = [Security.Cryptography.SHA256]::Create()
            $stream = $entry.Open()
            try {
                $actual = [BitConverter]::ToString($hashAlgorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
                if ($actual -ne $fileHashes[$relative]) { throw "Archive integrity check failed: $relative" }
            } finally { $stream.Dispose(); $hashAlgorithm.Dispose() }
        }
        Write-Host "Verified $($zip.Entries.Count) archive entries."
    } finally { $zip.Dispose() }
    Copy-Item -LiteralPath $temporaryZip -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($destination + '.sha256'), "$hash  $archiveName`n", [Text.UTF8Encoding]::new($false))
    $size = [Math]::Round((Get-Item -LiteralPath $destination).Length / 1MB, 1)
    Write-Host "Created: $destination ($size MiB)"
} finally {
    if (Test-Path -LiteralPath $staging) {
        $resolved = (Resolve-Path -LiteralPath $staging).Path
        $expected = [IO.Path]::GetFullPath($staging)
        if ($resolved -ne $expected -or !$resolved.StartsWith([IO.Path]::GetFullPath($workRoot) + [IO.Path]::DirectorySeparatorChar)) {
            throw 'Temporary directory cleanup validation failed.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
