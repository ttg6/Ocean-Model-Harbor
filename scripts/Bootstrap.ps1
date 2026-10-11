param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null

function Get-VerifiedArchive([string]$Url, [string]$Destination, [string]$ExpectedHash) {
    if (!(Test-Path -LiteralPath $Destination)) {
        & curl.exe --silent --show-error --fail --location --retry 2 --output $Destination $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    }
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
    if ($actual -ine $ExpectedHash.Trim()) { throw "Checksum failed: $Destination. Remove this archive and retry." }
}

if (!(Get-ChildItem (Join-Path $toolRoot 'jdk') -Directory -ErrorAction SilentlyContinue)) {
    $release = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = $release[0].binary.package
    $archive = Join-Path $toolRoot 'jdk.zip'
    Get-VerifiedArchive $package.link $archive $package.checksum
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $toolRoot 'jdk') -Force
}

$gradleDir = Join-Path $toolRoot 'gradle/gradle-8.12.1'
if (!(Test-Path -LiteralPath $gradleDir)) {
    $url = 'https://services.gradle.org/distributions/gradle-8.12.1-bin.zip'
    $checksum = Invoke-RestMethod "$url.sha256"
    $archive = Join-Path $toolRoot 'gradle.zip'
    Get-VerifiedArchive $url $archive $checksum
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $toolRoot 'gradle') -Force
}
Write-Host 'Tools ready. Run scripts/Build-Windows.ps1 to create the portable Windows release.'
