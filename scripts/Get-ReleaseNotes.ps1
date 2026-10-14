param([string]$Version)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($Version)) {
    $buildConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'build.gradle.kts') -Raw -Encoding UTF8
    if ($buildConfig -notmatch 'version = "(\d+\.\d+\.\d+)"') { throw 'Project version missing.' }
    $Version = $Matches[1]
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid release version.' }
$branding = Get-Content -LiteralPath (Join-Path $projectRoot 'branding.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$path = Join-Path $projectRoot 'CHANGELOG.md'
if (!(Test-Path -LiteralPath $path)) { throw 'CHANGELOG.md missing. Document changes before packaging.' }
$history = Get-Content -LiteralPath $path -Raw -Encoding UTF8
$pattern = '(?ms)^## \[' + [regex]::Escape($Version) + '\][^\r\n]*\r?\n(?<body>.*?)(?=^## \[|\z)'
$sections = [regex]::Matches($history, $pattern)
if ($sections.Count -ne 1 -or $sections[0].Groups['body'].Value -notmatch '(?m)^-\s+\S') {
    throw "CHANGELOG.md must contain exactly one non-empty update section for $Version."
}
function ConvertTo-PlainText([string]$Text) {
    $plain = [regex]::Replace($Text, '\[([^\]]+)\]\((https?://[^)]+)\)', '$1 ($2)')
    $plain = [regex]::Replace($plain, '(?m)^#{1,6}\s+', '')
    $plain = $plain.Replace('**', '').Replace([string][char]96, '')
    return ($plain.Trim() -replace '\r?\n', "`r`n") + "`r`n"
}
$body = $sections[0].Groups['body'].Value.Trim()
$intro = [regex]::Split($history, '(?m)^## \[')[0].Trim()
[PSCustomObject]@{
    Version = $Version
    HistoryText = ConvertTo-PlainText $history
    CurrentText = ConvertTo-PlainText ("$($branding.name) $Version`n`n$body")
    ReleaseMarkdown = "$intro`n`n$($sections[0].Value.Trim())`n"
}
