param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$resourceDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'desktop/src/main/resources/branding'
New-Item -ItemType Directory -Path $resourceDir -Force | Out-Null
$bitmap = New-Object Drawing.Bitmap 256,256
$g = [Drawing.Graphics]::FromImage($bitmap)
$g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([Drawing.Color]::Transparent)
$path = New-Object Drawing.Drawing2D.GraphicsPath
$radius = 52
$path.AddArc(1,1,$radius,$radius,180,90)
$path.AddArc((255-$radius),1,$radius,$radius,270,90)
$path.AddArc((255-$radius),(255-$radius),$radius,$radius,0,90)
$path.AddArc(1,(255-$radius),$radius,$radius,90,90)
$path.CloseFigure()
$brush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(16,44,67))
$pen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(169,240,228)),14
$pen.StartCap = [Drawing.Drawing2D.LineCap]::Round
$pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
$pen.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
try {
    $g.FillPath($brush,$path)
    $g.DrawEllipse($pen,109,42,38,38)
    $g.DrawLine($pen,128,80,128,203)
    $g.DrawLine($pen,88,110,168,110)
    $g.DrawArc($pen,59,120,138,85,0,180)
    $g.DrawLine($pen,59,164,77,157)
    $g.DrawLine($pen,59,164,67,182)
    $g.DrawLine($pen,197,164,179,157)
    $g.DrawLine($pen,197,164,189,182)
    $bitmap.Save((Join-Path $resourceDir 'ocean-icon.png'),[Drawing.Imaging.ImageFormat]::Png)
    $sizes = @(16,20,24,32,40,48,64,128,256)
    $images = @()
    foreach ($size in $sizes) {
        $scaled = New-Object Drawing.Bitmap $size,$size
        $sg = [Drawing.Graphics]::FromImage($scaled)
        $ms = New-Object IO.MemoryStream
        try {
            $sg.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $sg.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $sg.DrawImage($bitmap,0,0,$size,$size)
            $scaled.Save($ms,[Drawing.Imaging.ImageFormat]::Png)
            $images += ,$ms.ToArray()
        } finally { $ms.Dispose(); $sg.Dispose(); $scaled.Dispose() }
    }
    $destinationDir = Split-Path ([IO.Path]::GetFullPath($Destination)) -Parent
    New-Item -ItemType Directory -Path $destinationDir -Force | Out-Null
    $stream = [IO.File]::Create($Destination)
    $writer = New-Object IO.BinaryWriter $stream
    try {
        $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
        $offset = 6 + 16 * $sizes.Count
        for ($i=0; $i -lt $sizes.Count; $i++) {
            $dimension = if ($sizes[$i] -eq 256) { 0 } else { $sizes[$i] }
            $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
            $writer.Write([byte]0); $writer.Write([byte]0)
            $writer.Write([uint16]1); $writer.Write([uint16]32)
            $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
            $offset += $images[$i].Length
        }
        foreach ($bytes in $images) { $writer.Write([byte[]]$bytes) }
    } finally { $writer.Dispose(); $stream.Dispose() }
} finally { $pen.Dispose(); $brush.Dispose(); $path.Dispose(); $g.Dispose(); $bitmap.Dispose() }
