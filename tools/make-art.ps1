Add-Type -AssemblyName System.Drawing

$out = 'D:\deepseekharness\charta-daa\src\main\resources\assets\daa'
New-Item -ItemType Directory -Force -Path "$out\textures\gui\game" | Out-Null

function New-BigA([int]$size, [string]$path) {
  $bmp = [System.Drawing.Bitmap]::new($size, $size)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.Clear([System.Drawing.Color]::Transparent)

  $pad = [int]($size * 0.06)
  $r = [int]($size * 0.14)
  $inner = $size - (2 * $pad)
  $rect = [System.Drawing.Rectangle]::new($pad, $pad, $inner, $inner)
  $d = 2 * $r

  $shape = [System.Drawing.Drawing2D.GraphicsPath]::new()
  $shape.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
  $shape.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
  $shape.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
  $shape.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
  $shape.CloseFigure()

  $brush = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
      $rect,
      [System.Drawing.Color]::FromArgb(255, 24, 66, 46),
      [System.Drawing.Color]::FromArgb(255, 8, 26, 20),
      [single]90)
  $g.FillPath($brush, $shape)
  $rim = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(255, 214, 178, 84), [single]($size * 0.018))
  $g.DrawPath($rim, $shape)

  $cw = [int]($size * 0.40)
  $ch = [int]($size * 0.56)
  $cx = [int]($size * 0.30)
  $cy = [int]($size * 0.16)
  $card = [System.Drawing.Rectangle]::new($cx, $cy, $cw, $ch)
  $g.FillRectangle([System.Drawing.Brushes]::White, $card)
  $edge = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(255, 40, 40, 40), [single]($size * 0.008))
  $g.DrawRectangle($edge, $card)

  $font = [System.Drawing.Font]::new('Georgia', [single]($ch * 0.62), [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
  $format = [System.Drawing.StringFormat]::new()
  $format.Alignment = [System.Drawing.StringAlignment]::Center
  $format.LineAlignment = [System.Drawing.StringAlignment]::Center
  $textBox = [System.Drawing.RectangleF]::new($cx, $cy + ($ch * 0.04), $cw, ($ch * 0.62))
  $g.DrawString('A', $font, [System.Drawing.Brushes]::Crimson, $textBox, $format)

  # A gold star for the jokers, in the free corner the card leaves.
  $scx = [single]($size * 0.76)
  $scy = [single]($size * 0.72)
  $sr = [single]($size * 0.13)
  $points = @()
  for ($i = 0; $i -lt 10; $i++) {
    $angle = (-[Math]::PI / 2) + ($i * [Math]::PI / 5)
    $radius = if ($i % 2 -eq 0) { $sr } else { $sr * 0.42 }
    $points += [System.Drawing.PointF]::new(
        [single]($scx + ($radius * [Math]::Cos($angle))),
        [single]($scy + ($radius * [Math]::Sin($angle))))
  }
  $gold = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 230, 196, 96))
  $g.FillPolygon($gold, $points)

  $g.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

New-BigA 560 "$out\textures\gui\game\big_a.png"
New-BigA 128 "$out\icon.png"
Get-ChildItem -Recurse $out -File | Select-Object Name, Length
