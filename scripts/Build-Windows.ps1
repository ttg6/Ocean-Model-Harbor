param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$buildConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'build.gradle.kts') -Raw -Encoding UTF8
if ($buildConfig -notmatch 'version = "(\d+\.\d+\.\d+)"') { throw 'Project version missing.' }
$releaseVersion = $Matches[1]
$branding = Get-Content -LiteralPath (Join-Path $projectRoot 'branding.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$appName = $branding.name
$archivePrefix = $branding.archivePrefix
$localJdk = Get-ChildItem (Join-Path $projectRoot '.tools/jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/jpackage.exe'))) {
    throw 'JDK 17 not found. Run scripts/Bootstrap.ps1 or set JAVA_HOME.'
}
$gradle = Join-Path $projectRoot '.tools/gradle/gradle-8.12.1/bin/gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) { throw 'Run scripts/Bootstrap.ps1 first.' }
# Some Windows installations accept AF_UNIX bind but reject connect. Force JDK's TCP fallback for build workers.
$previousOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "$previousOptions -Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=C:/aigw-no-unix-sockets"
try {
    # Generate the shared PNG before processResources so the JVM and EXE use the same artwork.
    $iconPath = Join-Path $projectRoot '.tools/app.ico'
    & (Join-Path $PSScriptRoot 'Create-Icon.ps1') -Destination $iconPath
    $tasks = @(':desktop:installDist')
    if (!$SkipTests) { $tasks = @(':core:test', ':desktop:test') + $tasks }
    & $gradle @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed. No release generated.' }
    $dist = Join-Path $projectRoot 'dist'
    $releaseDir = Join-Path $dist ('release-' + $releaseVersion)
    $image = Join-Path $releaseDir $appName
    if (Test-Path -LiteralPath $image) {
        $resolved = (Resolve-Path -LiteralPath $image).Path
        $expected = [IO.Path]::GetFullPath($image)
        if ($resolved -ne $expected -or !($resolved.StartsWith([IO.Path]::GetFullPath($dist) + [IO.Path]::DirectorySeparatorChar))) { throw 'Release directory validation failed.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    & (Join-Path $env:JAVA_HOME 'bin/jpackage.exe') --type app-image --name $appName --app-version $releaseVersion `
        --vendor 'Ocean' --description 'Ocean multi-account AI model gateway for Windows' `
        --input ("desktop/build/install/" + $branding.distributionName + '/lib') --main-jar "desktop-$releaseVersion.jar" `
        --main-class dev.aigw.windows.MainKt --dest $releaseDir --icon $iconPath --java-options '-Dfile.encoding=UTF-8' `
        --java-options '-Djava.net.preferIPv4Stack=true'
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    Copy-Item -LiteralPath $iconPath -Destination (Join-Path $image 'Ocean.ico')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination $image
    Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination $image
    Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD-PARTY-NOTICES.md') -Destination $image
    Copy-Item -LiteralPath (Join-Path $projectRoot 'docs') -Destination (Join-Path $image 'docs') -Recurse
    $archiveName = "$archivePrefix-$releaseVersion-x64.zip"
    Compress-Archive -LiteralPath $image -DestinationPath (Join-Path $dist $archiveName) -Force
    $archiveHash = (Get-FileHash -LiteralPath (Join-Path $dist $archiveName) -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $dist 'SHA256.txt'), "$archiveHash  $archiveName`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Created: $dist/$archiveName"
} finally { $env:JAVA_TOOL_OPTIONS = $previousOptions }
